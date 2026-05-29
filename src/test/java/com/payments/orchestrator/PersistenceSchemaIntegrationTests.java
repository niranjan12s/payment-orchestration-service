package com.payments.orchestrator;

import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceSchemaIntegrationTests extends BaseIntegrationTest {

    @Autowired
    private PaymentIntentRepository intentRepository;

    @Autowired
    private PaymentAttemptRepository attemptRepository;

    @Autowired
    private PaymentEventRepository eventRepository;

    @Autowired
    private PaymentIdempotencyRepository idempotencyRepository;

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private ProcessedWebhookRepository webhookRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void testPaymentIntentAndAttemptCascadesAndTimestamps() {
        // 1. Create a PaymentIntent
        PaymentIntent intent = new PaymentIntent();
        intent.setMerchantId(UUID.randomUUID());
        intent.setMerchantOrderId("ORDER-" + UUID.randomUUID());
        intent.setTransactionCurrencyCode("USD");
        intent.setTransactionAmount(new BigDecimal("150.00"));
        intent.setSettlementCurrencyCode("INR");
        intent.setSettlementAmount(new BigDecimal("12450.00"));
        intent.setStatus(PaymentStatus.CREATED);

        // 2. Create and associate a PaymentAttempt
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setProviderName("PSP_A");
        attempt.setPaymentMethodType(PaymentMethodType.CARD);
        attempt.setPaymentTokenReference("tok_visa_123");
        attempt.setStatus(AttemptStatus.PROCESSING);
        attempt.setRetryCount(0);

        intent.addAttempt(attempt);

        // 3. Save via cascade
        PaymentIntent savedIntent = intentRepository.save(intent);
        entityManager.flush();
        entityManager.clear();

        // 4. Assert persistence and generated columns
        PaymentIntent retrievedIntent = intentRepository.findById(savedIntent.getIntentId()).orElseThrow();
        assertThat(retrievedIntent.getIntentId()).isNotNull();
        assertThat(retrievedIntent.getVersion()).isEqualTo(0L);
        assertThat(retrievedIntent.getCreatedAt()).isNotNull();
        assertThat(retrievedIntent.getUpdatedAt()).isNotNull();
        assertThat(retrievedIntent.getStatus()).isEqualTo(PaymentStatus.CREATED);

        assertThat(retrievedIntent.getAttempts()).hasSize(1);
        PaymentAttempt retrievedAttempt = retrievedIntent.getAttempts().get(0);
        assertThat(retrievedAttempt.getAttemptId()).isNotNull();
        assertThat(retrievedAttempt.getProviderName()).isEqualTo("PSP_A");
        assertThat(retrievedAttempt.getStatus()).isEqualTo(AttemptStatus.PROCESSING);
        assertThat(retrievedAttempt.getCreatedAt()).isNotNull();
        assertThat(retrievedAttempt.getVersion()).isEqualTo(0L);
    }

    @Test
    @Transactional
    void testOptimisticLockingOnPaymentIntent() {
        // 1. Persist a PaymentIntent
        PaymentIntent intent = new PaymentIntent();
        intent.setMerchantId(UUID.randomUUID());
        intent.setMerchantOrderId("ORDER-" + UUID.randomUUID());
        intent.setStatus(PaymentStatus.CREATED);
        PaymentIntent saved = intentRepository.save(intent);
        entityManager.flush();
        entityManager.clear();

        UUID savedId = saved.getIntentId();

        // 2. Retrieve two distinct copies from persistence context
        PaymentIntent copy1 = intentRepository.findById(savedId).orElseThrow();
        PaymentIntent copy2 = intentRepository.findById(savedId).orElseThrow();

        // Make sure copy2 is detached/independent of copy1
        entityManager.detach(copy2);

        // 3. Modify copy1 and save (advances version from 0 to 1)
        copy1.setStatus(PaymentStatus.PROCESSING);
        intentRepository.save(copy1);
        entityManager.flush();

        // 4. Modify copy2 (still has old version 0 in memory) and try to save
        copy2.setStatus(PaymentStatus.AUTHORIZED);
        
        assertThatThrownBy(() -> {
            intentRepository.save(copy2);
            entityManager.flush();
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @Transactional
    void testOutboxPessimisticSkipLockedPollingQuery() {
        // 1. Create and save pending outbox rows
        PaymentOutbox outbox1 = new PaymentOutbox();
        outbox1.setAggregateId(UUID.randomUUID());
        outbox1.setAggregateType("PAYMENT");
        outbox1.setEventType("PAYMENT_CREATED");
        outbox1.setStatus(OutboxStatus.PENDING);
        
        Map<String, Object> payload1 = new HashMap<>();
        payload1.put("amount", 100);
        payload1.put("currency", "USD");
        outbox1.setPayload(payload1);

        PaymentOutbox outbox2 = new PaymentOutbox();
        outbox2.setAggregateId(UUID.randomUUID());
        outbox2.setAggregateType("PAYMENT");
        outbox2.setEventType("PAYMENT_CREATED");
        outbox2.setStatus(OutboxStatus.PENDING);
        outbox2.setPayload(payload1);

        outboxRepository.save(outbox1);
        outboxRepository.save(outbox2);
        entityManager.flush();
        entityManager.clear();

        // 2. Fetch pending outbox records using the custom poll query
        List<PaymentOutbox> pending = outboxRepository.findTopPendingForPublishing(
            OutboxStatus.PENDING, PageRequest.of(0, 10)
        );

        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).getPayload()).containsEntry("amount", 100);
    }

    @Test
    @Transactional
    void testPaymentIdempotencyJsonbAndTtl() {
        // 1. Save idempotency record
        PaymentIdempotency idempotency = new PaymentIdempotency();
        idempotency.setIdempotencyKey(UUID.randomUUID().toString());
        idempotency.setRequestHash("sha256_hash_value");
        idempotency.setStatus("COMPLETED");

        Map<String, Object> response = new HashMap<>();
        response.put("status", "AUTHORIZED");
        response.put("transaction_id", "tx_12345");
        idempotency.setResponsePayload(response);
        idempotency.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10)); // already expired

        idempotencyRepository.save(idempotency);
        entityManager.flush();
        entityManager.clear();

        // 2. Fetch by key
        PaymentIdempotency retrieved = idempotencyRepository.findByIdempotencyKey(idempotency.getIdempotencyKey()).orElseThrow();
        assertThat(retrieved.getRequestHash()).isEqualTo("sha256_hash_value");
        assertThat(retrieved.getResponsePayload()).containsEntry("transaction_id", "tx_12345");

        // 3. Purge expired keys
        int deleted = idempotencyRepository.deleteExpiredKeys(OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    @Transactional
    void testWebhookDeduplication() {
        String provider = "PSP_A";
        String eventId = "evt_" + UUID.randomUUID();

        ProcessedWebhook webhook = new ProcessedWebhook();
        webhook.setProviderName(provider);
        webhook.setProviderEventId(eventId);

        webhookRepository.save(webhook);
        entityManager.flush();
        entityManager.clear();

        boolean exists = webhookRepository.existsByProviderNameAndProviderEventId(provider, eventId);
        assertThat(exists).isTrue();

        boolean nonExistent = webhookRepository.existsByProviderNameAndProviderEventId(provider, "evt_other");
        assertThat(nonExistent).isFalse();
    }
}
