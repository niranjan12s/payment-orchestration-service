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
import com.payments.orchestrator.worker.RetryWorker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RetryWorkerTests {

    @InjectMocks
    private RetryServiceImpl retryService;

    @InjectMocks
    private RetryWorker retryWorker;

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

    private UUID merchantId;
    private PaymentIntent pendingIntent;
    private PaymentAttempt failedAttempt;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Setup mock metrics
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mockCounter);

        // Setup toolchain values for backoff capped checks
        ReflectionTestUtils.setField(retryWorker, "baseBackoffMs", 1000L);
        ReflectionTestUtils.setField(retryWorker, "maxBackoffMs", 300000L);

        merchantId = UUID.randomUUID();

        // Setup default mappers
        when(errorClassifier.getTargetIntentStatus(PspStatus.SUCCESS)).thenReturn(PaymentStatus.AUTHORIZED);
        when(errorClassifier.getTargetAttemptStatus(PspStatus.SUCCESS)).thenReturn(AttemptStatus.AUTHORIZED);
        when(errorClassifier.getTargetIntentStatus(PspStatus.FAILED)).thenReturn(PaymentStatus.FAILED);
        when(errorClassifier.getTargetAttemptStatus(PspStatus.FAILED)).thenReturn(AttemptStatus.FAILED);

        // Setup initial PENDING intent with failed retry-safe attempt
        pendingIntent = new PaymentIntent();
        pendingIntent.setIntentId(UUID.randomUUID());
        pendingIntent.setMerchantId(merchantId);
        pendingIntent.setMerchantOrderId("ORDER-RETRY-999");
        pendingIntent.setStatus(PaymentStatus.PENDING);
        pendingIntent.setTransactionCurrencyCode("USD");
        pendingIntent.setTransactionAmount(new BigDecimal("100.00"));
        pendingIntent.setSettlementCurrencyCode("USD");
        pendingIntent.setSettlementAmount(new BigDecimal("100.00"));
        pendingIntent.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        pendingIntent.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        failedAttempt = new PaymentAttempt();
        failedAttempt.setAttemptId(UUID.randomUUID());
        failedAttempt.setIntent(pendingIntent);
        failedAttempt.setProviderName("PSP_A");
        failedAttempt.setPaymentMethodType(PaymentMethodType.CARD);
        failedAttempt.setPaymentTokenReference("tok_sec123");
        failedAttempt.setStatus(AttemptStatus.FAILED);
        failedAttempt.setErrorCode("CONNECT_TIMEOUT"); // Retry-Safe
        failedAttempt.setRetryCount(0);
        failedAttempt.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        failedAttempt.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        pendingIntent.addAttempt(failedAttempt);
    }

    @Test
    void testRetrySafeAndBackoffCalculatorDeferrals() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 1. Ready Intent: failed retry-safe error code, updated 10 minutes ago, retryCount=0 => backoff 1s
        PaymentIntent eligibleIntent = new PaymentIntent();
        eligibleIntent.setIntentId(UUID.randomUUID());
        eligibleIntent.setStatus(PaymentStatus.PENDING);
        eligibleIntent.setUpdatedAt(now.minusMinutes(10));
        PaymentAttempt att1 = new PaymentAttempt();
        att1.setStatus(AttemptStatus.FAILED);
        att1.setErrorCode("DNS_FAILURE");
        att1.setRetryCount(0);
        att1.setCreatedAt(now.minusMinutes(10));
        att1.setUpdatedAt(now.minusMinutes(10));
        eligibleIntent.addAttempt(att1);

        // 2. Not Ready Intent: failed retry-safe error, updated 30s ago, retryCount=2 => backoff 4s (4000ms)
        PaymentIntent backoffIntent = new PaymentIntent();
        backoffIntent.setIntentId(UUID.randomUUID());
        backoffIntent.setStatus(PaymentStatus.PENDING);
        backoffIntent.setUpdatedAt(now.minusSeconds(30));
        PaymentAttempt att2 = new PaymentAttempt();
        att2.setStatus(AttemptStatus.FAILED);
        att2.setErrorCode("CONNECT_TIMEOUT");
        att2.setRetryCount(2); // Capped Backoff required = 1000 * 2^2 = 4000ms
        att2.setCreatedAt(now.minusMinutes(5));
        att2.setUpdatedAt(now.minusSeconds(2)); // Only 2s elapsed
        backoffIntent.addAttempt(att2);

        when(intentRepository.findTopPendingForRetry(eq(PaymentStatus.PENDING), anySet(), any(Pageable.class)))
                .thenReturn(Arrays.asList(eligibleIntent, backoffIntent));

        // Trigger retry batch locks
        List<PaymentIntent> eligibleList = retryWorker.lockAndSelectRetryBatch();

        // Verifies only eligible intent 1 is picked and updated, while backoff intent 2 is safely deferred
        assertThat(eligibleList).hasSize(1);
        assertThat(eligibleList.get(0).getIntentId()).isEqualTo(eligibleIntent.getIntentId());

        verify(intentRepository, times(1)).save(eligibleIntent);
        verify(intentRepository, never()).save(backoffIntent);
    }

    @Test
    void testHappyPathRetrySuccessMarksOldAttemptsSuperseded() {
        PspResponse successResponse = new PspResponse(PspStatus.SUCCESS, "ref_retry_authorized_100", null, null);
        when(pspAConnector.authorize(any(PaymentAttempt.class), anyString())).thenReturn(successResponse);

        when(intentRepository.findById(pendingIntent.getIntentId())).thenReturn(Optional.of(pendingIntent));
        when(attemptRepository.findById(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            if (id.equals(failedAttempt.getAttemptId())) return Optional.of(failedAttempt);
            
            // Return a new mock processing attempt
            PaymentAttempt newAttempt = new PaymentAttempt();
            newAttempt.setAttemptId(id);
            newAttempt.setIntent(pendingIntent);
            newAttempt.setProviderName("PSP_A");
            newAttempt.setStatus(AttemptStatus.PROCESSING);
            newAttempt.setRetryCount(1);
            return Optional.of(newAttempt);
        });

        // Trigger retry
        retryService.executeRetry(pendingIntent);

        // Assert new attempt and intent are AUTHORIZED
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        
        // Prior Attempt 1 transitions to SUPERSEDED (Rule 3)
        assertThat(failedAttempt.getStatus()).isEqualTo(AttemptStatus.SUPERSEDED);

        // Verify successful outbox and events saved
        verify(eventRepository, times(1)).save(argThat(event -> 
                "PAYMENT_AUTHORIZED".equals(event.getEventType()) && 
                event.getIntentId().equals(pendingIntent.getIntentId())
        ));

        verify(outboxRepository, times(1)).save(argThat(outbox -> 
                "PAYMENT_AUTHORIZED".equals(outbox.getEventType()) && 
                "AUTHORIZED".equals(outbox.getPayload().get("status"))
        ));

        verify(meterRegistry, times(1)).counter("payment.retry.execution.success");
    }

    @Test
    void testDefinitiveFailureRetryDeflectsIntentsToFailed() {
        // Mock hard decline
        PspResponse hardDecline = new PspResponse(PspStatus.FAILED, null, "INSUFFICIENT_FUNDS", "Card declined: Insufficient funds");
        when(pspAConnector.authorize(any(PaymentAttempt.class), anyString())).thenReturn(hardDecline);

        when(intentRepository.findById(pendingIntent.getIntentId())).thenReturn(Optional.of(pendingIntent));
        when(attemptRepository.findById(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            if (id.equals(failedAttempt.getAttemptId())) return Optional.of(failedAttempt);
            PaymentAttempt newAttempt = new PaymentAttempt();
            newAttempt.setAttemptId(id);
            newAttempt.setIntent(pendingIntent);
            newAttempt.setProviderName("PSP_A");
            newAttempt.setStatus(AttemptStatus.PROCESSING);
            newAttempt.setRetryCount(1);
            return Optional.of(newAttempt);
        });

        // Trigger retry
        retryService.executeRetry(pendingIntent);

        // Assert intent is failed immediately (Hard declines must fail-fast without further retries)
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.FAILED);

        verify(eventRepository, times(1)).save(argThat(event -> 
                "PAYMENT_FAILED".equals(event.getEventType()) && 
                event.getIntentId().equals(pendingIntent.getIntentId())
        ));

        verify(outboxRepository, times(1)).save(argThat(outbox -> 
                "PAYMENT_FAILED".equals(outbox.getEventType())
        ));
    }

    @Test
    void testMaxAttemptsTriggersDLQAlert() {
        // Setup intent with 5 prior attempts (6th attempt will exceed cap)
        PaymentIntent maxIntent = new PaymentIntent();
        maxIntent.setIntentId(UUID.randomUUID());
        maxIntent.setStatus(PaymentStatus.PENDING);
        maxIntent.setMerchantId(merchantId);
        maxIntent.setMerchantOrderId("MAX-ORDER-999");
        maxIntent.setCorrelationId("corr-max");
        maxIntent.setRequestId("req-max");
        maxIntent.setTransactionAmount(BigDecimal.TEN);
        maxIntent.setTransactionCurrencyCode("USD");
        maxIntent.setSettlementAmount(BigDecimal.TEN);
        maxIntent.setSettlementCurrencyCode("USD");

        for (int i = 0; i < 5; i++) {
            PaymentAttempt att = new PaymentAttempt();
            att.setAttemptId(UUID.randomUUID());
            att.setStatus(AttemptStatus.FAILED);
            att.setRetryCount(i);
            att.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            maxIntent.addAttempt(att);
        }

        when(intentRepository.findById(maxIntent.getIntentId())).thenReturn(Optional.of(maxIntent));

        // Prepare retry attempt (5th attempt)
        PaymentAttempt result = retryService.prepareRetryAttempt(maxIntent.getIntentId());

        // Assert it returned null, moved intent to FAILED (terminal), and raised DLQ metrics
        assertThat(result).isNull();
        assertThat(maxIntent.getStatus()).isEqualTo(PaymentStatus.FAILED);

        verify(eventRepository, times(1)).save(argThat(event -> 
                "PAYMENT_FAILED".equals(event.getEventType()) && 
                "MAX_RETRY_EXCEEDED".equals(event.getEventPayload().get("reason"))
        ));

        verify(outboxRepository, times(1)).save(argThat(outbox -> 
                "PAYMENT_FAILED".equals(outbox.getEventType()) && 
                "FAILED".equals(outbox.getPayload().get("status"))
        ));

        verify(meterRegistry, times(1)).counter("payment.retry.dlq.count");
    }
}
