package com.knot.ai.global.exception;

import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        List<FieldError> fieldErrors
) {
    public record FieldError(
            String field,
            String reason
    ) {
    }
}
