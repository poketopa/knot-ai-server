package com.knot.ai.transcription.application;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.domain.JobException;
import com.knot.ai.transcription.domain.TranscriptResult;
import jakarta.annotation.PreDestroy;
@Service
public class TranscriptionJobService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(TranscriptionJobService.class);

    private final TranscriptionEngine engine;
    private final AiServerProperties properties;
    private final Clock clock;
    private final Map<UUID, Job> jobs = new HashMap<>();
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();
    private UUID activeId;
    private boolean cleanupFailed;

    @org.springframework.beans.factory.annotation.Autowired
    public TranscriptionJobService(
            TranscriptionEngine engine,
            AiServerProperties properties
    ) {
        this(
                engine,
                properties,
                Clock.systemUTC()
        );
    }
    TranscriptionJobService(
            TranscriptionEngine engine,
            AiServerProperties properties,
            Clock clock
    ) {
        this.engine = engine;
        this.properties = properties;
        this.clock = clock;
        janitor.scheduleWithFixedDelay(
                this::expireSafely,
                1,
                1,
                TimeUnit.SECONDS
        );
    }
    public synchronized JobSnapshot create(CreateJobCommand command) {
        expireRecords();
        Instant now = clock.instant();
        if (!command.requestValidUntil()
                .isAfter(now) || command.requestValidUntil()
                        .isAfter(now.plus(properties.requestValidityLimit()))) {
            throw new JobException(
                    "REQUEST_EXPIRED",
                    400
            );
        }
        if (!engine.getProfileId()
                .equals(command.profileId())) {
            throw new JobException(
                    "PROFILE_MISMATCH",
                    409
            );
        }
        Job existing = jobs.get(command.attemptId());
        if (existing != null) {
            if (!existing.command.fingerprint()
                    .equals(command.fingerprint())) {
                throw new JobException(
                        "IDEMPOTENCY_CONFLICT",
                        409
                );
            }
            return existing.snapshot();
        }
        if (activeId != null || jobs.size() >= properties.maximumRecords() || jobs.values()
                .stream()
                .anyMatch(job -> job.result != null)) {
            throw new JobException(
                    "BUSY",
                    409
            );
        }
        if (cleanupFailed || !engine.isReady()) {
            throw new JobException(
                    "NOT_READY",
                    503
            );
        }
        Job job = new Job(command);
        jobs.put(
                command.attemptId(),
                job
        );
        activeId = command.attemptId();
        job.worker = Thread.ofPlatform()
                .name("transcription-job")
                .unstarted(() -> execute(job));
        try {
            job.worker.start();
        } catch (RuntimeException exception) {
            job.fail(
                    "EXECUTOR_REJECTED",
                    now
            );
            activeId = null;
        }
        return job.snapshot();
    }
    public synchronized JobSnapshot get(UUID attemptId) {
        expireRecords();
        return requireJob(attemptId).snapshot();
    }
    public synchronized void delete(UUID attemptId) {
        Job job = requireJob(attemptId);
        if (job.status == JobSnapshot.Status.PROCESSING) {
            job.fail(
                    "CANCELED",
                    clock.instant()
            );
            job.worker.interrupt();
        }
        job.result = null;
        job.status = JobSnapshot.Status.EXPIRED;
    }
    public boolean isReady() {
        synchronized (this) {
            return !cleanupFailed && engine.isReady();
        }
    }
    public String getProfileId() {
        return engine.getProfileId();
    }
    @PreDestroy
    public void close() {
        janitor.shutdownNow();
        Thread worker;
        synchronized (this) {
            worker = activeId == null ? null : jobs.get(activeId).worker;
            if (worker != null) {
                worker.interrupt();
            }
        }
        if (worker != null) {
            try {
                worker.join(15_000);
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
            }
        }
    }
    private void execute(Job job) {
        try {
            TranscriptResult result = engine.transcribe(job.command);
            synchronized (this) {
                if (job.status == JobSnapshot.Status.PROCESSING) {
                    job.result = result;
                    job.status = JobSnapshot.Status.SUCCEEDED;
                    job.finishedAt = clock.instant();
                }
            }
        } catch (Exception exception) {
            synchronized (this) {
                if (job.status == JobSnapshot.Status.PROCESSING) {
                    String code = exception instanceof JobException failure ? failure.getCode() : "INFERENCE_FAILED";
                    job.fail(
                            code,
                            clock.instant()
                    );
                }
            }
        } finally {
            // Cleanup completes before releasing the only inference slot.
            Thread.interrupted();
            try {
                engine.cleanup(job.command.attemptId());
            } catch (Exception exception) {
                synchronized (this) {
                    cleanupFailed = true;
                    LOGGER.warn(
                            "전사 작업 정리 실패 attemptId={} stage=CLEANUP code=PROCESS_CLEANUP_FAILED",
                            job.command.attemptId()
                    );
                }
            }
            synchronized (this) {
                activeId = null;
            }
        }
    }
    private Job requireJob(UUID attemptId) {
        Job job = jobs.get(attemptId);
        if (job == null) {
            throw new JobException(
                    "JOB_NOT_FOUND",
                    404
            );
        }
        return job;
    }
    private void expireSafely() {
        synchronized (this) {
            expireRecords();
        }
    }
    private void expireRecords() {
        Instant now = clock.instant();
        for (Job job : jobs.values()) {
            if (job.finishedAt != null && !now.isBefore(job.finishedAt.plus(properties.resultRetention()))) {
                job.result = null;
                job.status = JobSnapshot.Status.EXPIRED;
            }
        }
        jobs.values()
                .removeIf(
                        job -> job.status != JobSnapshot.Status.PROCESSING && !job.command.attemptId()
                                .equals(activeId) && !now.isBefore(job.command.requestValidUntil())
                                && (job.status != JobSnapshot.Status.SUCCEEDED)
                );
    }
    private static class Job {
        private final CreateJobCommand command;
        private JobSnapshot.Status status = JobSnapshot.Status.PROCESSING;
        private String failureCode;
        private TranscriptResult result;
        private Instant finishedAt;
        private Thread worker;
        private Job(CreateJobCommand command) {
            this.command = command;
        }
        private void fail(
                String code,
                Instant now
        ) {
            status = JobSnapshot.Status.FAILED;
            failureCode = code;
            finishedAt = now;
        }
        private JobSnapshot snapshot() {
            return new JobSnapshot(
                    command.attemptId(),
                    status,
                    failureCode,
                    result
            );
        }
    }
}
