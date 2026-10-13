package com.knot.ai.transcription.application;

import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.dto.command.CreateJobCommand;
import com.knot.ai.transcription.application.dto.result.JobSnapshot;
import com.knot.ai.transcription.domain.JobErrorCode;
import com.knot.ai.transcription.domain.JobException;
import com.knot.ai.transcription.domain.JobStatus;
import com.knot.ai.transcription.domain.TranscriptResult;
import com.knot.ai.transcription.domain.TranscriptionJob;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TranscriptionJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionJobService.class);

    private final TranscriptionEngine engine;
    private final AiServerProperties properties;
    private final Clock clock;
    private final Map<UUID, RunningJob> jobs = new HashMap<>();
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();
    private UUID activeId;
    private boolean cleanupFailed;

    @Autowired
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
            throw new JobException(JobErrorCode.REQUEST_EXPIRED);
        }
        if (!engine.getProfileId()
                .equals(command.profileId())) {
            throw new JobException(JobErrorCode.PROFILE_MISMATCH);
        }
        RunningJob existing = jobs.get(command.attemptId());
        if (existing != null) {
            existing.job.validateSameIdentity(command.identity());
            return existing.snapshot();
        }
        if (activeId != null || jobs.size() >= properties.maximumRecords() || jobs.values()
                .stream()
                .anyMatch(job -> job.job.hasRetainedResult())) {
            throw new JobException(JobErrorCode.BUSY);
        }
        if (cleanupFailed || !engine.isReady()) {
            throw new JobException(JobErrorCode.NOT_READY);
        }
        RunningJob job = RunningJob.create(command);
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
            job.job.fail(
                    JobErrorCode.EXECUTOR_REJECTED,
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
        RunningJob job = requireJob(attemptId);
        if (job.job.getStatus() == JobStatus.PROCESSING) {
            job.job.fail(
                    JobErrorCode.CANCELED,
                    clock.instant()
            );
            job.worker.interrupt();
        }
        job.job.expire();
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

    private void execute(RunningJob job) {
        try {
            TranscriptResult result = engine.transcribe(job.command);
            synchronized (this) {
                job.job.succeed(
                        result,
                        clock.instant()
                );
            }
        } catch (Exception exception) {
            synchronized (this) {
                if (exception instanceof JobException failure) {
                    job.job.fail(
                            failure.getErrorCode(),
                            clock.instant()
                    );
                    return;
                }
                job.job.fail(
                        JobErrorCode.INFERENCE_FAILED,
                        clock.instant()
                );
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

    private RunningJob requireJob(UUID attemptId) {
        RunningJob job = jobs.get(attemptId);
        if (job == null) {
            throw new JobException(JobErrorCode.JOB_NOT_FOUND);
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
        for (RunningJob job : jobs.values()) {
            job.job.expireResult(
                    now,
                    properties.resultRetention()
            );
        }
        jobs.values()
                .removeIf(
                        job -> !job.command.attemptId()
                                .equals(activeId) && job.job.isRemovable(now)
                );
    }

    private static class RunningJob {
        private final CreateJobCommand command;
        private final TranscriptionJob job;
        private Thread worker;

        private RunningJob(
                CreateJobCommand command,
                TranscriptionJob job
        ) {
            this.command = command;
            this.job = job;
        }

        private static RunningJob create(CreateJobCommand command) {
            return new RunningJob(
                    command,
                    TranscriptionJob.create(
                            command.attemptId(),
                            command.identity(),
                            command.requestValidUntil()
                    )
            );
        }

        private JobSnapshot snapshot() {
            return new JobSnapshot(
                    command.attemptId(),
                    job.getStatus(),
                    job.getFailureCode(),
                    job.getResult()
            );
        }
    }
}
