package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.AttemptStatus;
import com.payments.orchestrator.domain.PaymentStatus;
import com.payments.orchestrator.exception.IllegalStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentLifecycleValidator {

    private static final Logger log = LoggerFactory.getLogger(PaymentLifecycleValidator.class);

    /**
     * Validates payment intent state transitions.
     * Returns true if the transition is a contradictory update to a terminal state that must be ignored.
     * Throws IllegalStateTransitionException for illegal transitions.
     */
    public boolean checkAndValidateIntentTransition(PaymentStatus current, PaymentStatus target) {
        if (current == target) {
            return false; // Idempotent same-state check
        }

        // Handle terminal states and contradictory webhook updates
        if (current == PaymentStatus.AUTHORIZED || current == PaymentStatus.FAILED) {
            log.error("[SECURITY AUDIT] Contradictory webhook/reconciliation update ignored. " +
                      "Attempted transition from terminal state {} to {}", current, target);
            return true; // Return true to signal that this update must be ignored and not persisted
        }

        boolean isValid = switch (current) {
            case CREATED -> target == PaymentStatus.PROCESSING;
            case PROCESSING -> target == PaymentStatus.AUTHORIZED || target == PaymentStatus.FAILED || target == PaymentStatus.PENDING;
            case PENDING -> target == PaymentStatus.AUTHORIZED || target == PaymentStatus.FAILED || target == PaymentStatus.MANUAL_REVIEW;
            case MANUAL_REVIEW -> target == PaymentStatus.AUTHORIZED || target == PaymentStatus.FAILED;
            case AUTHORIZED, FAILED -> false;
        };

        if (!isValid) {
            throw new IllegalStateTransitionException(current.name(), target.name());
        }

        return false;
    }

    /**
     * Validates payment attempt state transitions.
     * Terminal states for attempts (AUTHORIZED, FAILED, SUPERSEDED) are strictly immutable.
     * Throws IllegalStateTransitionException for illegal transitions.
     */
    public void validateAttemptTransition(AttemptStatus current, AttemptStatus target) {
        if (current == target) {
            return; // Idempotent
        }

        if (current == AttemptStatus.AUTHORIZED || current == AttemptStatus.FAILED || current == AttemptStatus.SUPERSEDED) {
            throw new IllegalStateTransitionException(
                    String.format("Cannot transition payment attempt from terminal state %s to %s", current, target)
            );
        }

        boolean isValid = switch (current) {
            case PROCESSING -> target == AttemptStatus.AUTHORIZED || target == AttemptStatus.FAILED || target == AttemptStatus.PENDING || target == AttemptStatus.SUPERSEDED;
            case PENDING -> target == AttemptStatus.AUTHORIZED || target == AttemptStatus.FAILED || target == AttemptStatus.SUPERSEDED;
            case AUTHORIZED, FAILED, SUPERSEDED -> false;
        };

        if (!isValid) {
            throw new IllegalStateTransitionException(current.name(), target.name());
        }
    }
}
