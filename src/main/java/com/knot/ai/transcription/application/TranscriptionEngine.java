package com.knot.ai.transcription.application;

import com.knot.ai.transcription.domain.TranscriptResult;
import java.util.UUID;

public interface TranscriptionEngine {
    boolean isReady();
    TranscriptResult transcribe(TranscriptionRequest request) throws Exception;
    void cleanup(UUID attemptId) throws Exception;
}
