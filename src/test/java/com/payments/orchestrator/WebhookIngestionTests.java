package com.payments.orchestrator;

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
import com.payments.orchestrator.service.PaymentLifecycleValidator;
import com.payments.orchestrator.service.WebhookServiceImpl;
import com.payments.orchestrator.security.SecurityUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WebhookIngestionTests {

    @InjectMocks
    private WebhookServiceImpl webhookService;

    @Mock
    private PaymentIntentRepository intentRepository;

    @Mock
    private PaymentAttemptRepository attemptRepository;

    @Mock
    private PaymentEventRepository eventRepository;

    @Mock
    private PaymentOutboxRepository outboxRepository;

    @Mock
    private ProcessedWebhookRepository processedWebhookRepository;

    @Mock
    private PaymentLifecycleValidator lifecycleValidator;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter mockCounter;

    private UUID merchantId;
    private PaymentIntent pendingIntent;
    private PaymentAttempt activeAttempt;
    private String webhookSecret = "secret_psp_a";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Inject configuration secrets
        ReflectionTestUtils.setField(webhookService, "pspASecret", webhookSecret);
        ReflectionTestUtils.setField(webhookService, "pspBSecret", "secret_psp_b");

        // Setup MeterRegistry
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mockCounter);

        merchantId = UUID.randomUUID();

        // Create standard PENDING intent and active attempt
        pendingIntent = new PaymentIntent();
        pendingIntent.setIntentId(UUID.randomUUID());
        pendingIntent.setMerchantId(merchantId);
        pendingIntent.setMerchantOrderId("ORDER-WH-123");
        pendingIntent.setCorrelationId("corr-wh-id");
        pendingIntent.setRequestId("req-wh-id");
        pendingIntent.setStatus(PaymentStatus.PENDING);
        pendingIntent.setTransactionCurrencyCode("USD");
        pendingIntent.setTransactionAmount(new BigDecimal("100.00"));
        pendingIntent.setSettlementCurrencyCode("USD");
        pendingIntent.setSettlementAmount(new BigDecimal("100.00"));
        pendingIntent.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        pendingIntent.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        activeAttempt = new PaymentAttempt();
        activeAttempt.setAttemptId(UUID.randomUUID());
        activeAttempt.setIntent(pendingIntent);
        activeAttempt.setProviderName("PSP_A");
        activeAttempt.setPaymentMethodType(PaymentMethodType.CARD);
        activeAttempt.setPaymentTokenReference("tok_abc123");
        activeAttempt.setStatus(AttemptStatus.PENDING);
        activeAttempt.setProviderReference("provider_tx_9f8e7d6c");
        activeAttempt.setRetryCount(0);
        activeAttempt.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        activeAttempt.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        pendingIntent.addAttempt(activeAttempt);
    }

    @Test
    void testValidSignatureIngestionSuccess() throws Exception {
        String rawBody = "{\"event_id\":\"evt_wh_001\",\"event_type\":\"PAYMENT_AUTHORIZED\",\"provider_reference\":\"provider_tx_9f8e7d6c\",\"status\":\"AUTHORIZED\",\"timestamp\":\"2026-05-27T12:00:05Z\"}";
        String signature = SecurityUtils.hmacSha256Base64(webhookSecret, rawBody);

        WebhookRequest request = new WebhookRequest();
        request.setEventId("evt_wh_001");
        request.setEventType("PAYMENT_AUTHORIZED");
        request.setProviderReference("provider_tx_9f8e7d6c");
        request.setStatus("AUTHORIZED");
        request.setTimestamp(OffsetDateTime.parse("2026-05-27T12:00:05Z"));

        when(processedWebhookRepository.existsByProviderNameAndProviderEventId("PSP_A", "evt_wh_001")).thenReturn(false);
        when(attemptRepository.findByProviderReference("provider_tx_9f8e7d6c")).thenReturn(Optional.of(activeAttempt));

        // Trigger processing
        webhookService.processWebhook("PSP_A", signature, rawBody, request);

        // Verify state transitions
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(activeAttempt.getStatus()).isEqualTo(AttemptStatus.AUTHORIZED);
        assertThat(pendingIntent.getFinalAttemptId()).isEqualTo(activeAttempt.getAttemptId());

        // Verify deduplication entry and audit records
        verify(processedWebhookRepository, times(1)).saveAndFlush(any(ProcessedWebhook.class));
        verify(eventRepository, times(1)).save(argThat(event -> 
                "WEBHOOK_RECEIVED".equals(event.getEventType()) && 
                event.getIntentId().equals(pendingIntent.getIntentId())
        ));
        verify(outboxRepository, times(1)).save(argThat(outbox -> 
                "WEBHOOK_RECEIVED".equals(outbox.getEventType()) && 
                "AUTHORIZED".equals(outbox.getPayload().get("status"))
        ));
    }

    @Test
    void testInvalidSignatureThrowsException() {
        String rawBody = "{\"event_id\":\"evt_wh_001\",\"status\":\"AUTHORIZED\"}";
        
        WebhookRequest request = new WebhookRequest();
        request.setEventId("evt_wh_001");
        request.setProviderReference("provider_tx_9f8e7d6c");
        request.setStatus("AUTHORIZED");

        // Asserts explicit mocked invalid signature throws
        assertThatThrownBy(() -> webhookService.processWebhook("PSP_A", "invalid_signature", rawBody, request))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .hasMessageContaining("Webhook signature verification failed");

        // Asserts incorrect calculated hmac signature throws
        assertThatThrownBy(() -> webhookService.processWebhook("PSP_A", "incorrect_signature_val", rawBody, request))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .hasMessageContaining("Webhook signature verification failed");
    }

    @Test
    void testIdempotentDeduplicationForDuplicateWebhook() {
        WebhookRequest request = new WebhookRequest();
        request.setEventId("evt_dup_999");
        request.setProviderReference("provider_tx_9f8e7d6c");
        request.setStatus("AUTHORIZED");

        // Setup already processed webhook duplicate check
        when(processedWebhookRepository.existsByProviderNameAndProviderEventId("PSP_A", "evt_dup_999")).thenReturn(true);

        // Trigger processing (does not require a valid hmac signature because deduplication is checked prior or matches mock bypass)
        // Wait, to keep tests realistic, we pass a valid mocked signature by matching calculated one or we can mock existsByProviderNameAndProviderEventId
        // Wait, in code processWebhook does signature validation *before* deduplication check.
        // So we need a valid signature in this test to pass signature check and reach the deduplication check!
        String rawBody = "body";
        String tempSignature = "";
        try {
            tempSignature = SecurityUtils.hmacSha256Base64(webhookSecret, rawBody);
        } catch (Exception e) {}
        final String signature = tempSignature;

        webhookService.processWebhook("PSP_A", signature, rawBody, request);

        // Verify that attempt and intent repositories are NEVER touched (idempotent no-op)
        verifyNoInteractions(attemptRepository);
        verifyNoInteractions(intentRepository);
    }

    @Test
    void testCorrelationValidationThrowsNotFound() {
        String rawBody = "body";
        String tempSignature = "";
        try { tempSignature = SecurityUtils.hmacSha256Base64(webhookSecret, rawBody); } catch (Exception e) {}
        final String signature = tempSignature;

        WebhookRequest request = new WebhookRequest();
        request.setEventId("evt_wh_002");
        request.setProviderReference("provider_tx_unknown");
        request.setStatus("AUTHORIZED");

        // Mock attempt lookup returning empty Optional
        when(processedWebhookRepository.existsByProviderNameAndProviderEventId("PSP_A", "evt_wh_002")).thenReturn(false);
        when(attemptRepository.findByProviderReference("provider_tx_unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> webhookService.processWebhook("PSP_A", signature, rawBody, request))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining("No payment attempt found");
    }

    @Test
    void testWebhookRejectionForSupersededAttempt() {
        String rawBody = "body";
        String tempSignature = "";
        try { tempSignature = SecurityUtils.hmacSha256Base64(webhookSecret, rawBody); } catch (Exception e) {}
        final String signature = tempSignature;

        WebhookRequest request = new WebhookRequest();
        request.setEventId("evt_wh_003");
        request.setProviderReference("provider_tx_9f8e7d6c");
        request.setStatus("AUTHORIZED");

        // Set attempt to SUPERSEDED
        activeAttempt.setStatus(AttemptStatus.SUPERSEDED);

        when(processedWebhookRepository.existsByProviderNameAndProviderEventId("PSP_A", "evt_wh_003")).thenReturn(false);
        when(attemptRepository.findByProviderReference("provider_tx_9f8e7d6c")).thenReturn(Optional.of(activeAttempt));

        assertThatThrownBy(() -> webhookService.processWebhook("PSP_A", signature, rawBody, request))
                .isInstanceOf(AttemptSupersededException.class)
                .hasMessageContaining("superseded attempt");
    }

    @Test
    void testIllegalTransitionFromFailedToAuthorizedThrows() {
        String rawBody = "body";
        String tempSignature = "";
        try { tempSignature = SecurityUtils.hmacSha256Base64(webhookSecret, rawBody); } catch (Exception e) {}
        final String signature = tempSignature;

        WebhookRequest request = new WebhookRequest();
        request.setEventId("evt_wh_004");
        request.setProviderReference("provider_tx_9f8e7d6c");
        request.setStatus("AUTHORIZED"); // Attempt to AUTHORIZE

        // Pre-establish Intent to FAILED terminal status
        pendingIntent.setStatus(PaymentStatus.FAILED);

        when(processedWebhookRepository.existsByProviderNameAndProviderEventId("PSP_A", "evt_wh_004")).thenReturn(false);
        when(attemptRepository.findByProviderReference("provider_tx_9f8e7d6c")).thenReturn(Optional.of(activeAttempt));

        assertThatThrownBy(() -> webhookService.processWebhook("PSP_A", signature, rawBody, request))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("Cannot transition payment from FAILED to AUTHORIZED");
    }

    @Test
    void testIdempotentNoOpForAlreadyAuthorizedIntent() {
        String rawBody = "body";
        String signature = "";
        try { signature = SecurityUtils.hmacSha256Base64(webhookSecret, rawBody); } catch (Exception e) {}

        WebhookRequest request = new WebhookRequest();
        request.setEventId("evt_wh_005");
        request.setProviderReference("provider_tx_9f8e7d6c");
        request.setStatus("FAILED"); // Try contradictory fail

        // Pre-establish Intent to AUTHORIZED
        pendingIntent.setStatus(PaymentStatus.AUTHORIZED);

        when(processedWebhookRepository.existsByProviderNameAndProviderEventId("PSP_A", "evt_wh_005")).thenReturn(false);
        when(attemptRepository.findByProviderReference("provider_tx_9f8e7d6c")).thenReturn(Optional.of(activeAttempt));

        // Trigger processing
        webhookService.processWebhook("PSP_A", signature, rawBody, request);

        // Verify status remains AUTHORIZED, but deduplication record IS saved
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        verify(processedWebhookRepository, times(1)).saveAndFlush(any(ProcessedWebhook.class));
        
        // Assert attempt and intent repositories are NEVER saved/modified
        verify(attemptRepository, never()).save(any(PaymentAttempt.class));
        verify(intentRepository, never()).save(any(PaymentIntent.class));
    }
}
