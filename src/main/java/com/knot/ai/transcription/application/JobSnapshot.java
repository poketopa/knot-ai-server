package com.knot.ai.transcription.application;

import com.knot.ai.transcription.domain.TranscriptResult;
import java.util.UUID;

public record JobSnapshot(
        UUID attemptId,
        Status status,
        String failureCode,
        TranscriptResult result
) {
    public enum Status {
        PROCESSING,
        SUCCEEDED,
        FAILED
    }
}
