package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.PaymentAttempt;
import com.payments.orchestrator.dto.PspResponse;

public interface PspConnector {

    /**
     * Retrieves the unique identifier of the payment service provider connector.
     */
    String getProviderName();

    /**
     * Executes the payment authorization call against the external provider endpoint.
     *
     * @param attempt the database attempt recording target request parameters
     * @param providerIdempotencyKey unique idempotency key sent to the PSP
     * @return the result returned by the provider
     * @throws com.payments.orchestrator.exception.PspTimeoutException if request times out (ambiguous PENDING state)
     */
    PspResponse authorize(PaymentAttempt attempt, String providerIdempotencyKey);

    /**
     * Queries the external provider endpoint for the current status of a transaction attempt.
     *
     * @param attempt the payment attempt records to query status for
     * @return the current transaction state from the provider
     * @throws com.payments.orchestrator.exception.PspTimeoutException if query times out (remains PENDING)
     */
    PspResponse queryStatus(PaymentAttempt attempt);
}
