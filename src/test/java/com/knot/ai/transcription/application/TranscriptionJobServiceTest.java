package com.knot.ai.transcription.application;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.knot.ai.TestFixtures;
import com.knot.ai.transcription.domain.*;
class TranscriptionJobServiceTest {
    @TempDir
    Path directory;
    private final FakeEngine engine = new FakeEngine();
    private TranscriptionJobService service;
    private final MutableClock clock = new MutableClock();
    @BeforeEach
    void prepare() {
        service = new TranscriptionJobService(
                engine,
                TestFixtures.properties(directory),
                clock
        );
    }
    @AfterEach
    void close() {
        service.close();
    }
    @Test
    @DisplayName("같은 요청의 새 서명 URL은 기존 작업으로 수렴한다")
    void create_success_sameIdentityNewUrl() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        service.create(
                command(
                        attemptId,
                        "https://first",
                        "key"
                )
        );
        assertTrue(
                engine.started.await(
                        2,
                        TimeUnit.SECONDS
                )
        );
        // when
        JobSnapshot result = service.create(
                command(
                        attemptId,
                        "https://second",
                        "key"
                )
        );
        // then
        assertEquals(
                attemptId,
                result.attemptId()
        );
        assertEquals(
                1,
                engine.invocations.get()
        );
    }
    @Test
    @DisplayName("동일 요청 ID의 다른 파일은 충돌로 거부한다")
    void create_failure_changedObject() {
        // given
        UUID attemptId = UUID.randomUUID();
        service.create(
                command(
                        attemptId,
                        "https://first",
                        "key"
                )
        );
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> service.create(
                        command(
                                attemptId,
                                "https://first",
                                "other"
                        )
                )
        );
        // then
        assertEquals(
                "IDEMPOTENCY_CONFLICT",
                exception.getCode()
        );
    }
    @Test
    @DisplayName("활성 작업이 있으면 다른 요청을 대기시키지 않는다")
    void create_failure_busy() {
        // given
        service.create(
                command(
                        UUID.randomUUID(),
                        "https://first",
                        "key"
                )
        );
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> service.create(
                        command(
                                UUID.randomUUID(),
                                "https://first",
                                "key"
                        )
                )
        );
        // then
        assertEquals(
                "BUSY",
                exception.getCode()
        );
    }
    @Test
    @DisplayName("삭제된 결과의 유효한 중복 요청은 재실행하지 않는다")
    void create_success_deletedTombstone() {
        // given
        UUID attemptId = UUID.randomUUID();
        service.create(
                command(
                        attemptId,
                        "https://first",
                        "key"
                )
        );
        service.delete(attemptId);
        // when
        JobSnapshot result = service.create(
                command(
                        attemptId,
                        "https://second",
                        "key"
                )
        );
        // then
        assertEquals(
                JobSnapshot.Status.EXPIRED,
                result.status()
        );
        assertNull(result.result());
    }
    @Test
    @DisplayName("보존 기한이 지나면 결과를 제거하고 중복 방지 기록만 남긴다")
    void get_success_retentionExpired() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        service.create(
                command(
                        attemptId,
                        "https://first",
                        "key"
                )
        );
        engine.finish.countDown();
        waitForFinished(attemptId);
        clock.now = clock.now.plusSeconds(61);
        // when
        JobSnapshot result = service.get(attemptId);
        // then
        assertEquals(
                JobSnapshot.Status.EXPIRED,
                result.status()
        );
        assertNull(result.result());
    }
    @Test
    @DisplayName("정리 완료 전에 새 작업이 실행되지 않는다")
    void create_failure_cleanupStillActive() throws Exception {
        // given
        engine.cleanupBlock = true;
        UUID attemptId = UUID.randomUUID();
        service.create(
                command(
                        attemptId,
                        "https://first",
                        "key"
                )
        );
        engine.finish.countDown();
        assertTrue(
                engine.cleaning.await(
                        2,
                        TimeUnit.SECONDS
                )
        );
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> service.create(
                        command(
                                UUID.randomUUID(),
                                "https://second",
                                "key"
                        )
                )
        );
        // then
        assertEquals(
                "BUSY",
                exception.getCode()
        );
        engine.cleaned.countDown();
    }
    @Test
    @DisplayName("요청 만료 후에는 같은 ID도 수락하지 않는다")
    void create_failure_requestExpired() {
        // given
        CreateJobCommand command = command(
                UUID.randomUUID(),
                "https://first",
                "key"
        );
        clock.now = clock.now.plusSeconds(1801);
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> service.create(command)
        );
        // then
        assertEquals(
                "REQUEST_EXPIRED",
                exception.getCode()
        );
    }
    @Test
    @DisplayName("실제 동시 요청 가운데 하나만 실행 슬롯을 확보한다")
    void create_success_concurrentSingleSlot() throws Exception {
        // given
        CyclicBarrier barrier = new CyclicBarrier(3);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        Callable<String> action = () -> {
            barrier.await();
            try {
                service.create(
                        command(
                                UUID.randomUUID(),
                                "https://first",
                                "key"
                        )
                );
                return "accepted";
            } catch (JobException exception) {
                return exception.getCode();
            }
        };
        Future<String> first = callers.submit(action);
        Future<String> second = callers.submit(action);
        // when
        barrier.await();
        // then
        assertEquals(
                Set.of(
                        "accepted",
                        "BUSY"
                ),
                Set.of(
                        first.get(),
                        second.get()
                )
        );
        callers.shutdownNow();
    }
    @Test
    @DisplayName("프로세스 정리 실패 이후에는 readiness를 닫는다")
    void isReady_failure_cleanupFailed() throws Exception {
        // given
        engine.cleanupFailure = true;
        UUID attemptId = UUID.randomUUID();
        service.create(
                command(
                        attemptId,
                        "https://first",
                        "key"
                )
        );
        engine.finish.countDown();
        assertTrue(
                engine.cleaning.await(
                        2,
                        TimeUnit.SECONDS
                )
        );
        for (int index = 0; index < 200 && service.isReady(); index++) {
            Thread.sleep(10);
        }
        // when
        boolean ready = service.isReady();
        // then
        assertFalse(ready);
    }
    private CreateJobCommand command(
            UUID id,
            String url,
            String key
    ) {
        return new CreateJobCommand(
                id,
                "profile",
                Instant.parse("2026-10-08T00:30:00Z"),
                new CreateJobCommand.AudioInput(
                        "bucket",
                        key,
                        null,
                        "etag",
                        url,
                        Map.of(),
                        1,
                        "audio/webm"
                ),
                null
        );
    }
    private void waitForFinished(UUID id) throws Exception {
        for (int index = 0; index < 200; index++) {
            if (service.get(id)
                    .status() != JobSnapshot.Status.PROCESSING) {
                return;
            }
            Thread.sleep(10);
        }
        fail("작업이 완료되지 않았습니다");
    }
    private static class FakeEngine implements TranscriptionEngine {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finish = new CountDownLatch(1);
        private final CountDownLatch cleaning = new CountDownLatch(1);
        private final CountDownLatch cleaned = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private boolean cleanupBlock;
        private boolean cleanupFailure;
        public String getProfileId() {
            return "profile";
        }
        public boolean isReady() {
            return true;
        }
        public TranscriptResult transcribe(CreateJobCommand command) throws InterruptedException {
            invocations.incrementAndGet();
            started.countDown();
            finish.await();
            return new TranscriptResult(
                    1,
                    "{\"transcription\":[]}",
                    List.of(
                            new TranscriptResult.Utterance(
                                    0,
                                    "화자 1",
                                    0,
                                    1,
                                    "안녕"
                            )
                    )
            );
        }
        public void cleanup(UUID id) throws InterruptedException {
            cleaning.countDown();
            if (cleanupFailure) {
                throw new JobException(
                        "PROCESS_CLEANUP_FAILED",
                        503
                );
            }
            if (cleanupBlock) {
                cleaned.await();
            }
        }
    }
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T00:00:00Z");
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        public Clock withZone(ZoneId zone) {
            return this;
        }
        public Instant instant() {
            return now;
        }
    }
}
