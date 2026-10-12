package com.knot.ai.transcription.domain;
import java.util.List;
public record TranscriptResult(
        int schemaVersion,
        String rawWhisperJson,
        List<Utterance> utterances
) {
    public TranscriptResult {
        if (schemaVersion != 1 || rawWhisperJson == null || rawWhisperJson.isBlank() || utterances == null
                || utterances.isEmpty()) {
            throw new JobException(
                    "INVALID_RESULT",
                    500
            );
        }
        utterances = List.copyOf(utterances);
        double previousStart = -1;
        for (int index = 0; index < utterances.size(); index++) {
            Utterance utterance = utterances.get(index);
            if (utterance.ord() != index || utterance.speakerLabel() == null || utterance.speakerLabel()
                    .isBlank() || utterance.text() == null || utterance.text()
                            .isBlank()
                    || !Double.isFinite(utterance.startSec()) || !Double.isFinite(utterance.endSec())
                    || utterance.startSec() < 0 || utterance.endSec() < utterance.startSec()
                    || utterance.startSec() < previousStart) {
                throw new JobException(
                        "INVALID_RESULT",
                        500
                );
            }
            previousStart = utterance.startSec();
        }
    }
    public record Utterance(
            int ord,
            String speakerLabel,
            double startSec,
            double endSec,
            String text
    ) {
    }
}
