package com.example.demo.exception;

import com.example.demo.dto.ApiError;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ApiError(e.getStatus().name(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "요청 형식 또는 값이 올바르지 않습니다."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CONFLICT", "이미 사용 중인 사용자 식별자입니다."));
    }
}
