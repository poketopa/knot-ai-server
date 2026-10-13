package com.knot.ai.global;

import com.knot.ai.global.exception.ErrorResponse;
import com.knot.ai.global.exception.ErrorResponse.FieldError;
import com.knot.ai.transcription.domain.JobException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(JobException.class)
    public ResponseEntity<ErrorResponse> handleJob(JobException exception) {
        return respond(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<FieldError> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(
                        error -> new FieldError(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                )
                .toList();
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "VALIDATION_ERROR",
                                "요청 값이 올바르지 않습니다",
                                fieldErrors
                        )
                );
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleFormat(Exception exception) {
        return respond(
                400,
                "INVALID_REQUEST_BODY",
                "요청 형식이 올바르지 않습니다"
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException exception) {
        return respond(
                405,
                "METHOD_NOT_ALLOWED",
                "허용하지 않는 요청 방식입니다"
        );
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleMissingResource(Exception exception) {
        return respond(
                404,
                "RESOURCE_NOT_FOUND",
                "요청 경로를 찾을 수 없습니다"
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        return respond(
                500,
                "INTERNAL_ERROR",
                "요청을 처리할 수 없습니다"
        );
    }

    private ResponseEntity<ErrorResponse> respond(
            int status,
            String code,
            String message
    ) {
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                code,
                                message,
                                List.of()
                        )
                );
    }
}
