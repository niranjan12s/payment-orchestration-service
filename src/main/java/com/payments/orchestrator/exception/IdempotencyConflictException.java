package com.payments.orchestrator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT, reason = "IDEMPOTENCY_CONFLICT")
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }

    public IdempotencyConflictException(String key, String message) {
        super(String.format("Idempotency conflict for key '%s': %s", key, message));
    }
}
