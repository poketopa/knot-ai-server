package com.knot.ai.transcription.infrastructure;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
class SupervisorJvmLossTest {
    @TempDir
    Path directory;
    @Test
    @DisplayName("실제 JVM 강제 종료 후 소유한 Node와 native 그룹만 회수한다")
    void supervise_success_actualJvmLoss() throws Exception {
        // given
        Path ownership = directory.resolve("process-owner.json");
        Path supervisor = Path.of("runner/scripts/serverSupervisor.py")
                .toAbsolutePath()
                .normalize();
        Path probe = Path.of("src/test/resources/SupervisorJvmProbe.java")
                .toAbsolutePath();
        Process unrelated = new ProcessBuilder(
                "/bin/sleep",
                "120"
        ).start();
        Process jvm = new ProcessBuilder(
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        "java"
                )
                        .toString(),
                probe.toString(),
                supervisor.toString(),
                ownership.toString(),
                directory.toString()
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        JsonMapper mapper = JsonMapper.builder()
                .build();
        try {
            JsonNode running = waitForState(
                    mapper,
                    ownership,
                    "RUNNING",
                    2
            );
            assertEquals(
                    jvm.pid(),
                    running.path("parentPid")
                            .asLong()
            );
            // when
            jvm.destroyForcibly();
            // then
            assertTrue(
                    jvm.waitFor(
                            5,
                            TimeUnit.SECONDS
                    )
            );
            JsonNode cleaned = waitForState(
                    mapper,
                    ownership,
                    "CLEANED",
                    0
            );
            assertEquals(
                    "CLEANED",
                    cleaned.path("state")
                            .asString()
            );
            assertTrue(unrelated.isAlive());
            for (String pid : running.path("members")
                    .propertyNames()) {
                assertFalse(
                        ProcessHandle.of(Long.parseLong(pid))
                                .map(ProcessHandle::isAlive)
                                .orElse(false)
                );
            }
        } finally {
            jvm.destroyForcibly();
            unrelated.destroyForcibly();
            unrelated.waitFor(
                    5,
                    TimeUnit.SECONDS
            );
            if (Files.exists(ownership)) {
                Process cleanup = new ProcessBuilder(
                        "python3",
                        supervisor.toString(),
                        "--cleanup",
                        ownership.toString()
                ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!cleanup.waitFor(
                        10,
                        TimeUnit.SECONDS
                )) {
                    cleanup.destroyForcibly();
                }
            }
        }
    }
    private JsonNode waitForState(
            JsonMapper mapper,
            Path ownership,
            String state,
            int minimumMembers
    ) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15)
                .toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(ownership)) {
                JsonNode record = mapper.readTree(ownership.toFile());
                if (record.path("state")
                        .asString()
                        .equals(state)
                        && record.path("members")
                                .size() >= minimumMembers) {
                    return record;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("감독 프로세스가 제한 시간 안에 상태 전이를 완료하지 않았습니다");
    }
}
