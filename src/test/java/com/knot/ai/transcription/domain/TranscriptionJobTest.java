package com.knot.ai.transcription.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TranscriptionJobTest {

    @Test
    @DisplayName("같은 작업 ID의 다른 입력 identity는 멱등성 충돌로 거부한다")
    void validateSameIdentity_failure_changedIdentity() {
        // given
        TranscriptionJob job = TranscriptionJob.create(
                UUID.randomUUID(),
                identity("key"),
                Instant.parse("2026-10-08T00:30:00Z")
        );

        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> job.validateSameIdentity(identity("other"))
        );

        // then
        assertEquals(
                JobErrorCode.IDEMPOTENCY_CONFLICT,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("작업 identity가 없으면 작업을 생성하지 않는다")
    void create_failure_nullIdentity() {
        // when
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> TranscriptionJob.create(
                        UUID.randomUUID(),
                        null,
                        Instant.parse("2026-10-08T00:30:00Z")
                )
        );

        // then
        assertNotNull(exception);
    }

    @Test
    @DisplayName("성공 결과의 보존 기한이 지나면 결과를 제거하고 만료 상태로 바꾼다")
    void expireResult_success_retentionExpired() {
        // given
        TranscriptionJob job = TranscriptionJob.create(
                UUID.randomUUID(),
                identity("key"),
                Instant.parse("2026-10-08T00:30:00Z")
        );
        job.succeed(
                transcriptResult(),
                Instant.parse("2026-10-08T00:01:00Z")
        );

        // when
        job.expireResult(
                Instant.parse("2026-10-08T00:02:00Z"),
                Duration.ofSeconds(60)
        );

        // then
        assertEquals(
                JobStatus.EXPIRED,
                job.getStatus()
        );
        assertNull(job.getResult());
    }

    @Test
    @DisplayName("취소로 만료된 작업에는 늦게 도착한 성공 결과를 반영하지 않는다")
    void succeed_failure_alreadyExpired() {
        // given
        TranscriptionJob job = TranscriptionJob.create(
                UUID.randomUUID(),
                identity("key"),
                Instant.parse("2026-10-08T00:30:00Z")
        );
        job.expire();

        // when
        job.succeed(
                transcriptResult(),
                Instant.parse("2026-10-08T00:01:00Z")
        );

        // then
        assertEquals(
                JobStatus.EXPIRED,
                job.getStatus()
        );
        assertNull(job.getResult());
    }

    @Test
    @DisplayName("요청 유효 시간이 지나면 실패 tombstone은 제거 대상이 된다")
    void isRemovable_success_failedTombstoneExpired() {
        // given
        TranscriptionJob job = TranscriptionJob.create(
                UUID.randomUUID(),
                identity("key"),
                Instant.parse("2026-10-08T00:30:00Z")
        );
        job.fail(
                JobErrorCode.INFERENCE_FAILED,
                Instant.parse("2026-10-08T00:01:00Z")
        );

        // when
        boolean removable = job.isRemovable(Instant.parse("2026-10-08T00:30:00Z"));

        // then
        assertTrue(removable);
    }

    private JobIdentity identity(String key) {
        return new JobIdentity(
                "bucket",
                key,
                null,
                "etag",
                null,
                "profile"
        );
    }

    private TranscriptResult transcriptResult() {
        return new TranscriptResult(
                1,
                "{\"transcription\":[]}",
                List.of(
                        new TranscriptResult.Utterance(
                                0,
                                "화자 1",
                                0,
                                1,
                                "안녕"
                        )
                )
        );
    }
}
