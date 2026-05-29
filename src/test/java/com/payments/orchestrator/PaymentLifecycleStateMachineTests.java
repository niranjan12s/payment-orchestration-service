package com.payments.orchestrator;

import com.payments.orchestrator.domain.AttemptStatus;
import com.payments.orchestrator.domain.PaymentStatus;
import com.payments.orchestrator.exception.IllegalStateTransitionException;
import com.payments.orchestrator.service.PaymentLifecycleValidator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentLifecycleStateMachineTests {

    private final PaymentLifecycleValidator validator = new PaymentLifecycleValidator();

    @Test
    void testValidIntentTransitions() {
        // CREATED -> PROCESSING
        boolean ignore1 = validator.checkAndValidateIntentTransition(PaymentStatus.CREATED, PaymentStatus.PROCESSING);
        assertThat(ignore1).isFalse();

        // PROCESSING -> AUTHORIZED
        boolean ignore2 = validator.checkAndValidateIntentTransition(PaymentStatus.PROCESSING, PaymentStatus.AUTHORIZED);
        assertThat(ignore2).isFalse();

        // PROCESSING -> FAILED
        boolean ignore3 = validator.checkAndValidateIntentTransition(PaymentStatus.PROCESSING, PaymentStatus.FAILED);
        assertThat(ignore3).isFalse();

        // PROCESSING -> PENDING
        boolean ignore4 = validator.checkAndValidateIntentTransition(PaymentStatus.PROCESSING, PaymentStatus.PENDING);
        assertThat(ignore4).isFalse();

        // PENDING -> AUTHORIZED
        boolean ignore5 = validator.checkAndValidateIntentTransition(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED);
        assertThat(ignore5).isFalse();

        // PENDING -> FAILED
        boolean ignore6 = validator.checkAndValidateIntentTransition(PaymentStatus.PENDING, PaymentStatus.FAILED);
        assertThat(ignore6).isFalse();
    }

    @Test
    void testForbiddenIntentTransitions() {
        // CREATED -> AUTHORIZED (Blocked)
        assertThatThrownBy(() -> validator.checkAndValidateIntentTransition(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("Illegal state transition from CREATED to AUTHORIZED");

        // CREATED -> FAILED (Blocked)
        assertThatThrownBy(() -> validator.checkAndValidateIntentTransition(PaymentStatus.CREATED, PaymentStatus.FAILED))
                .isInstanceOf(IllegalStateTransitionException.class);

        // PENDING -> CREATED (Blocked)
        assertThatThrownBy(() -> validator.checkAndValidateIntentTransition(PaymentStatus.PENDING, PaymentStatus.CREATED))
                .isInstanceOf(IllegalStateTransitionException.class);

        // PENDING -> PROCESSING (Blocked)
        assertThatThrownBy(() -> validator.checkAndValidateIntentTransition(PaymentStatus.PENDING, PaymentStatus.PROCESSING))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void testIdempotentSameStateIntentTransitions() {
        // CREATED -> CREATED (Idempotent, returns false, does not throw)
        boolean ignore1 = validator.checkAndValidateIntentTransition(PaymentStatus.CREATED, PaymentStatus.CREATED);
        assertThat(ignore1).isFalse();

        // PENDING -> PENDING (Idempotent, returns false, does not throw)
        boolean ignore2 = validator.checkAndValidateIntentTransition(PaymentStatus.PENDING, PaymentStatus.PENDING);
        assertThat(ignore2).isFalse();
    }

    @Test
    void testContradictoryWebhookUpdatesIgnoredAndLogged() {
        // AUTHORIZED -> FAILED (Ignored and audit logged, returns true)
        boolean ignore1 = validator.checkAndValidateIntentTransition(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED);
        assertThat(ignore1).isTrue();

        // AUTHORIZED -> PROCESSING (Ignored and audit logged, returns true)
        boolean ignore2 = validator.checkAndValidateIntentTransition(PaymentStatus.AUTHORIZED, PaymentStatus.PROCESSING);
        assertThat(ignore2).isTrue();

        // FAILED -> AUTHORIZED (Ignored and audit logged, returns true)
        boolean ignore3 = validator.checkAndValidateIntentTransition(PaymentStatus.FAILED, PaymentStatus.AUTHORIZED);
        assertThat(ignore3).isTrue();
    }

    @Test
    void testValidAttemptTransitions() {
        // PROCESSING -> AUTHORIZED
        validator.validateAttemptTransition(AttemptStatus.PROCESSING, AttemptStatus.AUTHORIZED);

        // PROCESSING -> FAILED
        validator.validateAttemptTransition(AttemptStatus.PROCESSING, AttemptStatus.FAILED);

        // PROCESSING -> PENDING
        validator.validateAttemptTransition(AttemptStatus.PROCESSING, AttemptStatus.PENDING);

        // PROCESSING -> SUPERSEDED
        validator.validateAttemptTransition(AttemptStatus.PROCESSING, AttemptStatus.SUPERSEDED);

        // PENDING -> AUTHORIZED
        validator.validateAttemptTransition(AttemptStatus.PENDING, AttemptStatus.AUTHORIZED);

        // PENDING -> SUPERSEDED
        validator.validateAttemptTransition(AttemptStatus.PENDING, AttemptStatus.SUPERSEDED);
    }

    @Test
    void testForbiddenAttemptTransitions() {
        // AUTHORIZED -> FAILED (Blocked)
        assertThatThrownBy(() -> validator.validateAttemptTransition(AttemptStatus.AUTHORIZED, AttemptStatus.FAILED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("Cannot transition payment attempt from terminal state AUTHORIZED");

        // FAILED -> PROCESSING (Blocked)
        assertThatThrownBy(() -> validator.validateAttemptTransition(AttemptStatus.FAILED, AttemptStatus.PROCESSING))
                .isInstanceOf(IllegalStateTransitionException.class);

        // SUPERSEDED -> AUTHORIZED (Blocked)
        assertThatThrownBy(() -> validator.validateAttemptTransition(AttemptStatus.SUPERSEDED, AttemptStatus.AUTHORIZED))
                .isInstanceOf(IllegalStateTransitionException.class);
    }
}
