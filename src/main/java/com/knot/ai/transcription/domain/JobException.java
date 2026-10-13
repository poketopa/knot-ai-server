package com.knot.ai.transcription.domain;

public class JobException extends RuntimeException {
    private final JobErrorCode errorCode;

    public JobException(JobErrorCode errorCode) {
        super("전사 작업을 처리할 수 없습니다");
        this.errorCode = errorCode;
    }

    public JobErrorCode getErrorCode() {
        return errorCode;
    }

    public String getCode() {
        return errorCode.getCode();
    }

    public int getStatus() {
        return errorCode.getStatus();
    }
}
