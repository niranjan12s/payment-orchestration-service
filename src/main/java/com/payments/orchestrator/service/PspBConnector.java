package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.PaymentAttempt;
import com.payments.orchestrator.dto.PspResponse;
import com.payments.orchestrator.dto.PspStatus;
import com.payments.orchestrator.exception.PspTimeoutException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PspBConnector implements PspConnector {

    private static final Logger log = LoggerFactory.getLogger(PspBConnector.class);

    @Value("${orchestrator.psp.psp-b.mode:SUCCESS}")
    private String mode;

    @Override
    public String getProviderName() {
        return "PSP_B";
    }

    @Override
    @CircuitBreaker(name = "psp-b")
    public PspResponse authorize(PaymentAttempt attempt, String providerIdempotencyKey) {
        log.info("Sending authorization request to PSP_B for attempt_id: {}, provider_idempotency_key: {}",
                attempt.getAttemptId(), providerIdempotencyKey);

        String currentMode = mode != null ? mode.toUpperCase().trim() : "SUCCESS";

        switch (currentMode) {
            case "SUCCESS":
                String ref = "ref_pspb_" + UUID.randomUUID().toString().substring(0, 8);
                log.info("PSP_B transaction success. Reference: {}", ref);
                return new PspResponse(PspStatus.SUCCESS, ref, null, null);

            case "FAILURE":
                log.warn("PSP_B transaction failed. UPI account validation error.");
                return new PspResponse(PspStatus.FAILED, null, "UPI_ACCOUNT_INVALID", "Invalid virtual payment address");

            case "TIMEOUT":
                log.error("PSP_B connection timeout. Simulating read timeout exception.");
                throw new PspTimeoutException("Connect/Read timeout occurred on calling PSP_B");

            default:
                log.error("Unknown mock mode configured for PSP_B: {}", currentMode);
                throw new IllegalStateException("Unknown connector mode: " + currentMode);
        }
    }

    @Override
    @CircuitBreaker(name = "psp-b")
    public PspResponse queryStatus(PaymentAttempt attempt) {
        log.info("Querying transaction status from PSP_B for attempt_id: {}", attempt.getAttemptId());

        String currentMode = mode != null ? mode.toUpperCase().trim() : "SUCCESS";

        switch (currentMode) {
            case "SUCCESS":
                String ref = "ref_pspb_" + UUID.randomUUID().toString().substring(0, 8);
                log.info("PSP_B transaction status query returned: success (Reference: {})", ref);
                return new PspResponse(PspStatus.SUCCESS, ref, null, null);

            case "FAILURE":
                log.warn("PSP_B transaction status query returned: failed (UPI account invalid).");
                return new PspResponse(PspStatus.FAILED, null, "UPI_ACCOUNT_INVALID", "Invalid virtual payment address");

            case "TIMEOUT":
                log.error("PSP_B connection timeout on status query. Simulating read timeout exception.");
                throw new PspTimeoutException("Connect/Read timeout occurred on querying PSP_B status");

            default:
                log.error("Unknown mock mode configured for PSP_B: {}", currentMode);
                throw new IllegalStateException("Unknown connector mode: " + currentMode);
        }
    }

    // Direct setter to allow unit testing configurations dynamically
    public void setMode(String mode) {
        this.mode = mode;
    }
}
