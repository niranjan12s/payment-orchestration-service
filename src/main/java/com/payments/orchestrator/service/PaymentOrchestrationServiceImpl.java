package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.dto.CreatePaymentRequest;
import com.payments.orchestrator.exception.DuplicateMerchantOrderException;
import com.payments.orchestrator.repository.PaymentAttemptRepository;
import com.payments.orchestrator.repository.PaymentEventRepository;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentOrchestrationServiceImpl implements PaymentOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrationServiceImpl.class);

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
    private PspErrorClassifier errorClassifier;

    @Override
    @Transactional
    public PaymentIntent createInitialPaymentState(CreatePaymentRequest request, String idempotencyKey) {
        log.info("Initiating core persistence transaction flow for merchant_order_id: {}", request.getMerchantOrderId());

        // 1. Merchant Order Uniqueness Check
        Optional<PaymentIntent> existing = intentRepository.findByMerchantIdAndMerchantOrderId(
                request.getMerchantId(), request.getMerchantOrderId()
        );
        if (existing.isPresent()) {
            log.warn("Merchant order unique constraint violation. Merchant ID: {}, Order ID: {}",
                    request.getMerchantId(), request.getMerchantOrderId());
            throw new DuplicateMerchantOrderException(request.getMerchantId().toString(), request.getMerchantOrderId());
        }

        // 2. Trace Context Propagation (MDC fallback to generated defaults)
        String requestId = MDC.get("request_id");
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = "sys_" + UUID.randomUUID().toString();
        }

        String correlationId = MDC.get("correlation_id");
        if (correlationId == null || correlationId.trim().isEmpty()) {
            correlationId = "corr_" + UUID.randomUUID().toString();
        }

        // 3. PSP Routing logic (CARD -> PSP_A, UPI -> PSP_B)
        String providerName;
        String paymentMethodStr = request.getPaymentMethodType();
        if ("CARD".equalsIgnoreCase(paymentMethodStr)) {
            providerName = "PSP_A";
        } else if ("UPI".equalsIgnoreCase(paymentMethodStr)) {
            providerName = "PSP_B";
        } else {
            log.error("Invalid payment method type: {}", paymentMethodStr);
            throw new IllegalArgumentException("Unsupported payment method type: " + paymentMethodStr);
        }

        // Generate entity UUIDs up-front to link relations deterministically
        UUID intentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        // 4. Create and map PaymentIntent
        PaymentIntent intent = new PaymentIntent();
        intent.setIntentId(intentId);
        intent.setMerchantId(request.getMerchantId());
        intent.setMerchantOrderId(request.getMerchantOrderId());
        intent.setCorrelationId(correlationId);
        intent.setRequestId(requestId);
        intent.setIdempotencyKey(idempotencyKey);
        
        intent.setTransactionCurrencyCode(request.getTransactionAmount().getCurrencyCode());
        intent.setTransactionAmount(request.getTransactionAmount().getAmount());
        
        intent.setSettlementCurrencyCode(request.getSettlementAmount().getCurrencyCode());
        intent.setSettlementAmount(request.getSettlementAmount().getAmount());
        intent.setStatus(PaymentStatus.CREATED);
        intent.setActiveAttemptId(attemptId);

        // 5. Create and map PaymentAttempt (associated bidirectionally)
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setAttemptId(attemptId);
        attempt.setCorrelationId(correlationId);
        attempt.setRequestId(requestId);
        attempt.setProviderName(providerName);
        attempt.setPaymentMethodType(PaymentMethodType.valueOf(paymentMethodStr.toUpperCase()));
        attempt.setPaymentTokenReference(request.getPaymentTokenReference());
        attempt.setStatus(AttemptStatus.PROCESSING);
        attempt.setRetryCount(0);

        intent.addAttempt(attempt);

        // Save all records in a single database transaction (Cascade saves the linked attempt)
        PaymentIntent savedIntent = intentRepository.saveAndFlush(intent);
        
        UUID dbIntentId = savedIntent.getIntentId();
        UUID dbAttemptId = savedIntent.getAttempts().get(0).getAttemptId();

        // Also update activeAttemptId to the actual dbAttemptId
        savedIntent.setActiveAttemptId(dbAttemptId);
        savedIntent = intentRepository.saveAndFlush(savedIntent);

        // Force lazy collection load while session is active
        if (savedIntent.getAttempts() != null) {
            savedIntent.getAttempts().size();
        }

        // 6. Create and map PaymentEvent (Audit Trail)
        PaymentEvent event = new PaymentEvent();
        event.setEventId(UUID.randomUUID());
        event.setIntentId(dbIntentId);
        event.setAttemptId(dbAttemptId);
        event.setCorrelationId(correlationId);
        event.setEventType("PAYMENT_INITIATED");

        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("merchant_id", request.getMerchantId().toString());
        eventPayload.put("merchant_order_id", request.getMerchantOrderId());
        eventPayload.put("transaction_amount", request.getTransactionAmount().getAmount().toString());
        eventPayload.put("transaction_currency", request.getTransactionAmount().getCurrencyCode());
        eventPayload.put("provider_name", providerName);
        eventPayload.put("payment_method_type", paymentMethodStr);
        event.setEventPayload(eventPayload);

        // 7. Create and map PaymentOutbox event
        PaymentOutbox outbox = new PaymentOutbox();
        outbox.setOutboxId(UUID.randomUUID());
        outbox.setAggregateId(dbIntentId);
        outbox.setAggregateType("PaymentIntent");
        outbox.setCorrelationId(correlationId);
        outbox.setEventType("PAYMENT_CREATED");

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("intent_id", dbIntentId.toString());
        outboxPayload.put("merchant_id", request.getMerchantId().toString());
        outboxPayload.put("merchant_order_id", request.getMerchantOrderId());
        outboxPayload.put("status", "CREATED");
        outboxPayload.put("amount", request.getTransactionAmount().getAmount().toString());
        outboxPayload.put("currency", request.getTransactionAmount().getCurrencyCode());
        outbox.setPayload(outboxPayload);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);
        outbox.setSourceEventId(event.getEventId());

        eventRepository.save(event);
        outboxRepository.save(outbox);

        log.info("Successfully committed payment persistence state. intent_id: {}, attempt_id: {}", dbIntentId, dbAttemptId);
        return savedIntent;
    }

    @Override
    @Transactional
    public PaymentIntent updatePaymentOutcome(UUID intentId, UUID attemptId, com.payments.orchestrator.dto.PspResponse pspResponse) {
        log.info("Updating payment outcome for intentId: {}, attemptId: {}, status: {}", intentId, attemptId, pspResponse.getStatus());

        PaymentIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found: " + intentId));

        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentAttempt not found: " + attemptId));

        // 1. Resolve target statuses
        PaymentStatus targetIntentStatus = errorClassifier.getTargetIntentStatus(pspResponse.getStatus());
        AttemptStatus targetAttemptStatus = errorClassifier.getTargetAttemptStatus(pspResponse.getStatus());

        // 2. Transition attempt status
        lifecycleValidator.validateAttemptTransition(attempt.getStatus(), targetAttemptStatus);
        attempt.setStatus(targetAttemptStatus);

        // Map errors if failed or pending
        if (pspResponse.getStatus() == com.payments.orchestrator.dto.PspStatus.FAILED || pspResponse.getStatus() == com.payments.orchestrator.dto.PspStatus.PENDING) {
            attempt.setErrorCode(pspResponse.getErrorCode());
            attempt.setErrorMessage(pspResponse.getErrorMessage());
        }

        // Map provider reference if available
        if (pspResponse.getProviderReference() != null) {
            attempt.setProviderReference(pspResponse.getProviderReference());
        }

        // 3. Sequential Intent Transitions (CREATED -> PROCESSING -> TARGET)
        // If current state in DB is CREATED, transition it to PROCESSING first to satisfy state matrix
        if (intent.getStatus() == PaymentStatus.CREATED) {
            lifecycleValidator.checkAndValidateIntentTransition(intent.getStatus(), PaymentStatus.PROCESSING);
            intent.setStatus(PaymentStatus.PROCESSING);
        }

        // Now validate and transition from PROCESSING to target status
        lifecycleValidator.checkAndValidateIntentTransition(intent.getStatus(), targetIntentStatus);
        intent.setStatus(targetIntentStatus);

        // 4. Create and persist PaymentEvent (outcome audit)
        PaymentEvent event = new PaymentEvent();
        event.setEventId(UUID.randomUUID());
        event.setIntentId(intentId);
        event.setAttemptId(attemptId);
        event.setCorrelationId(intent.getCorrelationId());

        String eventType = switch (targetIntentStatus) {
            case AUTHORIZED -> "PAYMENT_AUTHORIZED";
            case FAILED -> "PAYMENT_FAILED";
            case PENDING -> "PAYMENT_PENDING";
            default -> "PAYMENT_UPDATED";
        };
        event.setEventType(eventType);

        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("merchant_id", intent.getMerchantId().toString());
        eventPayload.put("merchant_order_id", intent.getMerchantOrderId());
        eventPayload.put("status", targetIntentStatus.name());
        if (pspResponse.getProviderReference() != null) {
            eventPayload.put("provider_reference", pspResponse.getProviderReference());
        }
        if (pspResponse.getErrorCode() != null) {
            eventPayload.put("error_code", pspResponse.getErrorCode());
            eventPayload.put("error_message", pspResponse.getErrorMessage());
        }
        event.setEventPayload(eventPayload);

        // 5. Create and persist Outbox event
        PaymentOutbox outbox = new PaymentOutbox();
        outbox.setOutboxId(UUID.randomUUID());
        outbox.setAggregateId(intentId);
        outbox.setAggregateType("PaymentIntent");
        outbox.setCorrelationId(intent.getCorrelationId());
        outbox.setEventType(eventType);

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("intent_id", intentId.toString());
        outboxPayload.put("merchant_id", intent.getMerchantId().toString());
        outboxPayload.put("merchant_order_id", intent.getMerchantOrderId());
        outboxPayload.put("status", targetIntentStatus.name());
        outboxPayload.put("amount", intent.getTransactionAmount().toString());
        outboxPayload.put("currency", intent.getTransactionCurrencyCode());
        if (pspResponse.getProviderReference() != null) {
            outboxPayload.put("provider_reference", pspResponse.getProviderReference());
        }
        outbox.setPayload(outboxPayload);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);
        outbox.setSourceEventId(event.getEventId());

        // Save everything
        PaymentIntent savedIntent = intentRepository.save(intent);
        attemptRepository.save(attempt);
        eventRepository.save(event);
        outboxRepository.save(outbox);

        // Force lazy collection load while session is still active
        if (savedIntent.getAttempts() != null) {
            savedIntent.getAttempts().size();
        }

        log.info("Outcome updated successfully for payment intent: {}, final status: {}", intentId, targetIntentStatus);
        return savedIntent;
    }

    @Override
    @Transactional
    public PaymentAttempt createFallbackAttempt(UUID intentId, UUID primaryAttemptId, String fallbackProvider) {
        log.info("Creating fallback attempt for intentId: {}, primaryAttemptId: {}, fallbackProvider: {}",
                intentId, primaryAttemptId, fallbackProvider);

        PaymentIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found: " + intentId));

        PaymentAttempt primaryAttempt = attemptRepository.findById(primaryAttemptId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentAttempt not found: " + primaryAttemptId));

        // Mark primary attempt as FAILED because the provider was blocked/unavailable
        primaryAttempt.setStatus(AttemptStatus.FAILED);
        primaryAttempt.setErrorCode("PROVIDER_BLOCKED");
        primaryAttempt.setErrorMessage("Primary provider blocked by circuit breaker or failed to respond");
        attemptRepository.saveAndFlush(primaryAttempt);

        // Create second attempt
        PaymentAttempt secondAttempt = new PaymentAttempt();
        secondAttempt.setCorrelationId(intent.getCorrelationId());
        secondAttempt.setRequestId(intent.getRequestId());
        secondAttempt.setProviderName(fallbackProvider);
        
        secondAttempt.setPaymentMethodType(primaryAttempt.getPaymentMethodType());
        secondAttempt.setPaymentTokenReference(primaryAttempt.getPaymentTokenReference());
        secondAttempt.setStatus(AttemptStatus.PROCESSING);
        secondAttempt.setRetryCount(primaryAttempt.getRetryCount() + 1);

        intent.addAttempt(secondAttempt);

        PaymentIntent savedIntent = intentRepository.saveAndFlush(intent); // Cascades saves secondAttempt and generates UUID
        
        // Find the newly saved attempt to get its generated ID
        PaymentAttempt savedAttempt = savedIntent.getAttempts().stream()
                .filter(a -> fallbackProvider.equals(a.getProviderName()) && a.getStatus() == AttemptStatus.PROCESSING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Saved fallback attempt not found"));

        savedIntent.setActiveAttemptId(savedAttempt.getAttemptId());
        intentRepository.saveAndFlush(savedIntent);

        // 3. Create fallback transition audit event
        PaymentEvent event = new PaymentEvent();
        event.setEventId(UUID.randomUUID());
        event.setIntentId(intentId);
        event.setAttemptId(savedAttempt.getAttemptId());
        event.setCorrelationId(intent.getCorrelationId());
        event.setEventType("PAYMENT_ATTEMPT_FAILOVER");
        event.setEventPayload(Map.of(
                "primary_attempt_id", primaryAttemptId.toString(),
                "fallback_provider", fallbackProvider
        ));
        eventRepository.save(event);

        log.info("Successfully persisted fallback attempt: {}", savedAttempt.getAttemptId());
        return savedAttempt;
    }
}
