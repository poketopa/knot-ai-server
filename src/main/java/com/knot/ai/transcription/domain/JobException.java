package com.knot.ai.transcription.domain;

public class JobException extends RuntimeException {
    private final JobErrorCode errorCode;

    public JobException(JobErrorCode errorCode) {
        super(errorCode.getMessage());
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
