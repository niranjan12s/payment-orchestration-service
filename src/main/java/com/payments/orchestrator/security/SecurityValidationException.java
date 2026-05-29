package com.payments.orchestrator.security;

import org.springframework.http.HttpStatus;

public class SecurityValidationException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public SecurityValidationException(String errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
