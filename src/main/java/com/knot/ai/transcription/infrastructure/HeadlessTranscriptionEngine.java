package com.knot.ai.transcription.infrastructure;

import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.TranscriptionEngine;
import com.knot.ai.transcription.application.dto.command.CreateJobCommand;
import com.knot.ai.transcription.domain.JobErrorCode;
import com.knot.ai.transcription.domain.JobException;
import com.knot.ai.transcription.domain.TranscriptResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HeadlessTranscriptionEngine implements TranscriptionEngine {
    private final AiServerProperties properties;
    private final S3AudioDownloader downloader;
    private final JsonMapper mapper;
    private final JsonNode profile;
    private final ProcessSupervisor supervisor;
    private final AudioInspector audioInspector;
    private volatile boolean ready;

    public HeadlessTranscriptionEngine(
            AiServerProperties properties,
            S3AudioDownloader downloader,
            JsonMapper mapper
    ) throws IOException {
        this.properties = properties;
        this.downloader = downloader;
        this.mapper = mapper;
        profile = mapper.readTree(
                properties.profileFile()
                        .toFile()
        );
        validateProfile();
        Files.createDirectories(properties.workDirectory());
        supervisor = new ProcessSupervisor(
                properties,
                mapper,
                this::markNotReady
        );
        audioInspector = new AudioInspector();
        preflight();
        ready = true;
    }

    @Override
    public String getProfileId() {
        return profile.path("id")
                .asString();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public TranscriptResult transcribe(CreateJobCommand command) throws Exception {
        Path directory = directory(command.attemptId());
        Files.createDirectory(directory);
        Files.setPosixFilePermissions(
                directory,
                PosixFilePermissions.fromString("rwx------")
        );
        Path source = directory.resolve("input.webm");
        Path wav = directory.resolve("input.wav");
        downloader.download(
                command.input(),
                source
        );
        supervisor.runDecoding(
                List.of(
                        properties.ffprobe(),
                        "-v",
                        "error",
                        "-show_entries",
                        "format=duration",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        "-o",
                        directory.resolve("duration.txt")
                                .toString(),
                        source.toString()
                ),
                directory,
                properties.conversionTimeout()
        );
        audioInspector.verifyContainerDuration(directory.resolve("duration.txt"));
        supervisor.runDecoding(
                List.of(
                        properties.ffmpeg(),
                        "-nostdin",
                        "-v",
                        "error",
                        "-y",
                        "-i",
                        source.toString(),
                        "-vn",
                        "-ac",
                        "1",
                        "-ar",
                        "16000",
                        "-c:a",
                        "pcm_s16le",
                        "-fs",
                        "231448576",
                        wav.toString()
                ),
                directory,
                properties.conversionTimeout()
        );
        double duration = audioInspector.readPcmDuration(wav);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put(
                "schemaVersion",
                1
        );
        manifest.put(
                "audioPath",
                wav.toString()
        );
        manifest.put(
                "outputDirectory",
                directory.toString()
        );
        manifest.put(
                "profile",
                profile
        );
        manifest.put(
                "timeoutMs",
                properties.inferenceTimeout()
                        .toMillis()
        );
        if (command.speakerCount() != null) {
            manifest.put(
                    "speakerCount",
                    command.speakerCount()
            );
        }
        Path input = directory.resolve("input.json");
        mapper.writeValue(
                input.toFile(),
                manifest
        );
        supervisor.runInference(
                List.of(
                        properties.pnpm(),
                        "exec",
                        "tsx",
                        "scripts/serverPipeline.ts",
                        "--manifest",
                        input.toString()
                ),
                directory,
                properties.inferenceTimeout()
        );
        Path resultFile = directory.resolve("result.json");
        if (!Files.isRegularFile(resultFile) || Files.size(resultFile) > properties.maximumResultBytes()) {
            throw new JobException(JobErrorCode.INVALID_RESULT);
        }
        TranscriptResult result = mapper.readValue(
                resultFile.toFile(),
                TranscriptResult.class
        );
        JsonNode raw = mapper.readTree(result.rawWhisperJson());
        if (!raw.isObject() || !raw.path("transcription")
                .isArray()) {
            throw new JobException(JobErrorCode.INVALID_RESULT);
        }
        for (TranscriptResult.Utterance utterance : result.utterances()) {
            if (utterance.endSec() > duration + 1) {
                throw new JobException(JobErrorCode.INVALID_RESULT);
            }
        }
        return result;
    }

    @Override
    public void cleanup(UUID attemptId) throws Exception {
        Path directory = directory(attemptId);
        if (Files.isSymbolicLink(directory)) {
            throw new JobException(JobErrorCode.UNOWNED_WORK_DIRECTORY);
        }
        if (!Files.exists(directory)) {
            return;
        }
        supervisor.cleanupOwnership(directory);
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.delete(file);
            }
        }
    }

    private Path directory(UUID attemptId) {
        return properties.workDirectory()
                .toAbsolutePath()
                .normalize()
                .resolve(attemptId.toString());
    }

    private void preflight() throws IOException {
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(properties.workDirectory())) {
            for (Path directory : directories) {
                if (Files.isSymbolicLink(directory)) {
                    throw new JobException(JobErrorCode.UNOWNED_WORK_DIRECTORY);
                }
                if (!Files.isDirectory(
                        directory,
                        LinkOption.NOFOLLOW_LINKS
                )) {
                    continue;
                }
                UUID attemptId;
                try {
                    attemptId = UUID.fromString(
                            directory.getFileName()
                                    .toString()
                    );
                } catch (IllegalArgumentException exception) {
                    throw new JobException(JobErrorCode.UNOWNED_WORK_DIRECTORY);
                }
                try {
                    cleanup(attemptId);
                } catch (Exception exception) {
                    throw new JobException(JobErrorCode.PROCESS_CLEANUP_FAILED);
                }
            }
        }
    }

    private Path supervisor() {
        return properties.desktopDirectory()
                .resolve("scripts/serverSupervisor.py");
    }

    private void validateProfile() {
        if (!profile.path("id")
                .isString() || profile.path("id")
                        .asString()
                        .isBlank()) {
            throw new JobException(JobErrorCode.INVALID_PROFILE);
        }
        for (String command : List.of(
                properties.python(),
                properties.pnpm(),
                properties.ffmpeg(),
                properties.ffprobe(),
                "node"
        )) {
            if (!hasExecutable(command)) {
                throw new JobException(JobErrorCode.MISSING_RUNTIME);
            }
        }
        for (String field : List.of(
                "whisperBinary",
                "diarizeBinary",
                "whisperModel",
                "vadModel",
                "segmentationModel",
                "embeddingModel"
        )) {
            Path asset = Path.of(
                    profile.path(field)
                            .asString()
            );
            if (!asset.isAbsolute() || !Files.isRegularFile(asset) || !Files.isReadable(asset)) {
                throw new JobException(JobErrorCode.INVALID_PROFILE);
            }
        }
        if (profile.path("sttThreads")
                .asInt() <= 0
                || profile.path("diarizeThreads")
                        .asInt() <= 0
                || !profile.path("parallel")
                        .isBoolean()
                || !Files.isRegularFile(supervisor())) {
            throw new JobException(JobErrorCode.INVALID_PROFILE);
        }
    }

    private boolean hasExecutable(String command) {
        Path path = Path.of(command);
        if (path.isAbsolute()) {
            return Files.isRegularFile(path) && Files.isExecutable(path);
        }
        String searchPath = System.getenv("PATH");
        if (searchPath == null) {
            return false;
        }
        for (String entry : searchPath.split(File.pathSeparator)) {
            Path candidate = Path.of(entry)
                    .resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void markNotReady() {
        ready = false;
    }
}
