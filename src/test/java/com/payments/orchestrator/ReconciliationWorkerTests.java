package com.payments.orchestrator;

import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.dto.PspResponse;
import com.payments.orchestrator.dto.PspStatus;
import com.payments.orchestrator.exception.PspTimeoutException;
import com.payments.orchestrator.repository.PaymentAttemptRepository;
import com.payments.orchestrator.repository.PaymentEventRepository;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import com.payments.orchestrator.service.*;
import com.payments.orchestrator.worker.ReconciliationWorker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReconciliationWorkerTests {

    @InjectMocks
    private ReconciliationServiceImpl reconciliationService;

    @InjectMocks
    private ReconciliationWorker reconciliationWorker;

    @Mock
    private PaymentIntentRepository intentRepository;

    @Mock
    private PaymentAttemptRepository attemptRepository;

    @Mock
    private PaymentEventRepository eventRepository;

    @Mock
    private PaymentOutboxRepository outboxRepository;

    @Mock
    private PaymentLifecycleValidator lifecycleValidator;

    @Mock
    private PspAConnector pspAConnector;

    @Mock
    private PspBConnector pspBConnector;

    @Mock
    private PspErrorClassifier errorClassifier;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter mockCounter;

    @Mock
    private Timer mockTimer;

    private UUID merchantId;
    private PaymentIntent pendingIntent;
    private PaymentAttempt activeAttempt;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        merchantId = UUID.randomUUID();

        // Setup MeterRegistry mocks
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mockCounter);
        when(meterRegistry.timer(anyString(), any(String[].class))).thenReturn(mockTimer);

        // Setup default mocks for validators and mappers
        when(errorClassifier.getTargetIntentStatus(PspStatus.SUCCESS)).thenReturn(PaymentStatus.AUTHORIZED);
        when(errorClassifier.getTargetAttemptStatus(PspStatus.SUCCESS)).thenReturn(AttemptStatus.AUTHORIZED);
        when(errorClassifier.getTargetIntentStatus(PspStatus.FAILED)).thenReturn(PaymentStatus.FAILED);
        when(errorClassifier.getTargetAttemptStatus(PspStatus.FAILED)).thenReturn(AttemptStatus.FAILED);

        // Set up a mock pending payment intent
        pendingIntent = new PaymentIntent();
        pendingIntent.setIntentId(UUID.randomUUID());
        pendingIntent.setMerchantId(merchantId);
        pendingIntent.setMerchantOrderId("ORDER-RECON-111");
        pendingIntent.setCorrelationId("corr-recon-id");
        pendingIntent.setRequestId("req-recon-id");
        pendingIntent.setStatus(PaymentStatus.PENDING);
        pendingIntent.setTransactionCurrencyCode("USD");
        pendingIntent.setTransactionAmount(new BigDecimal("150.00"));
        pendingIntent.setSettlementCurrencyCode("USD");
        pendingIntent.setSettlementAmount(new BigDecimal("150.00"));
        pendingIntent.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        pendingIntent.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));

        // Associated active processing attempt
        activeAttempt = new PaymentAttempt();
        activeAttempt.setAttemptId(UUID.randomUUID());
        activeAttempt.setIntent(pendingIntent);
        activeAttempt.setProviderName("PSP_A");
        activeAttempt.setPaymentMethodType(PaymentMethodType.CARD);
        activeAttempt.setPaymentTokenReference("tok_abc123");
        activeAttempt.setStatus(AttemptStatus.PENDING);
        activeAttempt.setRetryCount(0);
        activeAttempt.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        activeAttempt.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));

        pendingIntent.addAttempt(activeAttempt);
    }

    @Test
    void testLockedPollingAndRetryBackoffCalculations() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 1. Intent ready for check (updated 10 minutes ago, retryCount=0 => backoff is 60s)
        PaymentIntent eligibleIntent = new PaymentIntent();
        eligibleIntent.setIntentId(UUID.randomUUID());
        eligibleIntent.setStatus(PaymentStatus.PENDING);
        eligibleIntent.setCreatedAt(now.minusMinutes(10));
        eligibleIntent.setUpdatedAt(now.minusMinutes(10));
        PaymentAttempt attempt1 = new PaymentAttempt();
        attempt1.setStatus(AttemptStatus.PENDING);
        attempt1.setRetryCount(0);
        attempt1.setCreatedAt(now.minusMinutes(10));
        attempt1.setUpdatedAt(now.minusMinutes(10));
        eligibleIntent.addAttempt(attempt1);

        // 2. Intent NOT ready (updated 30 seconds ago, retryCount=1 => backoff is 120s)
        PaymentIntent backoffIntent = new PaymentIntent();
        backoffIntent.setIntentId(UUID.randomUUID());
        backoffIntent.setStatus(PaymentStatus.PENDING);
        backoffIntent.setCreatedAt(now.minusMinutes(5));
        backoffIntent.setUpdatedAt(now.minusSeconds(30));
        PaymentAttempt attempt2 = new PaymentAttempt();
        attempt2.setStatus(AttemptStatus.PENDING);
        attempt2.setRetryCount(1); // Backoff required = 120 seconds
        attempt2.setCreatedAt(now.minusMinutes(5));
        attempt2.setUpdatedAt(now.minusSeconds(30));
        backoffIntent.addAttempt(attempt2);

        List<PaymentIntent> dbBatch = Arrays.asList(eligibleIntent, backoffIntent);
        when(intentRepository.findTopPendingForReconciliation(eq(PaymentStatus.PENDING), any(Pageable.class)))
                .thenReturn(dbBatch);

        // Trigger batch selection
        List<PaymentIntent> eligibleBatch = reconciliationWorker.lockAndSelectReconciliationBatch();

        // Assert only intent 1 was picked up and updated, while intent 2 was safely backoff-deferred
        assertThat(eligibleBatch).hasSize(1);
        assertThat(eligibleBatch.get(0).getIntentId()).isEqualTo(eligibleIntent.getIntentId());

        verify(intentRepository, times(1)).save(eligibleIntent);
        verify(intentRepository, never()).save(backoffIntent);
    }

    @Test
    void testSuccessfulReconciliationTransition() {
        PspResponse successResponse = new PspResponse(PspStatus.SUCCESS, "psp_ref_authorized_999", null, null);
        when(pspAConnector.queryStatus(activeAttempt)).thenReturn(successResponse);
        
        when(intentRepository.findById(pendingIntent.getIntentId())).thenReturn(Optional.of(pendingIntent));
        when(attemptRepository.findById(activeAttempt.getAttemptId())).thenReturn(Optional.of(activeAttempt));

        // Trigger reconciliation
        reconciliationService.reconcileIntent(pendingIntent);

        // Assert Intent and Attempt transitioned correctly
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(activeAttempt.getStatus()).isEqualTo(AttemptStatus.AUTHORIZED);
        assertThat(activeAttempt.getProviderReference()).isEqualTo("psp_ref_authorized_999");
        assertThat(pendingIntent.getActiveAttemptId()).isEqualTo(activeAttempt.getAttemptId());

        // Verify audit structures were written
        verify(eventRepository, times(1)).save(argThat(event -> 
                "RECONCILIATION_RESOLVED".equals(event.getEventType()) && 
                event.getIntentId().equals(pendingIntent.getIntentId())
        ));

        verify(outboxRepository, times(1)).save(argThat(outbox -> 
                "RECONCILIATION_RESOLVED".equals(outbox.getEventType()) && 
                "AUTHORIZED".equals(outbox.getPayload().get("status"))
        ));

        // Verify Metric
        verify(meterRegistry, times(1)).counter(
                eq("payment.reconciliation.resolutions"),
                eq("status"), eq("AUTHORIZED"),
                eq("provider"), eq("PSP_A")
        );
    }

    @Test
    void testDefinitiveFailureReconciliationTransition() {
        PspResponse failedResponse = new PspResponse(PspStatus.FAILED, null, "INSUFFICIENT_FUNDS", "Declined due to funds shortage");
        when(pspAConnector.queryStatus(activeAttempt)).thenReturn(failedResponse);

        when(intentRepository.findById(pendingIntent.getIntentId())).thenReturn(Optional.of(pendingIntent));
        when(attemptRepository.findById(activeAttempt.getAttemptId())).thenReturn(Optional.of(activeAttempt));

        // Trigger reconciliation
        reconciliationService.reconcileIntent(pendingIntent);

        // Assert FAILED state transitions
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(activeAttempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(activeAttempt.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(activeAttempt.getErrorMessage()).isEqualTo("Declined due to funds shortage");

        // Verify outbox outcome mapped
        verify(outboxRepository, times(1)).save(argThat(outbox -> 
                "RECONCILIATION_RESOLVED".equals(outbox.getEventType()) && 
                "FAILED".equals(outbox.getPayload().get("status"))
        ));

        // Verify Metric
        verify(meterRegistry, times(1)).counter(
                eq("payment.reconciliation.resolutions"),
                eq("status"), eq("FAILED"),
                eq("provider"), eq("PSP_A")
        );
    }

    @Test
    void testTransientQueryFailureIncrementsRetryAndRetainsPending() {
        when(pspAConnector.queryStatus(activeAttempt))
                .thenThrow(new PspTimeoutException("Ambiguous connection failure"));

        when(attemptRepository.findById(activeAttempt.getAttemptId())).thenReturn(Optional.of(activeAttempt));
        when(intentRepository.findById(pendingIntent.getIntentId())).thenReturn(Optional.of(pendingIntent));

        // Trigger reconciliation
        reconciliationService.reconcileIntent(pendingIntent);

        // State remains PENDING, but retry meta increments
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(activeAttempt.getStatus()).isEqualTo(AttemptStatus.PENDING);
        assertThat(activeAttempt.getRetryCount()).isEqualTo(1);
        assertThat(activeAttempt.getErrorCode()).isEqualTo("PSP_TIMEOUT");

        verify(intentRepository, times(1)).save(pendingIntent);
        verify(attemptRepository, times(1)).save(activeAttempt);
    }

    @Test
    void testReconciliationEscalationAlertAt24Hours() {
        // Mock intent created 26 hours ago
        pendingIntent.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(26));

        PspResponse successResponse = new PspResponse(PspStatus.SUCCESS, "psp_ref_111", null, null);
        when(pspAConnector.queryStatus(activeAttempt)).thenReturn(successResponse);
        when(intentRepository.findById(pendingIntent.getIntentId())).thenReturn(Optional.of(pendingIntent));
        when(attemptRepository.findById(activeAttempt.getAttemptId())).thenReturn(Optional.of(activeAttempt));

        reconciliationService.reconcileIntent(pendingIntent);

        // Verify 24h escalation alert metric incremented
        verify(meterRegistry, times(1)).counter(
                eq("payment.reconciliation.escalations"),
                eq("level"), eq("alert_24h")
        );
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void testReconciliationManualReviewEscalationAt48Hours() {
        // Mock intent created 50 hours ago
        pendingIntent.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(50));
        when(intentRepository.findById(pendingIntent.getIntentId())).thenReturn(Optional.of(pendingIntent));
        when(attemptRepository.findById(activeAttempt.getAttemptId())).thenReturn(Optional.of(activeAttempt));

        // Simulate status query returning PENDING
        PspResponse pendingResponse = new PspResponse(PspStatus.PENDING, null, null, null);
        when(pspAConnector.queryStatus(activeAttempt)).thenReturn(pendingResponse);

        // Trigger reconciliation
        reconciliationService.reconcileIntent(pendingIntent);

        // Assert it remained in PENDING status
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.PENDING);

        // Verify critical 48h escalation metrics
        verify(meterRegistry, times(1)).counter(
                eq("payment.reconciliation.escalations"),
                eq("level"), eq("critical_48h")
        );
    }

    @Test
    void testContradictoryWebhookPrecedencePreserved() {
        // Pre-establish Intent in terminal AUTHORIZED status
        pendingIntent.setStatus(PaymentStatus.AUTHORIZED);

        when(intentRepository.findById(pendingIntent.getIntentId())).thenReturn(Optional.of(pendingIntent));
        when(attemptRepository.findById(activeAttempt.getAttemptId())).thenReturn(Optional.of(activeAttempt));

        // Simulate a status query returning a contradictory FAILED response
        PspResponse contradictoryFailed = new PspResponse(PspStatus.FAILED, null, "CARD_DECLINED", "Card declined by bank");

        // Execute resolveOutcome directly
        reconciliationService.resolveOutcome(pendingIntent.getIntentId(), activeAttempt.getAttemptId(), contradictoryFailed);

        // Verify status remains AUTHORIZED and FAILED state was completely ignored (Precedence Rule)
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        verify(intentRepository, never()).save(pendingIntent);
        verify(attemptRepository, never()).save(activeAttempt);
    }
}
