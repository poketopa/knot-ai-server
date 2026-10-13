package com.knot.ai.transcription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.knot.ai.transcription.domain.JobErrorCode;
import com.knot.ai.transcription.domain.JobException;
import com.knot.ai.transcription.domain.TranscriptResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TranscriptionJobServiceTest {
    private final FakeEngine engine = new FakeEngine();
    private TranscriptionJobService service;

    @BeforeEach
    void prepare() {
        service = new TranscriptionJobService(engine);
    }

    @AfterEach
    void close() {
        engine.cleaned.countDown();
        service.close();
    }

    @Test
    @DisplayName("현재 요청 ID의 새 URL은 모델을 다시 실행하지 않는다")
    void create_success_currentAttempt() throws Exception {
        // given
        UUID id = UUID.randomUUID();
        service.create(request(id));
        assertTrue(
                engine.started.await(
                        2,
                        TimeUnit.SECONDS
                )
        );

        // when
        JobSnapshot snapshot = service.create(
                new TranscriptionRequest(
                        id,
                        "https://renewed",
                        2
                )
        );

        // then
        assertEquals(
                id,
                snapshot.attemptId()
        );
        assertEquals(
                JobSnapshot.Status.PROCESSING,
                snapshot.status()
        );
        assertEquals(
                1,
                engine.invocations.get()
        );
    }

    @Test
    @DisplayName("활성 작업이 있으면 다른 요청을 대기시키지 않는다")
    void create_failure_busy() {
        // given
        service.create(request(UUID.randomUUID()));

        // when
        JobException failure = assertThrows(
                JobException.class,
                () -> service.create(request(UUID.randomUUID()))
        );

        // then
        assertEquals(
                "BUSY",
                failure.getCode()
        );
    }

    @Test
    @DisplayName("동시 요청 중 하나만 모델 실행 슬롯을 얻는다")
    void create_success_singleConcurrentRequest() throws Exception {
        // given
        CyclicBarrier barrier = new CyclicBarrier(3);
        Callable<String> action = () -> {
            barrier.await();
            try {
                service.create(request(UUID.randomUUID()));
                return "accepted";
            } catch (JobException exception) {
                return exception.getCode();
            }
        };
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
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
        }
    }

    @Test
    @DisplayName("정리가 끝날 때까지 완료 상태와 다음 실행 슬롯을 공개하지 않는다")
    void create_failure_cleanupStillActive() throws Exception {
        // given
        engine.cleanupBlock = true;
        UUID id = UUID.randomUUID();
        service.create(request(id));
        engine.finish.countDown();
        assertTrue(
                engine.cleaning.await(
                        2,
                        TimeUnit.SECONDS
                )
        );

        // when
        JobException failure = assertThrows(
                JobException.class,
                () -> service.create(request(UUID.randomUUID()))
        );

        // then
        assertEquals(
                "BUSY",
                failure.getCode()
        );
        assertEquals(
                JobSnapshot.Status.PROCESSING,
                service.get(id)
                        .status()
        );
        engine.cleaned.countDown();
        assertEquals(
                JobSnapshot.Status.SUCCEEDED,
                awaitFinished(id).status()
        );
    }

    @Test
    @DisplayName("완료된 현재 ID를 다시 요청하면 보관한 결과를 반환한다")
    void create_success_completedAttempt() throws Exception {
        // given
        UUID id = UUID.randomUUID();
        service.create(request(id));
        engine.finish.countDown();
        JobSnapshot completed = awaitFinished(id);

        // when
        JobSnapshot duplicate = service.create(request(id));

        // then
        assertSame(
                completed,
                duplicate
        );
        assertEquals(
                1,
                engine.invocations.get()
        );
    }

    @Test
    @DisplayName("새 작업은 마지막 결과를 교체하고 이전 ID는 조회할 수 없다")
    void create_success_replaceCompletedAttempt() throws Exception {
        // given
        UUID firstId = UUID.randomUUID();
        service.create(request(firstId));
        engine.finish.countDown();
        awaitFinished(firstId);
        engine.finish = new CountDownLatch(1);
        UUID nextId = UUID.randomUUID();

        // when
        JobSnapshot next = service.create(request(nextId));

        // then
        assertEquals(
                nextId,
                next.attemptId()
        );
        assertNull(next.result());
        assertEquals(
                "JOB_NOT_FOUND",
                assertThrows(
                        JobException.class,
                        () -> service.get(firstId)
                ).getCode()
        );
    }

    @Test
    @DisplayName("모델 준비가 안 되면 새 작업을 받지 않는다")
    void create_failure_engineNotReady() {
        // given
        engine.ready = false;

        // when
        JobException failure = assertThrows(
                JobException.class,
                () -> service.create(request(UUID.randomUUID()))
        );

        // then
        assertEquals(
                "NOT_READY",
                failure.getCode()
        );
        assertFalse(service.isReady());
    }

    @Test
    @DisplayName("정리 실패 후에는 결과를 보관하되 새 실행을 차단한다")
    void create_failure_cleanupFailed() throws Exception {
        // given
        engine.cleanupFailure = true;
        UUID id = UUID.randomUUID();
        service.create(request(id));
        engine.finish.countDown();
        JobSnapshot completed = awaitFinished(id);

        // when
        JobException failure = assertThrows(
                JobException.class,
                () -> service.create(request(UUID.randomUUID()))
        );

        // then
        assertEquals(
                "NOT_READY",
                failure.getCode()
        );
        assertFalse(service.isReady());
        assertEquals(
                JobSnapshot.Status.SUCCEEDED,
                completed.status()
        );
    }

    @Test
    @DisplayName("실행 중인 작업이 없어도 종료 후에는 새 작업을 받지 않는다")
    void create_failure_closedWhileIdle() {
        // given
        service.close();

        // when
        JobException failure = assertThrows(
                JobException.class,
                () -> service.create(request(UUID.randomUUID()))
        );

        // then
        assertEquals(
                "NOT_READY",
                failure.getCode()
        );
        assertFalse(service.isReady());
    }

    @Test
    @DisplayName("종료는 진행 중인 모델을 중단하고 정리까지 기다린다")
    void close_success_interruptAndCleanup() throws Exception {
        // given
        UUID id = UUID.randomUUID();
        service.create(request(id));
        assertTrue(
                engine.started.await(
                        2,
                        TimeUnit.SECONDS
                )
        );

        // when
        service.close();

        // then
        assertEquals(
                0L,
                engine.cleaning.getCount()
        );
        assertEquals(
                JobSnapshot.Status.FAILED,
                service.get(id)
                        .status()
        );
        assertFalse(service.isReady());
    }

    @Test
    @DisplayName("새 서비스는 이전 메모리 작업을 복구하지 않는다")
    void get_failure_newServiceHasNoHistory() throws Exception {
        // given
        UUID id = UUID.randomUUID();
        service.create(request(id));
        engine.finish.countDown();
        awaitFinished(id);
        TranscriptionJobService restarted = new TranscriptionJobService(engine);
        try {
            // when
            JobException failure = assertThrows(
                    JobException.class,
                    () -> restarted.get(id)
            );

            // then
            assertEquals(
                    "JOB_NOT_FOUND",
                    failure.getCode()
            );
        } finally {
            restarted.close();
        }
    }

    @Test
    @DisplayName("모델의 알려진 오류 코드를 결과에 보존한다")
    void get_failure_modelDiagnostic() throws Exception {
        // given
        engine.failure = new JobException(JobErrorCode.INFERENCE_TIMEOUT);
        UUID id = UUID.randomUUID();
        service.create(request(id));

        // when
        engine.finish.countDown();

        // then
        JobSnapshot failed = awaitFinished(id);
        assertEquals(
                JobSnapshot.Status.FAILED,
                failed.status()
        );
        assertEquals(
                "INFERENCE_TIMEOUT",
                failed.failureCode()
        );
        assertNull(failed.result());
    }

    @Test
    @DisplayName("알 수 없는 모델 예외는 내부 메시지를 공개하지 않는다")
    void get_failure_unexpectedDiagnostic() throws Exception {
        // given
        engine.failure = new IllegalStateException("secret URL");
        UUID id = UUID.randomUUID();
        service.create(request(id));

        // when
        engine.finish.countDown();

        // then
        assertEquals(
                "INFERENCE_FAILED",
                awaitFinished(id).failureCode()
        );
    }

    private TranscriptionRequest request(UUID id) {
        return new TranscriptionRequest(
                id,
                "https://audio",
                null
        );
    }

    private JobSnapshot awaitFinished(UUID id) throws InterruptedException {
        for (int index = 0; index < 200; index++) {
            JobSnapshot snapshot = service.get(id);
            if (snapshot.status() != JobSnapshot.Status.PROCESSING) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        return fail("작업이 완료되지 않았습니다");
    }

    private static class FakeEngine implements TranscriptionEngine {
        private final CountDownLatch started = new CountDownLatch(1);
        private CountDownLatch finish = new CountDownLatch(1);
        private final CountDownLatch cleaning = new CountDownLatch(1);
        private final CountDownLatch cleaned = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private boolean ready = true;
        private boolean cleanupBlock;
        private boolean cleanupFailure;
        private RuntimeException failure;

        public boolean isReady() {
            return ready;
        }

        public TranscriptResult transcribe(TranscriptionRequest request) throws InterruptedException {
            invocations.incrementAndGet();
            started.countDown();
            finish.await();
            if (failure != null) {
                throw failure;
            }
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
                throw new JobException(JobErrorCode.PROCESS_CLEANUP_FAILED);
            }
            if (cleanupBlock) {
                cleaned.await();
            }
        }
    }
}
