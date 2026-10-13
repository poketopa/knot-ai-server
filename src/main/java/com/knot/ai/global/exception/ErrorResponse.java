package com.knot.ai.global.exception;

import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        List<FieldErrorResponse> fieldErrors
) {
    public ErrorResponse {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ErrorResponse from(ErrorCode errorCode) {
        return new ErrorResponse(
                errorCode.getCode(),
                errorCode.getMessage(),
                List.of()
        );
    }

    public static ErrorResponse validation(List<FieldErrorResponse> fieldErrors) {
        return new ErrorResponse(
                CommonErrorCode.VALIDATION_ERROR.getCode(),
                CommonErrorCode.VALIDATION_ERROR.getMessage(),
                fieldErrors
        );
    }
}
