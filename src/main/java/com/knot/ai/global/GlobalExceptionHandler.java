package com.knot.ai.global;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.knot.ai.transcription.domain.JobException;
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(JobException.class)
    public ResponseEntity<ErrorResponse> handleJob(JobException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(
                        new ErrorResponse(
                                exception.getCode(),
                                "전사 작업을 처리할 수 없습니다",
                                List.of()
                        )
                );
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<FieldError> errors = exception.getBindingResult()
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
                                errors
                        )
                );
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleFormat(Exception exception) {
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "INVALID_REQUEST_BODY",
                                "요청 형식이 올바르지 않습니다",
                                List.of()
                        )
                );
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(Exception exception) {
        return ResponseEntity.status(405)
                .body(
                        new ErrorResponse(
                                "METHOD_NOT_ALLOWED",
                                "허용하지 않는 요청 방식입니다",
                                List.of()
                        )
                );
    }
    @ExceptionHandler({org.springframework.web.servlet.NoHandlerFoundException.class,
            org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleMissingResource(Exception exception) {
        return ResponseEntity.status(404)
                .body(
                        new ErrorResponse(
                                "RESOURCE_NOT_FOUND",
                                "요청 경로를 찾을 수 없습니다",
                                List.of()
                        )
                );
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        return ResponseEntity.internalServerError()
                .body(
                        new ErrorResponse(
                                "INTERNAL_ERROR",
                                "요청을 처리할 수 없습니다",
                                List.of()
                        )
                );
    }
    public record ErrorResponse(
            String code,
            String message,
            List<FieldError> fieldErrors
    ) {
    }
    public record FieldError(
            String field,
            String reason
    ) {
    }
}
