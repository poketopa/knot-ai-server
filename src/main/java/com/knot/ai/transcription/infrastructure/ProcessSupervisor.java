package com.knot.ai.transcription.infrastructure;

import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.domain.JobErrorCode;
import com.knot.ai.transcription.domain.JobException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProcessSupervisor {
    private final AiServerProperties properties;
    private final JsonMapper mapper;
    private final Runnable markNotReady;

    ProcessSupervisor(
            AiServerProperties properties,
            JsonMapper mapper,
            Runnable markNotReady
    ) {
        this.properties = properties;
        this.mapper = mapper;
        this.markNotReady = markNotReady;
    }

    void runDecoding(
            List<String> command,
            Path directory,
            Duration timeout
    ) throws Exception {
        supervise(
                command,
                directory,
                timeout,
                JobErrorCode.DECODE_TIMEOUT,
                JobErrorCode.DECODE_FAILED
        );
    }

    void runInference(
            List<String> command,
            Path directory,
            Duration timeout
    ) throws Exception {
        supervise(
                command,
                directory,
                timeout,
                JobErrorCode.INFERENCE_TIMEOUT,
                null
        );
    }

    void cleanupOwnership(Path directory) throws IOException, InterruptedException {
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
            markNotReady.run();
            throw new JobException(JobErrorCode.PROCESS_CLEANUP_FAILED);
        }
        if (process.exitValue() != 0) {
            markNotReady.run();
            throw new JobException(JobErrorCode.PROCESS_CLEANUP_FAILED);
        }
    }

    private void supervise(
            List<String> command,
            Path directory,
            Duration timeout,
            JobErrorCode timeoutCode,
            JobErrorCode failureCode
    ) throws Exception {
        cleanupOwnership(directory);
        Process process = new ProcessBuilder(
                arguments(
                        command,
                        directory,
                        timeout
                )
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(
                    timeout.toMillis() + 15_000,
                    TimeUnit.MILLISECONDS
            )) {
                stop(process);
                throw new JobException(timeoutCode);
            }
            if (process.exitValue() == 124) {
                throw new JobException(timeoutCode);
            }
            if (process.exitValue() != 0) {
                throw new JobException(failureCode == null ? readDiagnostic(directory) : failureCode);
            }
        } catch (InterruptedException exception) {
            stopAfterInterrupt(process);
            throw new JobException(JobErrorCode.CANCELED);
        } finally {
            cleanupOwnership(directory);
        }
    }

    private List<String> arguments(
            List<String> command,
            Path directory,
            Duration timeout
    ) {
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
                        properties.runnerDirectory()
                                .toString(),
                        "--deadline-seconds",
                        Long.toString(timeout.toSeconds()),
                        "--"
                )
        );
        arguments.addAll(command);
        return arguments;
    }

    private Path supervisor() {
        return properties.runnerDirectory()
                .resolve("scripts/serverSupervisor.py");
    }

    private void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(
                15,
                TimeUnit.SECONDS
        )) {
            process.destroyForcibly();
        }
    }

    private void stopAfterInterrupt(Process process) throws InterruptedException {
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
                markNotReady.run();
            }
        }
    }

    private JobErrorCode readDiagnostic(Path directory) {
        Path file = directory.resolve("diagnostic.json");
        if (Files.isRegularFile(file)) {
            try {
                JsonNode code = mapper.readTree(file.toFile())
                        .path("code");
                if (code.isString()) {
                    return switch (code.asString()) {
                        case "CANCELLED" -> JobErrorCode.CANCELED;
                        case "INFERENCE_TIMEOUT" -> JobErrorCode.INFERENCE_TIMEOUT;
                        case "STT_FAILED" -> JobErrorCode.STT_FAILED;
                        case "DIARIZATION_FAILED" -> JobErrorCode.DIARIZATION_FAILED;
                        case "DIARIZATION_EMPTY" -> JobErrorCode.DIARIZATION_EMPTY;
                        case "OUTPUT_LIMIT" -> JobErrorCode.OUTPUT_LIMIT;
                        case "INVALID_MANIFEST" -> JobErrorCode.INVALID_MANIFEST;
                        case "INVALID_ARGUMENTS" -> JobErrorCode.INVALID_ARGUMENTS;
                        case "INVALID_RESULT" -> JobErrorCode.INVALID_RESULT;
                        case "RUNNER_FAILED" -> JobErrorCode.RUNNER_FAILED;
                        default -> JobErrorCode.INFERENCE_FAILED;
                    };
                }
            } catch (RuntimeException exception) {
                return JobErrorCode.INFERENCE_FAILED;
            }
        }
        return JobErrorCode.INFERENCE_FAILED;
    }
}
