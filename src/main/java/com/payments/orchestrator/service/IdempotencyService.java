package com.payments.orchestrator.service;

import com.payments.orchestrator.dto.CreatePaymentResponse;
import com.payments.orchestrator.dto.IdempotencyResult;

public interface IdempotencyService {

    /**
     * Inspects the idempotency key and hashes the payload to decide the processing route:
     * - New request: Persists key as 'PROCESSING' and returns Status.ABSENT.
     * - Replayed request: Returns Status.MATCH with the cached response.
     * - Parameter conflict: Throws IdempotencyConflictException (409).
     */
    IdempotencyResult checkIdempotency(String idempotencyKey, String rawBody);

    /**
     * Updates an in-flight 'PROCESSING' idempotency key status to 'COMPLETED'
     * and persists the generated response payload in the JSONB column.
     */
    void saveCompletedResponse(String idempotencyKey, CreatePaymentResponse response);
}
