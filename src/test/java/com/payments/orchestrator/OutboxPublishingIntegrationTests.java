package com.payments.orchestrator;

import com.payments.orchestrator.domain.OutboxStatus;
import com.payments.orchestrator.domain.PaymentOutbox;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import com.payments.orchestrator.service.EventPublisher;
import com.payments.orchestrator.service.InMemoryEventPublisher;
import com.payments.orchestrator.worker.OutboxPruningScheduler;
import com.payments.orchestrator.worker.OutboxPublisherWorker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
class OutboxPublishingIntegrationTests extends BaseIntegrationTest {

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private InMemoryEventPublisher inMemoryEventPublisher;

    @Autowired
    private OutboxPublisherWorker outboxPublisherWorker;

    @Autowired
    private OutboxPruningScheduler outboxPruningScheduler;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private EventPublisher mockEventPublisher;

    private UUID aggregateId;
    private Map<String, Object> payload;

    @BeforeEach
    void setUp() {
        aggregateId = UUID.randomUUID();
        payload = new HashMap<>();
        payload.put("merchant_order_id", "ORDER-OUTBOX-999");
        payload.put("amount", "100.00");

        inMemoryEventPublisher.clear();
        outboxRepository.deleteAll();
    }

    @Test
    @Transactional
    void testSuccessfulPublishingFlow() {
        // Setup mock event publisher to simulate successful brokers publication
        doNothing().when(mockEventPublisher).publish(anyString(), anyString(), anyMap());

        PaymentOutbox outbox = new PaymentOutbox();
        outbox.setOutboxId(UUID.randomUUID());
        outbox.setAggregateId(aggregateId);
        outbox.setAggregateType("PaymentIntent");
        outbox.setEventType("PAYMENT_CREATED");
        outbox.setPayload(payload);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);

        outboxRepository.save(outbox);
        entityManager.flush();
        entityManager.clear();

        // Trigger polling batch cycle
        outboxPublisherWorker.processPendingBatch();

        // 1. Verify entity transitioned to PROCESSED status
        PaymentOutbox retrieved = outboxRepository.findById(outbox.getOutboxId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(retrieved.getProcessedAt()).isNotNull();

        // 2. Verify external publication was triggered
        verify(mockEventPublisher, times(1)).publish(
                eq("payment-events"),
                eq(aggregateId.toString()),
                eq(payload)
        );
    }

    @Test
    @Transactional
    void testDeadLetterQueueThresholdExceeded() {
        // Mock event publisher to throw exception on publication
        doThrow(new RuntimeException("Simulated broker network failure"))
                .when(mockEventPublisher).publish(anyString(), anyString(), anyMap());

        PaymentOutbox outbox = new PaymentOutbox();
        outbox.setOutboxId(UUID.randomUUID());
        outbox.setAggregateId(aggregateId);
        outbox.setAggregateType("PaymentIntent");
        outbox.setEventType("PAYMENT_CREATED");
        outbox.setPayload(payload);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);

        outboxRepository.save(outbox);
        entityManager.flush();
        entityManager.clear();

        // Trigger polling 4 times - must increment retryCount but retain PENDING status
        for (int i = 0; i < 4; i++) {
            outboxPublisherWorker.processPendingBatch();
            
            PaymentOutbox current = outboxRepository.findById(outbox.getOutboxId()).orElseThrow();
            assertThat(current.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(current.getRetryCount()).isEqualTo(i + 1);
        }

        // 5th trigger - exceeds DLQ threshold, moves to FAILED status
        outboxPublisherWorker.processPendingBatch();
        PaymentOutbox finalRecord = outboxRepository.findById(outbox.getOutboxId()).orElseThrow();
        assertThat(finalRecord.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(finalRecord.getRetryCount()).isEqualTo(5);

        // 6th trigger - final event should be ignored as status is FAILED (should not trigger MockEventPublisher call again)
        reset(mockEventPublisher);
        outboxPublisherWorker.processPendingBatch();
        verifyNoInteractions(mockEventPublisher);
    }

    @Test
    @Transactional
    void testPruningHistoricalOutboxRecords() {
        // Setup mock event publisher
        doNothing().when(mockEventPublisher).publish(anyString(), anyString(), anyMap());

        // Event 1: processed 25 hours ago (Older than the 24h retention window, should be pruned)
        PaymentOutbox oldEvent = new PaymentOutbox();
        oldEvent.setOutboxId(UUID.randomUUID());
        oldEvent.setAggregateId(UUID.randomUUID());
        oldEvent.setAggregateType("PaymentIntent");
        oldEvent.setEventType("PAYMENT_CREATED");
        oldEvent.setPayload(payload);
        oldEvent.setStatus(OutboxStatus.PROCESSED);
        oldEvent.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(25));
        outboxRepository.save(oldEvent);

        // Event 2: processed 1 hour ago (Within the 24h retention window, should remain preserved)
        PaymentOutbox freshEvent = new PaymentOutbox();
        freshEvent.setOutboxId(UUID.randomUUID());
        freshEvent.setAggregateId(UUID.randomUUID());
        freshEvent.setAggregateType("PaymentIntent");
        freshEvent.setEventType("PAYMENT_CREATED");
        freshEvent.setPayload(payload);
        freshEvent.setStatus(OutboxStatus.PROCESSED);
        freshEvent.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        outboxRepository.save(freshEvent);

        entityManager.flush();
        entityManager.clear();

        // Execute scheduled pruner
        outboxPruningScheduler.pruneHistoricalOutboxRecords();

        // Assert only Event 1 was deleted
        assertThat(outboxRepository.findById(oldEvent.getOutboxId())).isEmpty();
        assertThat(outboxRepository.findById(freshEvent.getOutboxId())).isPresent();
    }
}
