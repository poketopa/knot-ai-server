package com.knot.ai.transcription.application;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
public record CreateJobCommand(
        UUID attemptId,
        String profileId,
        Instant requestValidUntil,
        AudioInput input,
        Integer speakerCount
) {
    public record AudioInput(
            String bucket,
            String key,
            String versionId,
            String etag,
            String downloadUrl,
            Map<String, String> requiredHeaders,
            long contentLength,
            String contentType
    ) {
        public AudioInput {
            requiredHeaders = requiredHeaders == null ? Map.of() : Map.copyOf(requiredHeaders);
        }
    }
    public Fingerprint fingerprint() {
        return new Fingerprint(
                input.bucket(),
                input.key(),
                input.versionId(),
                input.etag(),
                speakerCount,
                profileId
        );
    }
    public record Fingerprint(
            String bucket,
            String key,
            String versionId,
            String etag,
            Integer speakerCount,
            String profileId
    ) {
    }
}
