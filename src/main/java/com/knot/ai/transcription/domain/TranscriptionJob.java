package com.knot.ai.transcription.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class TranscriptionJob {
    private final UUID attemptId;
    private final JobIdentity identity;
    private final Instant requestValidUntil;
    private JobStatus status = JobStatus.PROCESSING;
    private JobErrorCode failureCode;
    private TranscriptResult result;
    private Instant finishedAt;

    private TranscriptionJob(
            UUID attemptId,
            JobIdentity identity,
            Instant requestValidUntil
    ) {
        this.attemptId = Objects.requireNonNull(attemptId);
        this.identity = Objects.requireNonNull(identity);
        this.requestValidUntil = Objects.requireNonNull(requestValidUntil);
    }

    public static TranscriptionJob create(
            UUID attemptId,
            JobIdentity identity,
            Instant requestValidUntil
    ) {
        return new TranscriptionJob(
                attemptId,
                identity,
                requestValidUntil
        );
    }

    public void validateSameIdentity(JobIdentity requestedIdentity) {
        if (!identity.equals(requestedIdentity)) {
            throw new JobException(JobErrorCode.IDEMPOTENCY_CONFLICT);
        }
    }

    public void succeed(
            TranscriptResult transcriptResult,
            Instant now
    ) {
        Objects.requireNonNull(transcriptResult);
        Objects.requireNonNull(now);
        if (status != JobStatus.PROCESSING) {
            return;
        }
        result = transcriptResult;
        status = JobStatus.SUCCEEDED;
        finishedAt = now;
    }

    public void fail(
            JobErrorCode errorCode,
            Instant now
    ) {
        Objects.requireNonNull(errorCode);
        Objects.requireNonNull(now);
        if (status != JobStatus.PROCESSING) {
            return;
        }
        status = JobStatus.FAILED;
        failureCode = errorCode;
        finishedAt = now;
    }

    public void expire() {
        result = null;
        status = JobStatus.EXPIRED;
    }

    public void expireResult(
            Instant now,
            Duration retention
    ) {
        if (finishedAt != null && !now.isBefore(finishedAt.plus(retention))) {
            expire();
        }
    }

    public boolean isRemovable(Instant now) {
        return status != JobStatus.PROCESSING && !now.isBefore(requestValidUntil) && status != JobStatus.SUCCEEDED;
    }

    public boolean hasRetainedResult() {
        return result != null;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode == null ? null : failureCode.getCode();
    }

    public TranscriptResult getResult() {
        return result;
    }
}
