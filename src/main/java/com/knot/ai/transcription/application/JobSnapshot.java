package com.knot.ai.transcription.application;
import java.util.UUID;
import com.knot.ai.transcription.domain.TranscriptResult;
public record JobSnapshot(
        UUID attemptId,
        Status status,
        String failureCode,
        TranscriptResult result
) {
    public enum Status {
        PROCESSING,
        SUCCEEDED,
        FAILED,
        EXPIRED
    }
}
