package com.payments.orchestrator.service;

import com.payments.orchestrator.dto.CreatePaymentRequest;
import com.payments.orchestrator.dto.CreatePaymentResponse;

public interface PaymentOrchestrationFlowManager {

    /**
     * Coordinates the end-to-end payment creation, validation, idempotency,
     * persistence transaction, and external PSP execution (with retry and HA failover policies).
     *
     * @param request the CreatePaymentRequest payload
     * @param idempotencyKey the idempotency key header value
     * @param rawBody the exact unparsed HTTP body string used for deterministic key validation
     * @return the CreatePaymentResponse object containing the resolved terminal or pending status
     */
    CreatePaymentResponse processPayment(CreatePaymentRequest request, String idempotencyKey, String rawBody);
}
