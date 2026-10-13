package com.knot.ai.transcription.domain;

import java.util.List;

public record TranscriptResult(
        int schemaVersion,
        String rawWhisperJson,
        List<Utterance> utterances
) {
    private static final int SCHEMA_VERSION = 1;

    public TranscriptResult {
        if (schemaVersion != SCHEMA_VERSION || rawWhisperJson == null || rawWhisperJson.isBlank() || utterances == null
                || utterances.isEmpty()) {
            throw new JobException(JobErrorCode.INVALID_RESULT);
        }
        double previousStart = -1;
        for (int index = 0; index < utterances.size(); index++) {
            Utterance utterance = utterances.get(index);
            if (utterance == null || utterance.ord() != index || utterance.startSec() < previousStart) {
                throw new JobException(JobErrorCode.INVALID_RESULT);
            }
            previousStart = utterance.startSec();
        }
        utterances = List.copyOf(utterances);
    }

    public record Utterance(
            int ord,
            String speakerLabel,
            double startSec,
            double endSec,
            String text
    ) {
        public Utterance {
            if (ord < 0 || speakerLabel == null || speakerLabel.isBlank() || text == null || text.isBlank()
                    || !Double.isFinite(startSec) || !Double.isFinite(endSec) || startSec < 0 || endSec < startSec) {
                throw new JobException(JobErrorCode.INVALID_RESULT);
            }
        }
    }
}
