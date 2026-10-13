package com.knot.ai.global;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("ai")
@Validated
public record AiServerProperties(
        @NotBlank(message = "앱 인증 토큰은 필수입니다") String token,
        @NotNull(message = "작업 경로는 필수입니다") Path workDirectory,
        @NotNull(message = "실행 프로필은 필수입니다") Path profileFile,
        @NotNull(message = "실행 경로는 필수입니다") Path desktopDirectory,
        @NotEmpty(message = "S3 호스트는 필수입니다") Set<String> allowedS3Hosts,
        @NotBlank(message = "S3 버킷은 필수입니다") String bucket,
        @NotBlank(message = "S3 접두사는 필수입니다") String keyPrefix,
        @NotNull(message = "연결 제한 시간은 필수입니다") Duration connectTimeout,
        @NotNull(message = "읽기 제한 시간은 필수입니다") Duration readTimeout,
        @NotNull(message = "다운로드 제한 시간은 필수입니다") Duration downloadTimeout,
        @NotNull(message = "변환 제한 시간은 필수입니다") Duration conversionTimeout,
        @NotNull(message = "추론 제한 시간은 필수입니다") Duration inferenceTimeout,
        @NotNull(message = "결과 보존 시간은 필수입니다") Duration resultRetention,
        @NotNull(message = "요청 유효 기간은 필수입니다") Duration requestValidityLimit,
        @Positive(message = "최대 파일 크기는 양수여야 합니다") long maximumBytes,
        @Positive(message = "최대 결과 크기는 양수여야 합니다") long maximumResultBytes,
        @Positive(message = "최대 작업 수는 양수여야 합니다") int maximumRecords,
        @NotBlank(message = "Python 실행 경로는 필수입니다") String python,
        @NotBlank(message = "pnpm 실행 경로는 필수입니다") String pnpm,
        @NotBlank(message = "FFmpeg 실행 경로는 필수입니다") String ffmpeg,
        @NotBlank(message = "ffprobe 실행 경로는 필수입니다") String ffprobe
) {
    private static final long MAXIMUM_AUDIO_BYTES = 524_288_000L;

    public AiServerProperties {
        for (Duration timeout : new Duration[]{connectTimeout, readTimeout, downloadTimeout, conversionTimeout,
                inferenceTimeout, resultRetention, requestValidityLimit}) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("제한 시간은 유한한 양수여야 합니다");
            }
        }
        if (maximumBytes > MAXIMUM_AUDIO_BYTES || maximumBytes <= 0 || maximumResultBytes <= 0 || maximumRecords <= 0) {
            throw new IllegalArgumentException("파일 및 작업 제한이 올바르지 않습니다");
        }
    }
}
