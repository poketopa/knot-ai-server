package com.knot.ai.transcription.presentation.dto.response;

import com.knot.ai.transcription.domain.TranscriptResult;
import java.util.List;

public record TranscriptResultResponse(
        int schemaVersion,
        String rawWhisperJson,
        List<UtteranceResponse> utterances
) {
    public static TranscriptResultResponse from(TranscriptResult result) {
        if (result == null) {
            return null;
        }
        return new TranscriptResultResponse(
                result.schemaVersion(),
                result.rawWhisperJson(),
                result.utterances()
                        .stream()
                        .map(UtteranceResponse::from)
                        .toList()
        );
    }

    public record UtteranceResponse(
            int ord,
            String speakerLabel,
            double startSec,
            double endSec,
            String text
    ) {
        private static UtteranceResponse from(TranscriptResult.Utterance utterance) {
            return new UtteranceResponse(
                    utterance.ord(),
                    utterance.speakerLabel(),
                    utterance.startSec(),
                    utterance.endSec(),
                    utterance.text()
            );
        }
    }
}
