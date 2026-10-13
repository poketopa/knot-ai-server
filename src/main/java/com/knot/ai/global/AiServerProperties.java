package com.knot.ai.global;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
        @NotNull(message = "실행 경로는 필수입니다") Path runnerDirectory,
        @NotEmpty(message = "S3 호스트는 필수입니다") Set<String> allowedS3Hosts,
        @NotNull(message = "다운로드 제한 시간은 필수입니다") Duration downloadTimeout,
        @NotNull(message = "변환 제한 시간은 필수입니다") Duration conversionTimeout,
        @NotNull(message = "추론 제한 시간은 필수입니다") Duration inferenceTimeout,
        @NotBlank(message = "Python 실행 경로는 필수입니다") String python,
        @NotBlank(message = "pnpm 실행 경로는 필수입니다") String pnpm,
        @NotBlank(message = "FFmpeg 실행 경로는 필수입니다") String ffmpeg
) {
    public AiServerProperties {
        for (Duration timeout : new Duration[]{downloadTimeout, conversionTimeout, inferenceTimeout}) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("제한 시간은 유한한 양수여야 합니다");
            }
        }
    }
}
