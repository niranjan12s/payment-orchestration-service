package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.PaymentIntent;

public interface RetryService {

    /**
     * Safely executes async retry operations for a single PaymentIntent.
     * Generates a new serialized attempt, initiates the external PSP authorize call,
     * and evaluates outcomes, superseding prior attempts on success.
     *
     * @param intent the locked pending payment intent to retry
     */
    void executeRetry(PaymentIntent intent);
}
