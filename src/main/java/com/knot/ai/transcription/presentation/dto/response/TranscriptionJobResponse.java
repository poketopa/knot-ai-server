package com.knot.ai.transcription.presentation.dto.response;

import com.knot.ai.transcription.application.dto.result.JobSnapshot;
import com.knot.ai.transcription.domain.JobStatus;
import java.util.UUID;

public record TranscriptionJobResponse(
        UUID attemptId,
        JobStatus status,
        String failureCode,
        TranscriptResultResponse result
) {
    public static TranscriptionJobResponse from(JobSnapshot snapshot) {
        return new TranscriptionJobResponse(
                snapshot.attemptId(),
                snapshot.status(),
                snapshot.failureCode(),
                TranscriptResultResponse.from(snapshot.result())
        );
    }
}
