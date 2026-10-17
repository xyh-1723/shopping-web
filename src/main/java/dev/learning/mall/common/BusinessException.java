package dev.learning.mall.common;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }
}
