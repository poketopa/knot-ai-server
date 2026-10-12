package com.knot.ai.transcription.infrastructure;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.*;
import com.knot.ai.transcription.domain.*;
@Component
public class HeadlessTranscriptionEngine implements TranscriptionEngine {
    private final AiServerProperties properties;
    private final S3AudioDownloader downloader;
    private final JsonMapper mapper;
    private final JsonNode profile;
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
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        );
        Path source = directory.resolve("input.webm");
        Path wav = directory.resolve("input.wav");
        downloader.download(
                command.input(),
                source
        );
        supervise(
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
        String probeDuration = Files.readString(directory.resolve("duration.txt"))
                .trim();
        if (!probeDuration.equals("N/A")) {
            double declaredDuration;
            try {
                declaredDuration = Double.parseDouble(probeDuration);
            } catch (NumberFormatException exception) {
                throw new JobException(
                        "INVALID_AUDIO_DURATION",
                        400
                );
            }
            if (!Double.isFinite(declaredDuration) || declaredDuration <= 0 || declaredDuration > 7200) {
                throw new JobException(
                        "AUDIO_DURATION_EXCEEDED",
                        400
                );
            }
        }
        supervise(
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
        // MediaRecorder WebM may omit duration; decoded PCM is the final source of
        // truth.
        double duration = readPcmDuration(wav);
        if (duration <= 0 || duration > 7200) {
            throw new JobException(
                    "AUDIO_DURATION_EXCEEDED",
                    400
            );
        }
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
        supervise(
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
            throw new JobException(
                    "INVALID_RESULT",
                    500
            );
        }
        TranscriptResult result = mapper.readValue(
                resultFile.toFile(),
                TranscriptResult.class
        );
        JsonNode raw = mapper.readTree(result.rawWhisperJson());
        if (!raw.isObject() || !raw.path("transcription")
                .isArray()) {
            throw new JobException(
                    "INVALID_RESULT",
                    500
            );
        }
        for (TranscriptResult.Utterance utterance : result.utterances()) {
            if (utterance.endSec() > duration + 1) {
                throw new JobException(
                        "INVALID_RESULT",
                        500
                );
            }
        }
        return result;
    }
    @Override
    public void cleanup(UUID attemptId) throws Exception {
        Path directory = directory(attemptId);
        if (Files.isSymbolicLink(directory)) {
            throw new JobException(
                    "UNOWNED_WORK_DIRECTORY",
                    503
            );
        }
        if (!Files.exists(directory)) {
            return;
        }
        cleanupOwnership(directory);
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
    private void supervise(
            List<String> command,
            Path directory,
            Duration timeout
    ) throws Exception {
        cleanupOwnership(directory);
        boolean decoding = command.getFirst()
                .equals(properties.ffprobe())
                || command.getFirst()
                        .equals(properties.ffmpeg());
        List<String> arguments = new ArrayList<>(
                List.of(
                        properties.python(),
                        supervisor().toString(),
                        "--parent-pid",
                        Long.toString(
                                ProcessHandle.current()
                                        .pid()
                        ),
                        "--ownership",
                        directory.resolve("process-owner.json")
                                .toString(),
                        "--cwd",
                        properties.desktopDirectory()
                                .toString(),
                        "--deadline-seconds",
                        Long.toString(timeout.toSeconds()),
                        "--"
                )
        );
        arguments.addAll(command);
        Process process = new ProcessBuilder(arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(
                    timeout.toMillis() + 15_000,
                    TimeUnit.MILLISECONDS
            )) {
                process.destroy();
                if (!process.waitFor(
                        15,
                        TimeUnit.SECONDS
                )) {
                    process.destroyForcibly();
                }
                throw new JobException(
                        decoding ? "DECODE_TIMEOUT" : "INFERENCE_TIMEOUT",
                        504
                );
            }
            if (process.exitValue() == 124) {
                throw new JobException(
                        decoding ? "DECODE_TIMEOUT" : "INFERENCE_TIMEOUT",
                        504
                );
            }
            if (process.exitValue() != 0) {
                throw new JobException(
                        decoding ? "DECODE_FAILED" : readDiagnostic(directory),
                        500
                );
            }
        } catch (InterruptedException exception) {
            process.destroy();
            if (!process.waitFor(
                    15,
                    TimeUnit.SECONDS
            )) {
                process.destroyForcibly();
                if (!process.waitFor(
                        5,
                        TimeUnit.SECONDS
                )) {
                    ready = false;
                }
            }
            throw new JobException(
                    "CANCELED",
                    409
            );
        } finally {
            cleanupOwnership(directory);
        }
    }
    private String readDiagnostic(Path directory) {
        Path file = directory.resolve("diagnostic.json");
        if (Files.isRegularFile(file)) {
            try {
                String code = mapper.readTree(file.toFile())
                        .path("code")
                        .asString();
                if (code.matches("[A-Z_]{1,64}")) {
                    return code;
                }
            } catch (RuntimeException exception) {
                return "INFERENCE_FAILED";
            }
        }
        return "INFERENCE_FAILED";
    }
    private void cleanupOwnership(Path directory) throws IOException, InterruptedException {
        Path ownership = directory.resolve("process-owner.json");
        if (!Files.exists(ownership)) {
            return;
        }
        Process process = new ProcessBuilder(
                properties.python(),
                supervisor().toString(),
                "--cleanup",
                ownership.toString()
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(
                15,
                TimeUnit.SECONDS
        )) {
            process.destroyForcibly();
            ready = false;
            throw new JobException(
                    "PROCESS_CLEANUP_FAILED",
                    503
            );
        }
        if (process.exitValue() != 0) {
            ready = false;
            throw new JobException(
                    "PROCESS_CLEANUP_FAILED",
                    503
            );
        }
    }
    private void preflight() throws IOException {
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(properties.workDirectory())) {
            for (Path directory : directories) {
                if (Files.isSymbolicLink(directory)) {
                    throw new JobException(
                            "UNOWNED_WORK_DIRECTORY",
                            503
                    );
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
                    throw new JobException(
                            "UNOWNED_WORK_DIRECTORY",
                            503
                    );
                }
                try {
                    cleanup(attemptId);
                } catch (Exception exception) {
                    throw new JobException(
                            "PROCESS_CLEANUP_FAILED",
                            503
                    );
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
            throw new JobException(
                    "INVALID_PROFILE",
                    503
            );
        }
        for (String command : List.of(
                properties.python(),
                properties.pnpm(),
                properties.ffmpeg(),
                properties.ffprobe(),
                "node"
        )) {
            if (!hasExecutable(command)) {
                throw new JobException(
                        "MISSING_RUNTIME",
                        503
                );
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
                throw new JobException(
                        "INVALID_PROFILE",
                        503
                );
            }
        }
        if (profile.path("sttThreads")
                .asInt() <= 0
                || profile.path("diarizeThreads")
                        .asInt() <= 0
                || !profile.path("parallel")
                        .isBoolean()
                || !Files.isRegularFile(supervisor())) {
            throw new JobException(
                    "INVALID_PROFILE",
                    503
            );
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
        for (String entry : searchPath.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(entry)
                    .resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    private double readPcmDuration(Path wav) throws IOException {
        long maximumPcmBytes = 230_400_000L;
        if (Files.size(wav) > maximumPcmBytes + 1_048_576L) {
            throw new JobException(
                    "AUDIO_DURATION_EXCEEDED",
                    400
            );
        }
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(
                wav.toFile(),
                "r"
        )) {
            if (file.length() < 44 || file.readInt() != 0x52494646) {
                throw new JobException(
                        "INVALID_AUDIO_FORMAT",
                        400
                );
            }
            file.skipBytes(4);
            if (file.readInt() != 0x57415645) {
                throw new JobException(
                        "INVALID_AUDIO_FORMAT",
                        400
                );
            }
            boolean formatVerified = false;
            while (file.getFilePointer() + 8 <= file.length()) {
                int chunkId = file.readInt();
                long chunkSize = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
                long position = file.getFilePointer();
                if (chunkSize > file.length() - position) {
                    throw new JobException(
                            "INVALID_AUDIO_FORMAT",
                            400
                    );
                }
                if (chunkId == 0x666d7420) {
                    if (chunkSize < 16 || Short.reverseBytes(file.readShort()) != 1
                            || Short.reverseBytes(file.readShort()) != 1
                            || Integer.reverseBytes(file.readInt()) != 16000
                            || Integer.reverseBytes(file.readInt()) != 32000
                            || Short.reverseBytes(file.readShort()) != 2
                            || Short.reverseBytes(file.readShort()) != 16) {
                        throw new JobException(
                                "INVALID_AUDIO_FORMAT",
                                400
                        );
                    }
                    formatVerified = true;
                } else if (chunkId == 0x64617461) {
                    if (!formatVerified || chunkSize % 2 != 0) {
                        throw new JobException(
                                "INVALID_AUDIO_FORMAT",
                                400
                        );
                    }
                    if (chunkSize > maximumPcmBytes) {
                        throw new JobException(
                                "AUDIO_DURATION_EXCEEDED",
                                400
                        );
                    }
                    return chunkSize / 32000.0;
                }
                file.seek(position + chunkSize + (chunkSize % 2));
            }
        }
        throw new JobException(
                "INVALID_AUDIO_FORMAT",
                400
        );
    }

}
