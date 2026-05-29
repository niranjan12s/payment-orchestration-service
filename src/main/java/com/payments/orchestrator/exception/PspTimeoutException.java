package com.payments.orchestrator.exception;

public class PspTimeoutException extends RuntimeException {

    public PspTimeoutException(String message) {
        super(message);
    }

    public PspTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
