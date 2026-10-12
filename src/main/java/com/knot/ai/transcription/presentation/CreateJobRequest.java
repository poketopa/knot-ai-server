package com.knot.ai.transcription.presentation;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.knot.ai.transcription.application.CreateJobCommand;
public record CreateJobRequest(
        @NotNull(message = "전사 요청 ID는 필수입니다") UUID attemptId,
        @NotBlank(message = "실행 프로필은 필수입니다") String profileId,
        @NotNull(message = "요청 만료 시각은 필수입니다") Instant requestValidUntil,
        @NotNull(message = "오디오 입력은 필수입니다") @Valid Input input,
        @Positive(message = "화자 수는 양수여야 합니다") Integer speakerCount
) {
    public CreateJobCommand toCommand() {
        return new CreateJobCommand(
                attemptId,
                profileId,
                requestValidUntil,
                input.toInput(),
                speakerCount
        );
    }
    public record Input(
            @NotBlank(message = "버킷은 필수입니다") String bucket,
            @NotBlank(message = "객체 경로는 필수입니다") String key,
            String versionId,
            @NotBlank(message = "객체 검증값은 필수입니다") String etag,
            @NotBlank(message = "다운로드 URL은 필수입니다") String downloadUrl,
            Map<String, String> requiredHeaders,
            @Positive(message = "파일 크기는 양수여야 합니다") @Max(value = 524288000, message = "파일이 너무 큽니다") long contentLength,
            @Pattern(regexp = "audio/webm", message = "WebM 오디오만 허용합니다") @NotNull String contentType
    ) {
        public CreateJobCommand.AudioInput toInput() {
            return new CreateJobCommand.AudioInput(
                    bucket,
                    key,
                    versionId,
                    etag,
                    downloadUrl,
                    requiredHeaders,
                    contentLength,
                    contentType
            );
        }
    }
}
