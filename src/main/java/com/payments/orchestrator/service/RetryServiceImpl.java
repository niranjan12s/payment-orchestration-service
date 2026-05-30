package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.dto.PspResponse;
import com.payments.orchestrator.dto.PspStatus;
import com.payments.orchestrator.exception.PspTimeoutException;
import com.payments.orchestrator.repository.PaymentAttemptRepository;
import com.payments.orchestrator.repository.PaymentEventRepository;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class RetryServiceImpl implements RetryService {

    private static final Logger log = LoggerFactory.getLogger(RetryServiceImpl.class);

    @Autowired
    private PaymentIntentRepository intentRepository;

    @Autowired
    private PaymentAttemptRepository attemptRepository;

    @Autowired
    private PaymentEventRepository eventRepository;

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private PaymentLifecycleValidator lifecycleValidator;

    @Autowired
    private PspAConnector pspAConnector;

    @Autowired
    private PspBConnector pspBConnector;

    @Autowired
    private PspErrorClassifier errorClassifier;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private RetryServiceImpl self;

    /**
     * Executes the non-transactional external retry authorization call and delegates outcomes
     * to transactional boundaries, ensuring network calls never happen inside a database transaction.
     */
    @Override
    public void executeRetry(PaymentIntent intent) {
        UUID intentId = intent.getIntentId();
        log.info("Executing async retry operations for PaymentIntent: {}", intentId);

        // 1. Transaction 1: Create a new serialized PaymentAttempt in PROCESSING status
        PaymentAttempt newAttempt = self.prepareRetryAttempt(intentId);
        
        if (newAttempt == null) {
            log.info("Retry preparation returned null (max attempts exceeded or already resolved) for intent {}.", intentId);
            return;
        }

        UUID attemptId = newAttempt.getAttemptId();
        String provider = newAttempt.getProviderName();
        PspConnector connector = "PSP_A".equalsIgnoreCase(provider) ? pspAConnector : pspBConnector;

        // 2. Execute PSP Authorization outside database transaction
        PspResponse pspResponse;
        try {
            String providerIdempKey = "idemp:" + attemptId;
            log.info("Sending retry authorization request to {} for attempt: {}, idempotency key: {}",
                    provider, attemptId, providerIdempKey);
            
            // Execute the PSP authorize call
            pspResponse = connector.authorize(newAttempt, providerIdempKey);
            
        } catch (PspTimeoutException pspTimeoutException) {
            log.error("Ambiguous timeout occurred during retry of provider {}.", provider);
            pspResponse = new PspResponse(PspStatus.PENDING, null, "PSP_TIMEOUT", "Connection/Read timeout occurred on calling PSP");
        } catch (CallNotPermittedException circuitBreakerException) {
            log.warn("Circuit Breaker is OPEN for provider {} during retry. Deflecting attempt.", provider);
            pspResponse = new PspResponse(PspStatus.PENDING, null, "PROVIDER_BLOCKED", "PSP API endpoint currently blocked by circuit breaker");
        } catch (Exception unexpectedPspException) {
            log.error("Generic exception during retry of provider {}.", provider, unexpectedPspException);
            pspResponse = new PspResponse(PspStatus.FAILED, null, "SYSTEM_ERROR", unexpectedPspException.getMessage());
        }

        // 3. Transaction 2: Resolve and apply outcomes atomically
        self.resolveOutcome(intentId, attemptId, pspResponse);
    }

    /**
     * Atomically prepares the retry attempt. Marks old attempt as FAILED if still in processing/pending,
     * checks limit constraints, creates a new attempt row (N+1), and persists initial state.
     */
    @Transactional
    public PaymentAttempt prepareRetryAttempt(UUID intentId) {
        PaymentIntent intent = intentRepository.findByIdWithAttempts(intentId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found: " + intentId));

        // Confirm intent is still PENDING and eligible to be retried
        if (intent.getStatus() != PaymentStatus.PENDING) {
            return null;
        }

        // Find the active/latest attempt
        PaymentAttempt activeAttempt = intent.getAttempts().stream()
                .max(Comparator.comparing(PaymentAttempt::getCreatedAt))
                .orElse(null);

        if (activeAttempt == null) {
            return null;
        }

        int currentAttemptsCount = intent.getAttempts().size();
        log.info("Preparing retry for intent: {}. Active attempt retry count: {}, Total attempts in intent: {}",
                intentId, activeAttempt.getRetryCount(), currentAttemptsCount);

        // Enforce Max attempts threshold (5 attempts maximum)
        if (currentAttemptsCount >= 5) {
            log.error("[DLQ CRITICAL ALERT] Max retry attempts (5) exceeded for PaymentIntent: {}. Transitioning to FAILED terminal status.",
                    intentId);
            
            lifecycleValidator.checkAndValidateIntentTransition(intent.getStatus(), PaymentStatus.FAILED);
            intent.setStatus(PaymentStatus.FAILED);
            intentRepository.saveAndFlush(intent);

            // Write DLQ Failure event using standard HashMap to prevent NullPointerException
            PaymentEvent dlqEvent = new PaymentEvent();
            dlqEvent.setEventId(UUID.randomUUID());
            dlqEvent.setIntentId(intentId);
            dlqEvent.setCorrelationId(intent.getCorrelationId());
            dlqEvent.setEventType("PAYMENT_FAILED");
            
            Map<String, Object> dlqPayload = new HashMap<>();
            dlqPayload.put("status", "FAILED");
            dlqPayload.put("reason", "MAX_RETRY_EXCEEDED");
            dlqPayload.put("total_attempts", currentAttemptsCount);
            dlqEvent.setEventPayload(dlqPayload);
            eventRepository.save(dlqEvent);

            // Write outbox event using standard HashMap to prevent NullPointerException
            PaymentOutbox dlqOutbox = new PaymentOutbox();
            dlqOutbox.setOutboxId(UUID.randomUUID());
            dlqOutbox.setAggregateId(intentId);
            dlqOutbox.setAggregateType("PaymentIntent");
            dlqOutbox.setCorrelationId(intent.getCorrelationId());
            dlqOutbox.setEventType("PAYMENT_FAILED");
            
            Map<String, Object> outboxPayload = new HashMap<>();
            outboxPayload.put("intent_id", intentId.toString());
            outboxPayload.put("status", "FAILED");
            if (intent.getMerchantId() != null) {
                outboxPayload.put("merchant_id", intent.getMerchantId().toString());
            }
            outboxPayload.put("merchant_order_id", intent.getMerchantOrderId());
            dlqOutbox.setPayload(outboxPayload);
            dlqOutbox.setStatus(OutboxStatus.PENDING);
            dlqOutbox.setRetryCount(0);
            dlqOutbox.setSourceEventId(dlqEvent.getEventId());
            outboxRepository.save(dlqOutbox);

            meterRegistry.counter("payment.retry.dlq.count").increment();
            return null;
        }

        // Create new attempt (N+1)
        UUID newAttemptId = UUID.randomUUID();
        PaymentAttempt newAttempt = new PaymentAttempt();
        newAttempt.setAttemptId(newAttemptId);
        newAttempt.setCorrelationId(intent.getCorrelationId());
        newAttempt.setRequestId(intent.getRequestId());
        newAttempt.setProviderName(activeAttempt.getProviderName());
        newAttempt.setPaymentMethodType(activeAttempt.getPaymentMethodType());
        newAttempt.setPaymentTokenReference(activeAttempt.getPaymentTokenReference());
        newAttempt.setStatus(AttemptStatus.PROCESSING);
        newAttempt.setRetryCount(activeAttempt.getRetryCount() + 1);

        intent.addAttempt(newAttempt);
        intent.setActiveAttemptId(newAttemptId);

        // Write retry execution events using standard HashMap to prevent NullPointerException
        PaymentEvent retryEvent = new PaymentEvent();
        retryEvent.setEventId(UUID.randomUUID());
        retryEvent.setIntentId(intentId);
        retryEvent.setAttemptId(newAttemptId);
        retryEvent.setCorrelationId(intent.getCorrelationId());
        retryEvent.setEventType("RETRY_EXECUTED");
        
        Map<String, Object> retryPayload = new HashMap<>();
        retryPayload.put("aggregate_id", intentId.toString());
        retryPayload.put("retry_count", newAttempt.getRetryCount());
        retryPayload.put("provider", newAttempt.getProviderName());
        retryEvent.setEventPayload(retryPayload);

        // Save states
        PaymentIntent savedIntent = intentRepository.saveAndFlush(intent);
        
        PaymentAttempt savedAttempt = savedIntent.getAttempts().stream()
                .max(Comparator.comparingInt(PaymentAttempt::getRetryCount))
                .orElse(newAttempt);
        
        eventRepository.save(retryEvent);

        log.info("Successfully persisted new retry attempt N+1: {} for intent {}", savedAttempt.getAttemptId(), intentId);
        return savedAttempt;
    }

    /**
     * Atomically resolves the final state of the intent and attempt from the retry response.
     */
    @Transactional
    public void resolveOutcome(UUID intentId, UUID attemptId, PspResponse pspResponse) {
        PaymentIntent intent = intentRepository.findByIdWithAttempts(intentId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found: " + intentId));

        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentAttempt not found: " + attemptId));

        // Contradictory check: If intent is already terminal, ignore and audit log
        if (intent.getStatus() == PaymentStatus.AUTHORIZED || intent.getStatus() == PaymentStatus.FAILED) {
            log.error("[SECURITY AUDIT] Contradictory retry resolution ignored. Intent {} is already terminal {}",
                    intentId, intent.getStatus());
            return;
        }

        PaymentStatus targetIntentStatus = errorClassifier.getTargetIntentStatus(pspResponse.getStatus());
        AttemptStatus targetAttemptStatus = errorClassifier.getTargetAttemptStatus(pspResponse.getStatus());

        if (pspResponse.getStatus() == PspStatus.SUCCESS) {
            // Happy path: Retry succeeds!
            lifecycleValidator.validateAttemptTransition(attempt.getStatus(), AttemptStatus.AUTHORIZED);
            attempt.setStatus(AttemptStatus.AUTHORIZED);
            attempt.setProviderReference(pspResponse.getProviderReference());

            lifecycleValidator.checkAndValidateIntentTransition(intent.getStatus(), PaymentStatus.AUTHORIZED);
            intent.setStatus(PaymentStatus.AUTHORIZED);

            // Write Successful resolution events using standard HashMap to prevent NullPointerException
            PaymentEvent successEvent = new PaymentEvent();
            successEvent.setEventId(UUID.randomUUID());
            successEvent.setIntentId(intentId);
            successEvent.setAttemptId(attemptId);
            successEvent.setCorrelationId(intent.getCorrelationId());
            successEvent.setEventType("PAYMENT_AUTHORIZED");
            
            Map<String, Object> successPayload = new HashMap<>();
            successPayload.put("status", "AUTHORIZED");
            successPayload.put("provider", attempt.getProviderName());
            successPayload.put("provider_reference", pspResponse.getProviderReference());
            successEvent.setEventPayload(successPayload);
            eventRepository.save(successEvent);

            // Write Outbox using standard HashMap to prevent NullPointerException
            PaymentOutbox outbox = new PaymentOutbox();
            outbox.setOutboxId(UUID.randomUUID());
            outbox.setAggregateId(intentId);
            outbox.setAggregateType("PaymentIntent");
            outbox.setCorrelationId(intent.getCorrelationId());
            outbox.setEventType("PAYMENT_AUTHORIZED");
            
            Map<String, Object> outboxPayload = new HashMap<>();
            outboxPayload.put("intent_id", intentId.toString());
            outboxPayload.put("status", "AUTHORIZED");
            if (intent.getMerchantId() != null) {
                outboxPayload.put("merchant_id", intent.getMerchantId().toString());
            }
            outboxPayload.put("merchant_order_id", intent.getMerchantOrderId());
            outboxPayload.put("amount", intent.getTransactionAmount().toString());
            outboxPayload.put("currency", intent.getTransactionCurrencyCode());
            outboxPayload.put("provider_reference", pspResponse.getProviderReference());
            outbox.setPayload(outboxPayload);
            outbox.setStatus(OutboxStatus.PENDING);
            outbox.setRetryCount(0);
            outbox.setSourceEventId(successEvent.getEventId());
            outboxRepository.save(outbox);

            meterRegistry.counter("payment.retry.execution.success").increment();
            log.info("Retry attempt N+1 succeeded! Reconciled intent {} to AUTHORIZED. Prior attempts superseded.", intentId);

        } else if (pspResponse.getStatus() == PspStatus.FAILED) {
            // Failed retry attempt
            lifecycleValidator.validateAttemptTransition(attempt.getStatus(), AttemptStatus.FAILED);
            attempt.setStatus(AttemptStatus.FAILED);
            attempt.setErrorCode(pspResponse.getErrorCode() != null ? pspResponse.getErrorCode() : "RETRY_FAILED");
            attempt.setErrorMessage(pspResponse.getErrorMessage() != null ? pspResponse.getErrorMessage() : "Transaction declined during retry");

            // Evaluate if failure is retry-safe and attempts count < 5
            boolean isErrorCodeSafeForRetry = isErrorCodeRetrySafe(pspResponse.getErrorCode());
            int totalAttempts = intent.getAttempts().size();

            if (isErrorCodeSafeForRetry && totalAttempts < 5) {
                // Keep intent as PENDING to allow further scheduled retries
                log.warn("Retry failed but error code {} is retry-safe. Retaining PENDING intent state (Attempts: {}/5).",
                        pspResponse.getErrorCode(), totalAttempts);
            } else {
                // Hard decline or max attempts reached => Fail the entire intent
                log.error("Retry failed definitively. Error code: {}, Total attempts: {}. Transitioning entire intent to FAILED.",
                        pspResponse.getErrorCode(), totalAttempts);

                lifecycleValidator.checkAndValidateIntentTransition(intent.getStatus(), PaymentStatus.FAILED);
                intent.setStatus(PaymentStatus.FAILED);

                // Write FAILED event using standard HashMap to prevent NullPointerException
                PaymentEvent failEvent = new PaymentEvent();
                failEvent.setEventId(UUID.randomUUID());
                failEvent.setIntentId(intentId);
                failEvent.setAttemptId(attemptId);
                failEvent.setCorrelationId(intent.getCorrelationId());
                failEvent.setEventType("PAYMENT_FAILED");
                
                Map<String, Object> failPayload = new HashMap<>();
                failPayload.put("status", "FAILED");
                failPayload.put("error_code", attempt.getErrorCode());
                failPayload.put("error_message", attempt.getErrorMessage());
                failEvent.setEventPayload(failPayload);
                eventRepository.save(failEvent);

                // Write outbox event using standard HashMap to prevent NullPointerException
                PaymentOutbox outbox = new PaymentOutbox();
                outbox.setOutboxId(UUID.randomUUID());
                outbox.setAggregateId(intentId);
                outbox.setAggregateType("PaymentIntent");
                outbox.setCorrelationId(intent.getCorrelationId());
                outbox.setEventType("PAYMENT_FAILED");
                
                Map<String, Object> outboxPayload = new HashMap<>();
                outboxPayload.put("intent_id", intentId.toString());
                outboxPayload.put("status", "FAILED");
                if (intent.getMerchantId() != null) {
                    outboxPayload.put("merchant_id", intent.getMerchantId().toString());
                }
                outboxPayload.put("merchant_order_id", intent.getMerchantOrderId());
                outbox.setPayload(outboxPayload);
                outbox.setStatus(OutboxStatus.PENDING);
                outbox.setRetryCount(0);
                outbox.setSourceEventId(failEvent.getEventId());
                outboxRepository.save(outbox);
            }

            meterRegistry.counter("payment.retry.execution.failure").increment();
        } else {
            // PENDING status outcome (Timeout / Transient query block)
            lifecycleValidator.validateAttemptTransition(attempt.getStatus(), AttemptStatus.PENDING);
            attempt.setStatus(AttemptStatus.PENDING);
            attempt.setErrorCode(pspResponse.getErrorCode());
            attempt.setErrorMessage(pspResponse.getErrorMessage());

            log.info("Retry call returned PENDING. Retaining intent PENDING state for reconciliation.");
        }

        intentRepository.saveAndFlush(intent);
        attemptRepository.save(attempt);
    }

    /**
     * Determines retry safety by evaluating attempt failure error codes.
     */
    private boolean isErrorCodeRetrySafe(String code) {
        if (code == null) return false;
        Set<String> retrySafeCodes = Set.of(
                "NOT_FOUND",
                "RETRY_SAFE_DECLINE",
                "REQUEST_NEVER_REACHED_PROCESSOR",
                "IDEMPOTENCY_LOOKUP_ABSENT",
                "CONNECT_TIMEOUT",
                "CONNECTION_RESET",
                "CONNECTION_REFUSED",
                "DNS_FAILURE",
                "PSP_TIMEOUT"
        );
        return retrySafeCodes.contains(code.toUpperCase().trim());
    }
}
