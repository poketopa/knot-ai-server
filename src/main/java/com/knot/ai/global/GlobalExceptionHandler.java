package com.knot.ai.global;

import com.knot.ai.global.exception.CommonErrorCode;
import com.knot.ai.global.exception.ErrorCode;
import com.knot.ai.global.exception.ErrorResponse;
import com.knot.ai.global.exception.FieldErrorResponse;
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
        return handleErrorCode(exception.getErrorCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<FieldErrorResponse> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(
                        error -> new FieldErrorResponse(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                )
                .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.validation(fieldErrors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleFormat(Exception exception) {
        return handleErrorCode(CommonErrorCode.INVALID_REQUEST_BODY);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException exception) {
        return handleErrorCode(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleMissingResource(Exception exception) {
        return handleErrorCode(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        return handleErrorCode(CommonErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ErrorResponse> handleErrorCode(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.from(errorCode));
    }
}
