package com.knot.ai.global;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
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
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration downloadTimeout,
        @NotNull Duration conversionTimeout,
        @NotNull Duration inferenceTimeout,
        @NotNull Duration resultRetention,
        @NotNull Duration requestValidityLimit,
        @Positive long maximumBytes,
        @Positive long maximumResultBytes,
        @Positive int maximumRecords,
        @NotBlank String python,
        @NotBlank String pnpm,
        @NotBlank String ffmpeg,
        @NotBlank String ffprobe
) {
    public AiServerProperties {
        for (Duration timeout : new Duration[]{connectTimeout, readTimeout, downloadTimeout, conversionTimeout,
                inferenceTimeout, resultRetention, requestValidityLimit}) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("제한 시간은 유한한 양수여야 합니다");
            }
        }
        if (maximumBytes > 524_288_000L || maximumBytes <= 0 || maximumResultBytes <= 0 || maximumRecords <= 0) {
            throw new IllegalArgumentException("파일 및 작업 제한이 올바르지 않습니다");
        }
    }
}
