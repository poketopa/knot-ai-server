package com.knot.ai.transcription.domain;

import com.knot.ai.global.exception.ErrorCode;

public enum JobErrorCode implements ErrorCode {
    REQUEST_EXPIRED(400),
    PROFILE_MISMATCH(409),
    IDEMPOTENCY_CONFLICT(409),
    BUSY(409),
    NOT_READY(503),
    JOB_NOT_FOUND(404),
    EXECUTOR_REJECTED(500),
    INFERENCE_FAILED(500),
    CANCELED(409),
    INVALID_RESULT(500),
    INVALID_AUDIO_DURATION(400),
    AUDIO_DURATION_EXCEEDED(400),
    INVALID_AUDIO_FORMAT(400),
    INVALID_PROFILE(503),
    MISSING_RUNTIME(503),
    PROCESS_CLEANUP_FAILED(503),
    UNOWNED_WORK_DIRECTORY(503),
    DECODE_TIMEOUT(504),
    INFERENCE_TIMEOUT(504),
    DECODE_FAILED(500),
    AUDIO_TOO_LARGE(400),
    DOWNLOAD_FAILED(502),
    DOWNLOAD_TIMEOUT(504),
    INCOMPLETE_DOWNLOAD(502),
    INPUT_CHANGED(409),
    INVALID_AUDIO_METADATA(400),
    INVALID_DOWNLOAD_HEADERS(400),
    INVALID_DOWNLOAD_URL(400),
    MISSING_OBJECT_VALIDATOR(400),
    STT_FAILED(500),
    DIARIZATION_FAILED(500),
    DIARIZATION_EMPTY(500),
    OUTPUT_LIMIT(500),
    INVALID_MANIFEST(500),
    INVALID_ARGUMENTS(500),
    RUNNER_FAILED(500);

    private final int status;

    JobErrorCode(int status) {
        this.status = status;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public String getMessage() {
        return "전사 작업을 처리할 수 없습니다";
    }
}
