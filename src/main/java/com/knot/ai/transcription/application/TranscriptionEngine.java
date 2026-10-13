package com.knot.ai.transcription.application;

import com.knot.ai.transcription.application.dto.command.CreateJobCommand;
import com.knot.ai.transcription.domain.TranscriptResult;

public interface TranscriptionEngine {
    String getProfileId();
    boolean isReady();
    TranscriptResult transcribe(CreateJobCommand command) throws Exception;
    void cleanup(java.util.UUID attemptId) throws Exception;
}
