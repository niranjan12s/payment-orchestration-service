package com.payments.orchestrator.exception;

public class AttemptSupersededException extends RuntimeException {
    public AttemptSupersededException(String message) {
        super(message);
    }
}
