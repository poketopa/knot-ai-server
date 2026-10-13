package com.knot.ai.transcription.application.dto.result;

import com.knot.ai.transcription.domain.JobStatus;
import com.knot.ai.transcription.domain.TranscriptResult;
import java.util.UUID;

public record JobSnapshot(
        UUID attemptId,
        JobStatus status,
        String failureCode,
        TranscriptResult result
) {
}
