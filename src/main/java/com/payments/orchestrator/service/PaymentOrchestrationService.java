package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.PaymentIntent;
import com.payments.orchestrator.dto.CreatePaymentRequest;

public interface PaymentOrchestrationService {

    /**
     * Atomically creates payment intent, attempt, event, and outbox event in a single transaction.
     * Enforces merchant order uniqueness, resolves correlation / request telemetry headers,
     * and performs provider routing logic based on payment method.
     *
     * @param request the payment creation payload DTO
     * @param idempotencyKey the idempotency key passed from request context
     * @return the saved PaymentIntent containing generated IDs and status
     * @throws com.payments.orchestrator.exception.DuplicateMerchantOrderException if order ID already exists
     */
    PaymentIntent createInitialPaymentState(CreatePaymentRequest request, String idempotencyKey);

    /**
     * Atomically updates intent and attempt states based on PSP authorization response,
     * executing state transitions in-memory and saving the terminal state, events, and outbox changes.
     */
    PaymentIntent updatePaymentOutcome(java.util.UUID intentId, java.util.UUID attemptId, com.payments.orchestrator.dto.PspResponse pspResponse);

    /**
     * Atomically supersedes the primary attempt, generates a secondary attempt for the fallback provider,
     * updates the intent's active attempt pointer, and persists audit events.
     */
    com.payments.orchestrator.domain.PaymentAttempt createFallbackAttempt(java.util.UUID intentId, java.util.UUID primaryAttemptId, String fallbackProvider);
}
