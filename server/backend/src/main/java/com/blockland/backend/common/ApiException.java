package com.blockland.backend.common;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Business error rendered as {@code {"message": ..., "details": [...]}}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final List<String> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<String> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public static ApiException notFound(String what, String id) {
        return new ApiException(HttpStatus.NOT_FOUND, what + "不存在: " + id);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException invalid(List<String> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "参数校验失败", errors);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public HttpStatus status() {
        return status;
    }

    public List<String> details() {
        return details;
    }
}
