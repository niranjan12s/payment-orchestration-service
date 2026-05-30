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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class ReconciliationServiceImpl implements ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationServiceImpl.class);

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

    /**
     * Executes the non-transactional external reconciliation calls and delegates state updates
     * to transactional boundaries, ensuring PSP calls never happen inside a database transaction.
     */
    @Override
    public void reconcileIntent(PaymentIntent intent) {
        UUID intentId = intent.getIntentId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Duration age = Duration.between(intent.getCreatedAt(), now);

        log.info("Processing reconciliation checks for PaymentIntent: {}. Status: {}, Age: {} hours.",
                intentId, intent.getStatus(), age.toHours());

        // 1. Escalation Policy >= 48 Hours: Emit Operational Alert
        if (age.toHours() >= 48) {
            log.error("[RECONCILIATION CRITICAL] PaymentIntent {} remains pending unresolved for {} hours. Immediate manual investigation required.",
                    intentId, age.toHours());
            meterRegistry.counter("payment.reconciliation.escalations", "level", "critical_48h").increment();
        }

        // 2. Escalation Policy >= 24 Hours: Emit Operational Alert
        if (age.toHours() >= 24) {
            log.warn("[RECONCILIATION ALERT] PaymentIntent {} has been pending unresolved for {} hours. High priority review required.",
                    intentId, age.toHours());
            meterRegistry.counter("payment.reconciliation.escalations", "level", "alert_24h").increment();
        }

        // 3. Extract the active payment attempt by ID
        UUID activeAttemptId = intent.getActiveAttemptId();
        PaymentAttempt activeAttempt = null;
        if (activeAttemptId != null) {
            activeAttempt = intent.getAttempts().stream()
                    .filter(a -> a.getAttemptId().equals(activeAttemptId))
                    .findFirst()
                    .orElse(null);
        }
        if (activeAttempt == null) {
            activeAttempt = intent.getAttempts().stream()
                    .max(Comparator.comparing(PaymentAttempt::getCreatedAt))
                    .orElse(null);
        }

        if (activeAttempt == null) {
            log.error("Reconciliation failed for PaymentIntent {}: No associated attempts found.", intentId);
            return;
        }

        UUID attemptId = activeAttempt.getAttemptId();
        String provider = activeAttempt.getProviderName();

        // 4. Resolve correct PSP connector
        PspConnector connector = "PSP_A".equalsIgnoreCase(provider) ? pspAConnector : pspBConnector;

        // 5. Query PSP status outside transaction
        PspResponse pspResponse;
        try {
            log.info("Executing external PSP status query against {} for attempt: {}", provider, attemptId);
            pspResponse = connector.queryStatus(activeAttempt);
        } catch (PspTimeoutException e) {
            log.warn("Read timeout occurred querying status from provider {}. Will retain PENDING status.", provider);
            handleReconciliationQueryFailure(intentId, attemptId, "PSP_TIMEOUT", "Connection/Read timeout querying PSP status");
            return;
        } catch (CallNotPermittedException e) {
            log.warn("Circuit Breaker is OPEN for provider {}. Skipping status query on this tick.", provider);
            return;
        } catch (Exception e) {
            log.error("Generic exception querying status from provider {}.", provider, e);
            handleReconciliationQueryFailure(intentId, attemptId, "SYSTEM_ERROR", e.getMessage());
            return;
        }

        // 6. Apply outcomes atomically in a transaction
        if (pspResponse != null) {
            if (pspResponse.getStatus() == PspStatus.SUCCESS || pspResponse.getStatus() == PspStatus.FAILED) {
                resolveOutcome(intentId, attemptId, pspResponse);
            } else {
                log.info("PSP status query for attempt {} returned PENDING. Retaining current states.", attemptId);
                handleReconciliationQueryFailure(intentId, attemptId, "STILL_PENDING", "PSP confirmed transaction remains pending");
            }
        }
    }



    /**
     * Atomically resolves the final state of the intent and attempt from the PSP query response.
     */
    @Transactional
    public void resolveOutcome(UUID intentId, UUID attemptId, PspResponse pspResponse) {
        PaymentIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found: " + intentId));

        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentAttempt not found: " + attemptId));

        // Contradictory check: If intent is already terminal, ignore and audit log
        if (intent.getStatus() == PaymentStatus.AUTHORIZED || intent.getStatus() == PaymentStatus.FAILED) {
            log.error("[SECURITY AUDIT] Contradictory reconciliation update ignored. " +
                    "Reconciliation returned {} but Intent {} is already in terminal status {}",
                    pspResponse.getStatus(), intentId, intent.getStatus());
            return;
        }

        PaymentStatus targetIntentStatus = errorClassifier.getTargetIntentStatus(pspResponse.getStatus());
        AttemptStatus targetAttemptStatus = errorClassifier.getTargetAttemptStatus(pspResponse.getStatus());

        // Validate and apply transitions
        lifecycleValidator.validateAttemptTransition(attempt.getStatus(), targetAttemptStatus);
        attempt.setStatus(targetAttemptStatus);

        if (pspResponse.getStatus() == PspStatus.FAILED) {
            attempt.setErrorCode(pspResponse.getErrorCode() != null ? pspResponse.getErrorCode() : "RECONCILIATION_DECLINE");
            attempt.setErrorMessage(pspResponse.getErrorMessage() != null ? pspResponse.getErrorMessage() : "Transaction declined by provider");
        } else if (pspResponse.getProviderReference() != null) {
            attempt.setProviderReference(pspResponse.getProviderReference());
        }

        // Sequential Intent Transitions
        lifecycleValidator.checkAndValidateIntentTransition(intent.getStatus(), targetIntentStatus);
        intent.setStatus(targetIntentStatus);
        intent.setActiveAttemptId(attemptId);

        // Persist outcome event (RECONCILIATION_RESOLVED)
        PaymentEvent event = new PaymentEvent();
        event.setEventId(UUID.randomUUID());
        event.setIntentId(intentId);
        event.setAttemptId(attemptId);
        event.setCorrelationId(intent.getCorrelationId());
        event.setEventType("RECONCILIATION_RESOLVED");

        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("merchant_id", intent.getMerchantId().toString());
        eventPayload.put("merchant_order_id", intent.getMerchantOrderId());
        eventPayload.put("status", targetIntentStatus.name());
        eventPayload.put("provider_name", attempt.getProviderName());
        if (attempt.getProviderReference() != null) {
            eventPayload.put("provider_reference", attempt.getProviderReference());
        }
        if (attempt.getErrorCode() != null) {
            eventPayload.put("error_code", attempt.getErrorCode());
            eventPayload.put("error_message", attempt.getErrorMessage());
        }
        event.setEventPayload(eventPayload);

        // Persist Outbox event (RECONCILIATION_RESOLVED)
        PaymentOutbox outbox = new PaymentOutbox();
        outbox.setOutboxId(UUID.randomUUID());
        outbox.setAggregateId(intentId);
        outbox.setAggregateType("PaymentIntent");
        outbox.setCorrelationId(intent.getCorrelationId());
        outbox.setEventType("RECONCILIATION_RESOLVED");

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("intent_id", intentId.toString());
        outboxPayload.put("merchant_id", intent.getMerchantId().toString());
        outboxPayload.put("merchant_order_id", intent.getMerchantOrderId());
        outboxPayload.put("status", targetIntentStatus.name());
        outboxPayload.put("amount", intent.getTransactionAmount().toString());
        outboxPayload.put("currency", intent.getTransactionCurrencyCode());
        if (attempt.getProviderReference() != null) {
            outboxPayload.put("provider_reference", attempt.getProviderReference());
        }
        outbox.setPayload(outboxPayload);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);
        outbox.setSourceEventId(event.getEventId());

        intentRepository.save(intent);
        attemptRepository.save(attempt);
        eventRepository.save(event);
        outboxRepository.save(outbox);

        log.info("Successfully reconciled PaymentIntent {} to status: {}", intentId, targetIntentStatus);

        // Record metrics
        meterRegistry.counter("payment.reconciliation.resolutions",
                "status", targetIntentStatus.name(),
                "provider", attempt.getProviderName()
        ).increment();
    }

    /**
     * Updates attempt retry metadata on query failures.
     */
    @Transactional
    public void handleReconciliationQueryFailure(UUID intentId, UUID attemptId, String errorCode, String errorMsg) {
        PaymentAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt != null) {
            attempt.setRetryCount(attempt.getRetryCount() + 1);
            attempt.setErrorCode(errorCode);
            attempt.setErrorMessage(errorMsg);
            attemptRepository.save(attempt);
        }

        PaymentIntent intent = intentRepository.findById(intentId).orElse(null);
        if (intent != null) {
            // Update updated_at to ensure backoff check is computed correctly
            intent.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            intentRepository.save(intent);
        }
    }
}
