package com.payments.orchestrator;

import com.payments.orchestrator.health.KafkaHealthIndicator;
import com.payments.orchestrator.security.MaskingUtils;
import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import com.payments.orchestrator.service.EventPublisher;
import com.payments.orchestrator.service.ReconciliationService;
import com.payments.orchestrator.service.RetryService;
import com.payments.orchestrator.worker.OutboxPublisherWorker;
import com.payments.orchestrator.worker.ReconciliationWorker;
import com.payments.orchestrator.worker.RetryWorker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.MDC;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ObservabilityHardeningTests {

    // ===================================================
    // 1. Kafka Health Indicator Tests
    // ===================================================

    @Test
    void testKafkaHealthIndicatorReportsUp() {
        KafkaHealthIndicator kafkaHealthIndicator = new KafkaHealthIndicator();
        Health health = kafkaHealthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("broker")).isEqualTo("simulated-cluster");
        assertThat(health.getDetails().get("status")).isEqualTo("CONNECTED");
        assertThat(health.getDetails().get("topics")).isNotNull();

        @SuppressWarnings("unchecked")
        List<String> topics = (List<String>) health.getDetails().get("topics");
        assertThat(topics).contains("payment-events", "payment-outbox");
    }

    // ===================================================
    // 2. Sensitive Data Masking Tests
    // ===================================================

    @Test
    void testMaskingUtilsMasksLongTokensCorrectly() {
        String token = "vault_token_abc123";
        String masked = MaskingUtils.mask(token);

        // First 4 + **** + last 4
        assertThat(masked).startsWith("vaul");
        assertThat(masked).endsWith("3");
        assertThat(masked).contains("****");
        assertThat(masked).doesNotContain("token_abc12");
    }

    @Test
    void testMaskingUtilsMasksShortSecretsCompletely() {
        String shortSecret = "abc123";
        String masked = MaskingUtils.mask(shortSecret);
        assertThat(masked).isEqualTo("***masked***");
    }

    @Test
    void testMaskingUtilsHandlesNullGracefully() {
        assertThat(MaskingUtils.mask(null)).isNull();
    }

    @Test
    void testMaskingUtilsHandlesExactly8CharsBoundary() {
        // 8 chars exactly (length <= 8) should be fully masked
        assertThat(MaskingUtils.mask("12345678")).isEqualTo("***masked***");

        // 9 chars is long enough to show partial
        String nineChars = "123456789";
        String masked = MaskingUtils.mask(nineChars);
        assertThat(masked).contains("****");
        assertThat(masked).isNotEqualTo("***masked***");
    }

    @Test
    void testMaskingUtilsMasksExactly9Chars() {
        // 9-char string: first 4 + **** + last 4 => "1234****6789" (overlap at position 5-9)
        String masked = MaskingUtils.mask("123456789");
        // first 4 = "1234", last 4 = "6789"
        assertThat(masked).isEqualTo("1234****6789");
    }

    // ===================================================
    // 3. MDC Worker Tracing Tests
    // ===================================================

    @InjectMocks
    private ReconciliationWorker reconciliationWorker;

    @InjectMocks
    private RetryWorker retryWorker;

    @InjectMocks
    private OutboxPublisherWorker outboxPublisherWorker;

    @Mock
    private PaymentIntentRepository intentRepository;

    @Mock
    private PaymentOutboxRepository outboxRepository;

    @Mock
    private ReconciliationService reconciliationService;

    @Mock
    private RetryService retryService;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter mockCounter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Ensure gauge calls don't throw NullPointerException
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mockCounter);
        when(meterRegistry.gauge(anyString(), anyLong())).thenReturn(null);
        // Clear MDC before each test to avoid cross-test contamination
        MDC.clear();
    }

    @Test
    void testReconciliationWorkerClearsMdcAfterEmptyCycle() {
        // Return empty list from reconciliation query
        when(intentRepository.findTopPendingForReconciliation(any(PaymentStatus.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        // For lag tracking query also returns empty
        when(intentRepository.findAll()).thenReturn(Collections.emptyList());
        when(intentRepository.count()).thenReturn(0L);

        reconciliationWorker.executeReconciliationCycle();

        // MDC must be fully cleared after cycle
        assertThat(MDC.get("request_id")).isNull();
        assertThat(MDC.get("internal_request_id")).isNull();
        assertThat(MDC.get("intent_id")).isNull();
        assertThat(MDC.get("correlation_id")).isNull();
    }

    @Test
    void testRetryWorkerClearsMdcAfterEmptyCycle() {
        // Return empty list from retry query
        when(intentRepository.findTopPendingForRetry(any(PaymentStatus.class), any(Set.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        retryWorker.executeRetryCycle();

        // MDC must be fully cleared after cycle
        assertThat(MDC.get("request_id")).isNull();
        assertThat(MDC.get("internal_request_id")).isNull();
        assertThat(MDC.get("intent_id")).isNull();
        assertThat(MDC.get("correlation_id")).isNull();
    }

    @Test
    void testOutboxWorkerClearsMdcAfterEmptyCycle() {
        // Return empty list from outbox query (processPendingBatch returns early)
        when(outboxRepository.findTopPendingForPublishing(any(OutboxStatus.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        outboxPublisherWorker.executePollingCycle();

        // MDC must be fully cleared after cycle
        assertThat(MDC.get("request_id")).isNull();
        assertThat(MDC.get("internal_request_id")).isNull();
        assertThat(MDC.get("intent_id")).isNull();
        assertThat(MDC.get("correlation_id")).isNull();
    }

    @Test
    void testReconciliationWorkerPropagatesIntentIdInMdc() throws Exception {
        UUID intentId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent();
        intent.setIntentId(intentId);
        intent.setMerchantId(UUID.randomUUID());
        intent.setMerchantOrderId("ORDER-MDC-TEST");
        intent.setCorrelationId("corr-mdc-test");
        intent.setStatus(PaymentStatus.PENDING);
        intent.setTransactionAmount(new BigDecimal("100.00"));
        intent.setTransactionCurrencyCode("USD");
        intent.setSettlementAmount(new BigDecimal("100.00"));
        intent.setSettlementCurrencyCode("USD");
        intent.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        intent.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));

        List<String> capturedIntentIds = new ArrayList<>();

        // Capture the MDC state at the point reconcileIntent is called
        doAnswer(invocation -> {
            capturedIntentIds.add(MDC.get("intent_id"));
            return null;
        }).when(reconciliationService).reconcileIntent(any(PaymentIntent.class));

        when(intentRepository.findTopPendingForReconciliation(any(PaymentStatus.class), any(Pageable.class)))
                .thenReturn(List.of(intent));
        when(intentRepository.count()).thenReturn(1L);
        when(intentRepository.findAll()).thenReturn(List.of(intent));

        reconciliationWorker.executeReconciliationCycle();

        // MDC intent_id was populated when reconcileIntent was called
        assertThat(capturedIntentIds).hasSize(1);
        assertThat(capturedIntentIds.get(0)).isEqualTo(intentId.toString());

        // MDC must be fully cleared after cycle
        assertThat(MDC.get("intent_id")).isNull();
    }

    @Test
    void testOutboxWorkerPropagatesIntentIdFromAggregateId() {
        UUID aggregateId = UUID.randomUUID();
        PaymentOutbox outbox = new PaymentOutbox();
        outbox.setOutboxId(UUID.randomUUID());
        outbox.setAggregateId(aggregateId);
        outbox.setAggregateType("PaymentIntent");
        outbox.setCorrelationId("corr-outbox-test");
        outbox.setEventType("PAYMENT_AUTHORIZED");
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);

        Map<String, Object> payload = new HashMap<>();
        payload.put("intent_id", aggregateId.toString());
        payload.put("status", "AUTHORIZED");
        outbox.setPayload(payload);

        List<String> capturedIntentIds = new ArrayList<>();

        // Capture MDC state when publish is called
        doAnswer(invocation -> {
            capturedIntentIds.add(MDC.get("intent_id"));
            return null;
        }).when(eventPublisher).publish(anyString(), anyString(), any());

        when(outboxRepository.findTopPendingForPublishing(any(OutboxStatus.class), any(Pageable.class)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.save(any(PaymentOutbox.class))).thenReturn(outbox);
        when(outboxRepository.countByStatus(any(OutboxStatus.class))).thenReturn(0L);

        outboxPublisherWorker.executePollingCycle();

        // MDC intent_id was set during the publish call
        assertThat(capturedIntentIds).hasSize(1);
        assertThat(capturedIntentIds.get(0)).isEqualTo(aggregateId.toString());

        // MDC must be fully cleared after cycle
        assertThat(MDC.get("intent_id")).isNull();
    }

    @Test
    void testMdcIsolatedBetweenWorkerCycles() {
        // Pre-set some MDC values as if from a prior contaminated execution
        MDC.put("intent_id", "stale-intent-id");
        MDC.put("correlation_id", "stale-correlation");

        when(intentRepository.findTopPendingForReconciliation(any(PaymentStatus.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(intentRepository.count()).thenReturn(0L);
        when(intentRepository.findAll()).thenReturn(Collections.emptyList());

        reconciliationWorker.executeReconciliationCycle();

        // Stale MDC values must be cleared after the cycle
        assertThat(MDC.get("intent_id")).isNull();
        assertThat(MDC.get("correlation_id")).isNull();
    }
}
