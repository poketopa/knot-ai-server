package com.knot.ai.global.exception;

public record FieldErrorResponse(
        String field,
        String reason
) {
}
