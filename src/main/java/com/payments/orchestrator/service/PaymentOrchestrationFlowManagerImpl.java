package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.dto.*;
import com.payments.orchestrator.exception.PspTimeoutException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link PaymentOrchestrationFlowManager} that manages the end-to-end payment creation flow.
 * It handles idempotency checks, initial state transactions, circuit breakers, safe transport retries,
 * failovers to fallback PSPs, and outcome state updates.
 */
@Service
public class PaymentOrchestrationFlowManagerImpl implements PaymentOrchestrationFlowManager {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrationFlowManagerImpl.class);

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private PaymentOrchestrationService orchestrationService;

    @Autowired
    private RoutingEngine routingEngine;

    @Autowired
    private PspAConnector pspAConnector;

    @Autowired
    private PspBConnector pspBConnector;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * Processes an end-to-end payment transaction request, enforcing idempotency, selecting the routing connector,
     * executing PSP authorization with failover capabilities, and updating final transaction states.
     *
     * @param request the create payment request containing payment details
     * @param idempotencyKey the unique idempotency key for the request
     * @param rawBody the raw request body payload for idempotency verification
     * @return the create payment response indicating final authorization, pending, or failed state
     */
    @Override
    public CreatePaymentResponse processPayment(CreatePaymentRequest request, String idempotencyKey, String rawBody) {
        log.info("Processing end-to-end payment creation flow. Order ID: {}, Idempotency Key: {}",
                request.getMerchantOrderId(), idempotencyKey);

        // 1. Idempotency Check
        IdempotencyResult idempotencyResult = idempotencyService.checkIdempotency(idempotencyKey, rawBody);
        if (idempotencyResult.isMatch()) {
            log.info("Returning cached idempotency response for key: {}", idempotencyKey);
            return idempotencyResult.getCachedResponse();
        }

        // 2. Transaction 1: Atomic state persistence before PSP Call
        PaymentIntent intent = orchestrationService.createInitialPaymentState(request, idempotencyKey);
        PaymentAttempt attempt = intent.getAttempts().get(0);

        UUID intentId = intent.getIntentId();
        UUID attemptId = attempt.getAttemptId();
        String primaryProvider = attempt.getProviderName();

        // 3. PSP Call (outside main transaction boundary)
        PspConnector primaryConnector = routingEngine.selectConnector(attempt.getPaymentMethodType());
        PspResponse pspResponse = null;
        boolean circuitBreakerTriggeredFailover = false;
        PaymentAttempt activeAttempt = attempt;
        PspConnector activeConnector = primaryConnector;

        long startTime = System.nanoTime();
        try {
            String providerIdempKey = "idemp:" + attemptId;
            // Execute with immediate retry policy ONLY for safe transport failures
            pspResponse = executeWithSafeRetries(primaryConnector, attempt, providerIdempKey);
        } catch (CallNotPermittedException circuitBreakerException) {
            log.warn("Primary provider {} circuit breaker is OPEN. Catching CallNotPermittedException and initiating failover...", primaryProvider);
            circuitBreakerTriggeredFailover = true;
        } catch (PspTimeoutException pspTimeoutException) {
            log.error("Ambiguous timeout occurred on calling primary provider {}. Must not failover immediately.", primaryProvider);
            meterRegistry.counter("payment.psp.timeout", "provider", primaryProvider).increment();
            pspResponse = new PspResponse(PspStatus.PENDING, null, "PSP_TIMEOUT", "Connection/Read timeout occurred on calling PSP");
        } catch (Exception unexpectedPspException) {
            log.error("Unhandled exception on calling primary provider {}.", primaryProvider, unexpectedPspException);
            pspResponse = new PspResponse(PspStatus.FAILED, null, "SYSTEM_ERROR", unexpectedPspException.getMessage());
        }

        // 4. HA Fallback Failover on Circuit Breaker Open
        if (circuitBreakerTriggeredFailover) {
            String fallbackProvider = "PSP_A".equals(primaryProvider) ? "PSP_B" : "PSP_A";
            log.info("Circuit breaker failover triggered. Redirecting from {} to fallback provider {}", primaryProvider, fallbackProvider);
            meterRegistry.counter("payment.psp.circuitbreaker.failover", "from", primaryProvider, "to", fallbackProvider).increment();

            // Transaction 1.5: Atomic supersede primary attempt and write secondary fallback attempt in PROCESSING status
            activeAttempt = orchestrationService.createFallbackAttempt(intentId, attemptId, fallbackProvider);
            activeConnector = "PSP_A".equals(fallbackProvider) ? pspAConnector : pspBConnector;

            startTime = System.nanoTime(); // Reset timer for fallback latency metrics
            try {
                String fallbackIdempKey = "idemp:" + activeAttempt.getAttemptId();
                pspResponse = executeWithSafeRetries(activeConnector, activeAttempt, fallbackIdempKey);
            } catch (CallNotPermittedException circuitBreakerException) {
                log.error("Fallback provider {} circuit breaker is ALSO open.", fallbackProvider);
                pspResponse = new PspResponse(PspStatus.PENDING, null, "PROVIDER_BLOCKED", "PSP API endpoint currently blocked by circuit breaker");
            } catch (PspTimeoutException pspTimeoutException) {
                log.error("Ambiguous timeout on fallback provider {}.", fallbackProvider);
                meterRegistry.counter("payment.psp.timeout", "provider", fallbackProvider).increment();
                pspResponse = new PspResponse(PspStatus.PENDING, null, "PSP_TIMEOUT", "Connection/Read timeout occurred on calling PSP");
            } catch (Exception unexpectedPspException) {
                log.error("Exception on calling fallback provider {}.", fallbackProvider, unexpectedPspException);
                pspResponse = new PspResponse(PspStatus.FAILED, null, "SYSTEM_ERROR", unexpectedPspException.getMessage());
            }
        }

        // Record metrics for final active connector execution
        long elapsedNanos = System.nanoTime() - startTime;
        meterRegistry.timer("payment.psp.latency", "provider", activeConnector.getProviderName())
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        meterRegistry.counter("payment.psp.calls", "provider", activeConnector.getProviderName(), "status", pspResponse.getStatus().name())
                .increment();

        // 5. Transaction 2: Atomic state outcome update
        PaymentIntent finalIntent = orchestrationService.updatePaymentOutcome(intentId, activeAttempt.getAttemptId(), pspResponse);
        
        final UUID finalActiveAttemptId = activeAttempt.getAttemptId();
        PaymentAttempt finalAttempt = finalIntent.getAttempts().stream()
                .filter(a -> a.getAttemptId().equals(finalActiveAttemptId))
                .findFirst()
                .orElseThrow();

        // 6. Map and build CreatePaymentResponse
        CreatePaymentResponse createPaymentResponse;
        if (finalIntent.getStatus() == PaymentStatus.AUTHORIZED) {
            PaymentAuthorizedResponse auth = new PaymentAuthorizedResponse(
                    finalIntent.getIntentId(),
                    finalAttempt.getAttemptId(),
                    finalIntent.getMerchantOrderId(),
                    finalAttempt.getProviderName(),
                    finalAttempt.getProviderReference(),
                    new MoneyAmount(finalIntent.getTransactionCurrencyCode(), finalIntent.getTransactionAmount()),
                    new MoneyAmount(finalIntent.getSettlementCurrencyCode(), finalIntent.getSettlementAmount()),
                    finalAttempt.getUpdatedAt()
            );
            createPaymentResponse = CreatePaymentResponse.authorized(auth);
        } else if (finalIntent.getStatus() == PaymentStatus.PENDING) {
            PaymentPendingResponse pending = new PaymentPendingResponse(
                    finalIntent.getIntentId(),
                    finalAttempt.getAttemptId(),
                    finalAttempt.getProviderName(),
                    finalAttempt.getErrorMessage() != null ? finalAttempt.getErrorMessage() : "Payment outcome pending reconciliation",
                    finalAttempt.getUpdatedAt()
            );
            createPaymentResponse = CreatePaymentResponse.pending(pending);
        } else {
            // FAILED response outcome (HTTP 200)
            createPaymentResponse = CreatePaymentResponse.failed(
                    finalIntent.getIntentId(),
                    finalAttempt.getAttemptId(),
                    finalIntent.getMerchantOrderId(),
                    finalAttempt.getProviderName(),
                    finalAttempt.getErrorMessage() != null ? finalAttempt.getErrorMessage() : "Payment authorization rejected",
                    new MoneyAmount(finalIntent.getTransactionCurrencyCode(), finalIntent.getTransactionAmount()),
                    new MoneyAmount(finalIntent.getSettlementCurrencyCode(), finalIntent.getSettlementAmount())
            );
        }

        // 7. Persist completed response in Idempotency cache table
        idempotencyService.saveCompletedResponse(idempotencyKey, createPaymentResponse);

        log.info("Payment flow completed successfully. Intent status: {}", finalIntent.getStatus());
        return createPaymentResponse;
    }

    /**
     * Executes calling the PSP with immediate retry loop ONLY for safe transport failures.
     *
     * @param connector the selected PSP connector
     * @param attempt the current payment attempt entity
     * @param providerIdempKey the provider-specific idempotency key
     * @return the PSP response from the authorization attempt
     * @throws PspTimeoutException if an ambiguous timeout occurs (must not retry immediately)
     * @throws CallNotPermittedException if the circuit breaker is open (must not retry)
     */
    private PspResponse executeWithSafeRetries(PspConnector connector, PaymentAttempt attempt, String providerIdempKey) {
        int maxAttempts = 3;
        int currentAttempt = 0;
        Exception lastException = null;

        while (currentAttempt < maxAttempts) {
            currentAttempt++;
            try {
                return connector.authorize(attempt, providerIdempKey);
            } catch (PspTimeoutException pspTimeoutException) {
                // Ambiguous timeout -> DO NOT RETRY IMMEDIATELY!
                log.warn("Ambiguous read timeout on calling {}. Terminating immediate retry loop.", connector.getProviderName());
                throw pspTimeoutException;
            } catch (CallNotPermittedException circuitBreakerException) {
                // Circuit Breaker blocked -> DO NOT RETRY!
                throw circuitBreakerException;
            } catch (Exception transportException) {
                lastException = transportException;
                boolean isSafeTransportError = isSafeTransportFailure(transportException);
                log.warn("Exception calling {} (Attempt {}/{}). Safe to retry? {}",
                        connector.getProviderName(), currentAttempt, maxAttempts, isSafeTransportError, transportException);
                
                if (!isSafeTransportError || currentAttempt >= maxAttempts) {
                    break;
                }
                
                log.info("Immediate safe transport retry triggered...");
            }
        }

        if (lastException instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException("PSP authorization call failed with transient transport exceptions", lastException);
    }

    /**
     * Helper to classify safe transport failures (Connection reset, connect timeout) vs unsafe ones (read timeouts).
     *
     * @param t the throwable/exception to analyze
     * @return true if the error is a safe transport failure that can be retried immediately, false otherwise
     */
    private boolean isSafeTransportFailure(Throwable t) {
        if (t == null) return false;
        
        if (t instanceof ConnectException) {
            return true;
        }
        
        if (t instanceof SocketTimeoutException) {
            String exceptionMessage = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
            // Safe to retry ONLY if it is a connect timeout, NOT a read timeout
            return exceptionMessage.contains("connect timed out") || exceptionMessage.contains("connection timed out");
        }
        
        if (t instanceof IOException) {
            String exceptionMessage = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
            // IOException connection reset/refused is safe to retry
            return exceptionMessage.contains("connection reset") || exceptionMessage.contains("connection refused") || exceptionMessage.contains("broken pipe");
        }
        
        if (t.getCause() != null && t.getCause() != t) {
            return isSafeTransportFailure(t.getCause());
        }
        
        return false;
    }
}
