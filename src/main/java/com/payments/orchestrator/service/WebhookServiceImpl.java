package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.dto.WebhookRequest;
import com.payments.orchestrator.exception.AttemptSupersededException;
import com.payments.orchestrator.exception.InvalidWebhookSignatureException;
import com.payments.orchestrator.exception.PaymentNotFoundException;
import com.payments.orchestrator.exception.IllegalStateTransitionException;
import com.payments.orchestrator.repository.PaymentAttemptRepository;
import com.payments.orchestrator.repository.PaymentEventRepository;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import com.payments.orchestrator.repository.ProcessedWebhookRepository;
import com.payments.orchestrator.security.SecurityUtils;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class WebhookServiceImpl implements WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookServiceImpl.class);

    @Autowired
    private PaymentIntentRepository intentRepository;

    @Autowired
    private PaymentAttemptRepository attemptRepository;

    @Autowired
    private PaymentEventRepository eventRepository;

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private ProcessedWebhookRepository processedWebhookRepository;

    @Autowired
    private PaymentLifecycleValidator lifecycleValidator;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${orchestrator.psp.psp-a.webhook-secret:secret_psp_a}")
    private String pspASecret;

    @Value("${orchestrator.psp.psp-b.webhook-secret:secret_psp_b}")
    private String pspBSecret;

    @Override
    @Transactional
    public void processWebhook(String provider, String signature, String rawBody, WebhookRequest request) {
        // 1. Establish basic trace ID in case we are running outside the standard filter (e.g., in unit tests)
        if (MDC.get("request_id") == null) {
            MDC.put("request_id", "sys_" + UUID.randomUUID().toString());
            MDC.put("correlation_id", "corr_" + UUID.randomUUID().toString());
            MDC.put("internal_request_id", "int_" + UUID.randomUUID().toString());
        }

        log.info("Incoming webhook payload received from provider: {}, event_id: {}, event_type: {}",
                provider, request.getEventId(), request.getEventType());

        // 2. Validate provider exists
        if (!"PSP_A".equals(provider) && !"PSP_B".equals(provider)) {
            log.error("Rejected webhook with unknown provider identifier: {}", provider);
            throw new PaymentNotFoundException("Unknown provider " + provider);
        }

        // 3. Webhook Signature Verification
        String providerWebhookSecret = "PSP_A".equals(provider) ? pspASecret : pspBSecret;
        if (signature == null || signature.trim().isEmpty()) {
            log.error("[SECURITY AUDIT] Missing signature header X-PSP-Signature for provider: {}", provider);
            throw new InvalidWebhookSignatureException("X-PSP-Signature header is missing");
        }

        if ("invalid_signature".equals(signature)) {
            log.error("[SECURITY AUDIT] Rejecting explicitly mocked invalid signature for provider: {}", provider);
            throw new InvalidWebhookSignatureException("Webhook signature verification failed for provider " + provider);
        }

        try {
            String expectedSignature = SecurityUtils.hmacSha256Base64(providerWebhookSecret, rawBody);
            if (!SecurityUtils.constantTimeEquals(expectedSignature, signature)) {
                log.error("[SECURITY AUDIT] Webhook signature mismatch for provider {}. Provided: {}, Expected: {}",
                        provider, signature, expectedSignature);
                throw new InvalidWebhookSignatureException("Webhook signature verification failed for provider " + provider);
            }
        } catch (InvalidWebhookSignatureException webhookSignatureException) {
            throw webhookSignatureException;
        } catch (Exception signatureComputationException) {
            log.error("Failed to calculate webhook signature.", signatureComputationException);
            throw new InvalidWebhookSignatureException("Webhook signature verification failed for provider " + provider);
        }

        // 4. Deduplication Event Check
        boolean isWebhookAlreadyProcessed = processedWebhookRepository.existsByProviderNameAndProviderEventId(provider, request.getEventId());
        if (isWebhookAlreadyProcessed) {
            log.info("[WEBHOOK IDEMPOTENT] Webhook event_id {} from provider {} has already been processed. Acknowledging with 200 OK.",
                    request.getEventId(), provider);
            return;
        }

        // 5. Correlate payment attempt using the provider reference
        PaymentAttempt attempt = attemptRepository.findByProviderNameAndProviderReference(provider, request.getProviderReference())
                .orElseThrow(() -> {
                    log.error("No payment attempt found for provider_reference '{}'", request.getProviderReference());
                    return new PaymentNotFoundException("No payment attempt found for provider_reference '" + request.getProviderReference() + "'");
                });

        PaymentIntent intent = attempt.getIntent();
        UUID intentId = intent.getIntentId();
        UUID attemptId = attempt.getAttemptId();

        // Establish the correlated intent ID in the MDC logging context
        MDC.put("intent_id", intentId.toString());

        // Validate optional attempt_id correlation
        if (request.getAttemptId() != null && !request.getAttemptId().equals(attemptId)) {
            log.error("Webhook attempt_id mismatch. Webhook: {}, Attempt: {}", request.getAttemptId(), attemptId);
            throw new PaymentNotFoundException("Attempt ID mismatch");
        }

        // Validate optional intent_id correlation
        if (request.getIntentId() != null && !request.getIntentId().equals(intentId)) {
            log.error("Webhook intent_id mismatch. Webhook: {}, Intent: {}", request.getIntentId(), intentId);
            throw new PaymentNotFoundException("Intent ID mismatch");
        }

        // Validate provider matching
        if (!provider.equals(attempt.getProviderName())) {
            log.error("Webhook provider mismatch. Request provider: {}, Attempt provider: {}", provider, attempt.getProviderName());
            throw new PaymentNotFoundException("Provider mismatch");
        }

        // 6. Enforce State Transition & Webhook Precedence Rules
        if (attempt.getStatus() == AttemptStatus.SUPERSEDED) {
            log.error("Webhook rejected: attempt {} is SUPERSEDED.", attemptId);
            throw new AttemptSupersededException("Webhook received for superseded attempt: " + attemptId);
        }

        PaymentStatus targetIntentStatus = mapWebhookStatusToIntentStatus(request.getStatus());
        AttemptStatus targetAttemptStatus = mapWebhookStatusToAttemptStatus(request.getStatus());

        // Rule R-08: Webhook attempts FAILED -> AUTHORIZED transition => 422 ILLEGAL_STATE_TRANSITION
        if (intent.getStatus() == PaymentStatus.FAILED && targetIntentStatus == PaymentStatus.AUTHORIZED) {
            log.error("[SECURITY AUDIT] Attempted FAILED -> AUTHORIZED transition via webhook rejected.");
            throw new IllegalStateTransitionException("Cannot transition payment from FAILED to AUTHORIZED");
        }

        // Rule R-07: Webhook received for already-AUTHORIZED intent => 200 acknowledged, no state change
        if (intent.getStatus() == PaymentStatus.AUTHORIZED) {
            log.info("[WEBHOOK IDEMPOTENT] Webhook received for already-AUTHORIZED intent {}. Ignoring state changes.", intentId);
            saveProcessedWebhook(provider, request.getEventId());
            return;
        }

        // 7. Apply Transitions
        lifecycleValidator.validateAttemptTransition(attempt.getStatus(), targetAttemptStatus);
        attempt.setStatus(targetAttemptStatus);

        if (targetAttemptStatus == AttemptStatus.FAILED) {
            String errCode = "WEBHOOK_DECLINE";
            String errMsg = "Declined via provider webhook";
            if (request.getMetadata() != null) {
                if (request.getMetadata().containsKey("decline_code")) {
                    errCode = String.valueOf(request.getMetadata().get("decline_code"));
                }
                if (request.getMetadata().containsKey("decline_message")) {
                    errMsg = String.valueOf(request.getMetadata().get("decline_message"));
                }
            }
            attempt.setErrorCode(errCode);
            attempt.setErrorMessage(errMsg);
        }

        boolean isIntentTransitionContradictory = lifecycleValidator.checkAndValidateIntentTransition(intent.getStatus(), targetIntentStatus);
        if (!isIntentTransitionContradictory) {
            intent.setStatus(targetIntentStatus);
            intent.setFinalAttemptId(attemptId);
        }

        // Handle superseding other attempts on authorization success
        if (targetAttemptStatus == AttemptStatus.AUTHORIZED) {
            for (PaymentAttempt prior : intent.getAttempts()) {
                if (!prior.getAttemptId().equals(attemptId) && prior.getStatus() != AttemptStatus.SUPERSEDED) {
                    prior.setStatus(AttemptStatus.SUPERSEDED);
                    attemptRepository.save(prior);
                }
            }
        }

        // 8. Persist Webhook Deduplication and States
        saveProcessedWebhook(provider, request.getEventId());

        attemptRepository.save(attempt);
        intentRepository.save(intent);

        // 9. Persist Audit trail events using standard Map to prevent NullPointerException
        PaymentEvent webhookEvent = new PaymentEvent();
        webhookEvent.setEventId(UUID.randomUUID());
        webhookEvent.setIntentId(intentId);
        webhookEvent.setAttemptId(attemptId);
        webhookEvent.setCorrelationId(intent.getCorrelationId());
        webhookEvent.setEventType("WEBHOOK_RECEIVED");

        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("provider", provider);
        eventPayload.put("event_id", request.getEventId());
        eventPayload.put("event_type", request.getEventType());
        eventPayload.put("status", request.getStatus());
        eventPayload.put("intent_id", intentId.toString());
        eventPayload.put("attempt_id", attemptId.toString());
        eventPayload.put("provider_reference", request.getProviderReference());
        if (request.getMetadata() != null) {
            eventPayload.put("metadata", request.getMetadata());
        }
        webhookEvent.setEventPayload(eventPayload);
        eventRepository.save(webhookEvent);

        // 10. Persist Outbox events using standard Map to prevent NullPointerException
        PaymentOutbox outbox = new PaymentOutbox();
        outbox.setOutboxId(UUID.randomUUID());
        outbox.setAggregateId(intentId);
        outbox.setAggregateType("PaymentIntent");
        outbox.setCorrelationId(intent.getCorrelationId());
        outbox.setEventType("WEBHOOK_RECEIVED");

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("intent_id", intentId.toString());
        outboxPayload.put("merchant_id", intent.getMerchantId() != null ? intent.getMerchantId().toString() : null);
        outboxPayload.put("merchant_order_id", intent.getMerchantOrderId());
        outboxPayload.put("status", targetIntentStatus.name());
        outboxPayload.put("amount", intent.getTransactionAmount() != null ? intent.getTransactionAmount().toString() : "0.00");
        outboxPayload.put("currency", intent.getTransactionCurrencyCode());
        outboxPayload.put("provider", provider);
        outboxPayload.put("event_id", request.getEventId());
        outboxPayload.put("provider_reference", request.getProviderReference());
        outbox.setPayload(outboxPayload);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);
        outboxRepository.save(outbox);

        log.info("Webhook processed successfully! Reconciled intent {} to status: {}", intentId, targetIntentStatus);

        // Record metrics
        meterRegistry.counter("payment.webhook.received",
                "provider", provider,
                "status", request.getStatus()
        ).increment();
    }

    private void saveProcessedWebhook(String provider, String eventId) {
        try {
            ProcessedWebhook processed = new ProcessedWebhook();
            processed.setProviderName(provider);
            processed.setProviderEventId(eventId);
            processedWebhookRepository.saveAndFlush(processed);
        } catch (DataIntegrityViolationException concurrentWebhookInsertException) {
            log.info("[WEBHOOK IDEMPOTENT] Concurrent race deduplication caught for event_id {} from provider {}",
                    eventId, provider);
        }
    }

    private PaymentStatus mapWebhookStatusToIntentStatus(String status) {
        if ("AUTHORIZED".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status)) return PaymentStatus.AUTHORIZED;
        if ("FAILED".equalsIgnoreCase(status)) return PaymentStatus.FAILED;
        if ("PENDING".equalsIgnoreCase(status)) return PaymentStatus.PENDING;
        throw new IllegalArgumentException("Unknown webhook status: " + status);
    }

    private AttemptStatus mapWebhookStatusToAttemptStatus(String status) {
        if ("AUTHORIZED".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status)) return AttemptStatus.AUTHORIZED;
        if ("FAILED".equalsIgnoreCase(status)) return AttemptStatus.FAILED;
        if ("PENDING".equalsIgnoreCase(status)) return AttemptStatus.PENDING;
        throw new IllegalArgumentException("Unknown webhook status: " + status);
    }
}
