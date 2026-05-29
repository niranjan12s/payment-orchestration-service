package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.PaymentIntent;

public interface ReconciliationService {

    /**
     * Executes the reconciliation processing logic for a single PaymentIntent.
     * Evaluates state resolutions, checks escalation durations, queries external PSPs,
     * and persists atomic transaction updates and events.
     *
     * @param intent the locked pending payment intent to reconcile
     */
    void reconcileIntent(PaymentIntent intent);
}
