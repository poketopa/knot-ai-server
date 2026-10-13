package com.knot.ai.transcription.application;

import com.knot.ai.transcription.domain.JobErrorCode;
import com.knot.ai.transcription.domain.JobException;
import com.knot.ai.transcription.domain.TranscriptResult;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TranscriptionJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionJobService.class);
    private static final long SHUTDOWN_WAIT_MILLIS = 15_000;

    private final TranscriptionEngine engine;
    private JobSnapshot current;
    private Thread worker;
    private boolean accepting = true;

    public TranscriptionJobService(TranscriptionEngine engine) {
        this.engine = engine;
    }

    public synchronized JobSnapshot create(TranscriptionRequest request) {
        if (!accepting) {
            throw new JobException(JobErrorCode.NOT_READY);
        }
        if (current != null && current.attemptId()
                .equals(request.attemptId())) {
            return current;
        }
        if (worker != null) {
            throw new JobException(JobErrorCode.BUSY);
        }
        if (!engine.isReady()) {
            throw new JobException(JobErrorCode.NOT_READY);
        }
        current = new JobSnapshot(
                request.attemptId(),
                JobSnapshot.Status.PROCESSING,
                null,
                null
        );
        worker = Thread.ofPlatform()
                .name("transcription-job")
                .unstarted(() -> execute(request));
        try {
            worker.start();
        } catch (RuntimeException exception) {
            current = new JobSnapshot(
                    request.attemptId(),
                    JobSnapshot.Status.FAILED,
                    JobErrorCode.EXECUTOR_REJECTED.getCode(),
                    null
            );
            worker = null;
        }
        return current;
    }

    public synchronized JobSnapshot get(UUID attemptId) {
        if (current == null || !current.attemptId()
                .equals(attemptId)) {
            throw new JobException(JobErrorCode.JOB_NOT_FOUND);
        }
        return current;
    }

    public synchronized boolean isReady() {
        return accepting && engine.isReady();
    }

    @PreDestroy
    public void close() {
        Thread running;
        synchronized (this) {
            accepting = false;
            running = worker;
            if (running != null) {
                running.interrupt();
            }
        }
        if (running != null) {
            try {
                running.join(SHUTDOWN_WAIT_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
            }
        }
    }

    private void execute(TranscriptionRequest request) {
        JobSnapshot outcome = new JobSnapshot(
                request.attemptId(),
                JobSnapshot.Status.FAILED,
                JobErrorCode.INFERENCE_FAILED.getCode(),
                null
        );
        try {
            TranscriptResult result = Objects.requireNonNull(engine.transcribe(request));
            outcome = new JobSnapshot(
                    request.attemptId(),
                    JobSnapshot.Status.SUCCEEDED,
                    null,
                    result
            );
        } catch (Exception exception) {
            if (exception instanceof JobException failure) {
                outcome = new JobSnapshot(
                        request.attemptId(),
                        JobSnapshot.Status.FAILED,
                        failure.getCode(),
                        null
                );
            }
        } finally {
            Thread.interrupted();
            try {
                engine.cleanup(request.attemptId());
            } catch (Exception exception) {
                synchronized (this) {
                    accepting = false;
                }
                LOGGER.warn(
                        "전사 작업 정리 실패 attemptId={} code=PROCESS_CLEANUP_FAILED",
                        request.attemptId()
                );
            }
            synchronized (this) {
                current = outcome;
                worker = null;
            }
        }
    }
}
