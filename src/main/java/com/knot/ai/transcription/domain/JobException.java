package com.knot.ai.transcription.domain;
public class JobException extends RuntimeException {
    private final String code;
    private final int status;
    public JobException(
            String code,
            int status
    ) {
        super("전사 작업을 처리할 수 없습니다");
        this.code = code;
        this.status = status;
    }
    public String getCode() {
        return code;
    }
    public int getStatus() {
        return status;
    }
}
