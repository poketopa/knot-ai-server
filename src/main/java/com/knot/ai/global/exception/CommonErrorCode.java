package com.knot.ai.global.exception;

public enum CommonErrorCode implements ErrorCode {
    UNAUTHORIZED(
            401,
            "앱 인증이 필요합니다"
    ),
    VALIDATION_ERROR(
            400,
            "요청 값이 올바르지 않습니다"
    ),
    INVALID_REQUEST_BODY(
            400,
            "요청 형식이 올바르지 않습니다"
    ),
    METHOD_NOT_ALLOWED(
            405,
            "허용하지 않는 요청 방식입니다"
    ),
    RESOURCE_NOT_FOUND(
            404,
            "요청 경로를 찾을 수 없습니다"
    ),
    INTERNAL_ERROR(
            500,
            "요청을 처리할 수 없습니다"
    );

    private final int status;
    private final String message;

    CommonErrorCode(
            int status,
            String message
    ) {
        this.status = status;
        this.message = message;
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
        return message;
    }
}
