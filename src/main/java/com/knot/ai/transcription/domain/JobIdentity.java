package com.knot.ai.transcription.domain;

public record JobIdentity(
        String bucket,
        String key,
        String versionId,
        String etag,
        Integer speakerCount,
        String profileId
) {
}
