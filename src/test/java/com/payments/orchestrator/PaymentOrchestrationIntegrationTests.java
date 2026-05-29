package com.payments.orchestrator;

import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.dto.CreatePaymentRequest;
import com.payments.orchestrator.dto.MoneyAmount;
import com.payments.orchestrator.exception.DuplicateMerchantOrderException;
import com.payments.orchestrator.repository.PaymentEventRepository;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import com.payments.orchestrator.service.PaymentOrchestrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOrchestrationIntegrationTests extends BaseIntegrationTest {

    @Autowired
    private PaymentOrchestrationService orchestrationService;

    @Autowired
    private PaymentIntentRepository intentRepository;

    @Autowired
    private PaymentEventRepository eventRepository;

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private TransactionalTestHelper transactionalTestHelper;

    private CreatePaymentRequest request;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        idempotencyKey = UUID.randomUUID().toString();
        request = new CreatePaymentRequest(
                UUID.randomUUID(),
                "ORDER-INT-123",
                "CARD",
                "vault_token_int",
                new MoneyAmount("USD", new BigDecimal("100.00")),
                new MoneyAmount("INR", new BigDecimal("8300.00")),
                null
        );
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void testAtomicPersistenceFlowIntegration() {
        MDC.put("request_id", "req-int-test");
        MDC.put("correlation_id", "corr-int-test");

        // Execute persistence transaction flow
        PaymentIntent savedIntent = orchestrationService.createInitialPaymentState(request, idempotencyKey);

        assertThat(savedIntent).isNotNull();
        UUID intentId = savedIntent.getIntentId();

        // 1. Verify PaymentIntent is persisted correctly
        Optional<PaymentIntent> retrievedIntentOpt = intentRepository.findById(intentId);
        assertThat(retrievedIntentOpt).isPresent();
        PaymentIntent retrievedIntent = retrievedIntentOpt.get();
        assertThat(retrievedIntent.getMerchantOrderId()).isEqualTo("ORDER-INT-123");
        assertThat(retrievedIntent.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(retrievedIntent.getRequestId()).isEqualTo("req-int-test");
        assertThat(retrievedIntent.getCorrelationId()).isEqualTo("corr-int-test");

        // 2. Verify PaymentAttempt is persisted correctly (cascaded bidirectional check)
        assertThat(retrievedIntent.getAttempts()).hasSize(1);
        PaymentAttempt attempt = retrievedIntent.getAttempts().get(0);
        assertThat(attempt.getProviderName()).isEqualTo("PSP_A");
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.PROCESSING);
        assertThat(attempt.getRequestId()).isEqualTo("req-int-test");
        assertThat(attempt.getCorrelationId()).isEqualTo("corr-int-test");

        // 3. Verify PaymentEvent (Audit Trail) is persisted
        List<PaymentEvent> events = eventRepository.findAll().stream()
                .filter(e -> "corr-int-test".equals(e.getCorrelationId()))
                .toList();
        assertThat(events).hasSize(1);
        PaymentEvent event = events.get(0);
        assertThat(event.getIntentId()).isEqualTo(intentId);
        assertThat(event.getAttemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(event.getEventType()).isEqualTo("PAYMENT_INITIATED");
        assertThat(event.getEventPayload()).containsEntry("merchant_order_id", "ORDER-INT-123");

        // 4. Verify PaymentOutbox is persisted
        List<PaymentOutbox> outboxEvents = outboxRepository.findAll().stream()
                .filter(o -> "corr-int-test".equals(o.getCorrelationId()))
                .toList();
        assertThat(outboxEvents).hasSize(1);
        PaymentOutbox outbox = outboxEvents.get(0);
        assertThat(outbox.getAggregateId()).isEqualTo(intentId);
        assertThat(outbox.getEventType()).isEqualTo("PAYMENT_CREATED");
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getPayload()).containsEntry("merchant_order_id", "ORDER-INT-123");
    }

    @Test
    void testMerchantOrderUniquenessEnforced() {
        orchestrationService.createInitialPaymentState(request, idempotencyKey);

        // Attempting to initiate the exact same order should throw a conflict exception
        assertThatThrownBy(() -> orchestrationService.createInitialPaymentState(request, "different-idempotency-key"))
                .isInstanceOf(DuplicateMerchantOrderException.class)
                .hasMessageContaining("Duplicate merchant order");
    }

    @Test
    void testTransactionRollbackOnRuntimeException() {
        String uniqueOrderId = "ORDER-ERR-" + UUID.randomUUID();
        CreatePaymentRequest rollbackRequest = new CreatePaymentRequest(
                UUID.randomUUID(),
                uniqueOrderId,
                "CARD",
                "vault_token_rollback",
                new MoneyAmount("USD", new BigDecimal("10.00")),
                new MoneyAmount("INR", new BigDecimal("830.00")),
                null
        );

        // Execute transactional helper bean that throws a simulated error
        assertThatThrownBy(() -> transactionalTestHelper.runWithException(rollbackRequest, idempotencyKey))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated runtime error for rollback");

        // Verify that absolutely NOTHING was committed to the DB for this order
        Optional<PaymentIntent> intent = intentRepository.findByMerchantIdAndMerchantOrderId(
                rollbackRequest.getMerchantId(), rollbackRequest.getMerchantOrderId()
        );
        assertThat(intent).isEmpty();

        // Verify no audit event was saved for this correlation
        List<PaymentEvent> events = eventRepository.findAll().stream()
                .filter(e -> "corr-rollback-test".equals(e.getCorrelationId()))
                .toList();
        assertThat(events).isEmpty();

        // Verify no outbox event was saved for this correlation
        List<PaymentOutbox> outboxEvents = outboxRepository.findAll().stream()
                .filter(o -> "corr-rollback-test".equals(o.getCorrelationId()))
                .toList();
        assertThat(outboxEvents).isEmpty();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public TransactionalTestHelper transactionalTestHelper(PaymentOrchestrationService orchestrationService) {
            return new TransactionalTestHelper(orchestrationService);
        }
    }

    static class TransactionalTestHelper {
        private final PaymentOrchestrationService orchestrationService;

        public TransactionalTestHelper(PaymentOrchestrationService orchestrationService) {
            this.orchestrationService = orchestrationService;
        }

        @Transactional
        public void runWithException(CreatePaymentRequest request, String idempotencyKey) {
            MDC.put("correlation_id", "corr-rollback-test");
            
            // Initiate saving within this transaction
            orchestrationService.createInitialPaymentState(request, idempotencyKey);
            
            // Intentionally trigger an exception to force rollback of all 4 inserts
            throw new RuntimeException("Simulated runtime error for rollback");
        }
    }
}
