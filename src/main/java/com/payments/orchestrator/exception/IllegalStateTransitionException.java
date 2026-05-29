package com.payments.orchestrator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.UNPROCESSABLE_ENTITY, reason = "ILLEGAL_STATE_TRANSITION")
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String message) {
        super(message);
    }

    public IllegalStateTransitionException(String fromState, String toState) {
        super(String.format("Illegal state transition from %s to %s", fromState, toState));
    }
}
