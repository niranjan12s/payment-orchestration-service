package com.payments.orchestrator.worker;

import com.payments.orchestrator.domain.OutboxStatus;
import com.payments.orchestrator.domain.PaymentOutbox;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import com.payments.orchestrator.service.EventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxPublisherWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherWorker.class);

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private MeterRegistry meterRegistry;

    private final AtomicLong outboxLagGauge = new AtomicLong(0);

    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge("payment.outbox.lag", outboxLagGauge);
    }

    /**
     * Periodically wakes up to poll pending outbox events and publish them downstream.
     * The fixed delay is read dynamically from configuration, falling back to 1.5 seconds.
     */
    @Scheduled(fixedDelayString = "${orchestrator.workers.outbox-publisher.interval-ms:1500}")
    @Transactional
    public void executePollingCycle() {
        try {
            MDC.put("request_id", "bg_outbox_cycle_" + UUID.randomUUID().toString());
            MDC.put("internal_request_id", "int_" + UUID.randomUUID().toString());
            processPendingBatch();
        } catch (Exception outboxPollingCycleException) {
            log.error("Unhandled exception in outbox polling scheduler loop.", outboxPollingCycleException);
        } finally {
            MDC.clear();
        }
    }

    /**
     * Fetches and processes a batch of pending outbox events inside a single transaction.
     * Employs Pessimistic SKIP LOCKED strategy to prevent lock wait contention or double-publishing.
     */
    @Transactional
    public void processPendingBatch() {
        // Poll up to 50 pending records (with pessimistic SKIP LOCKED locks)
        List<PaymentOutbox> pendingList = outboxRepository.findTopPendingForPublishing(
                OutboxStatus.PENDING, PageRequest.of(0, 50)
        );

        if (pendingList.isEmpty()) {
            return;
        }

        log.info("Outbox publisher picked up {} pending events for downstream async publishing.", pendingList.size());

        for (PaymentOutbox event : pendingList) {
            try {
                MDC.put("request_id", "bg_outbox_" + UUID.randomUUID().toString());
                MDC.put("correlation_id", event.getCorrelationId());
                MDC.put("internal_request_id", "int_" + UUID.randomUUID().toString());
                if (event.getAggregateId() != null) {
                    MDC.put("intent_id", event.getAggregateId().toString());
                }

                log.debug("Publishing outbox event: {}, type: {}, aggId: {}",
                        event.getOutboxId(), event.getEventType(), event.getAggregateId());

                // Route to topic payment-events using the aggregate ID as the partition key
                eventPublisher.publish("payment-events", event.getAggregateId().toString(), event.getPayload());

                event.setStatus(OutboxStatus.PROCESSED);
                event.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
                log.debug("Successfully published outbox event: {}", event.getOutboxId());
            } catch (Exception eventPublishException) {
                log.error("Exception occurred while publishing outbox event: {}. Msg: {}", event.getOutboxId(), eventPublishException.getMessage(), eventPublishException);

                meterRegistry.counter("payment.outbox.publish.failure", "aggregate", event.getAggregateType()).increment();

                int attempts = event.getRetryCount() + 1;
                event.setRetryCount(attempts);

                if (attempts >= 5) {
                    log.error("[OUTBOX FAILURE] Dead-letter threshold (5) exceeded for outbox event: {}. " +
                            "Agg ID: {}, Type: {}. Transitioning to FAILED DLQ state.",
                            event.getOutboxId(), event.getAggregateId(), event.getAggregateType());

                    event.setStatus(OutboxStatus.FAILED);
                    meterRegistry.counter("payment.outbox.dlq.count", "aggregate", event.getAggregateType()).increment();
                } else {
                    // Retain status as PENDING to retry in subsequently scheduled ticks
                    event.setStatus(OutboxStatus.PENDING);
                }
            } finally {
                MDC.remove("intent_id");
                MDC.remove("correlation_id");
            }

            outboxRepository.save(event);
        }

        // Record lag metric representing count of remaining PENDING events in the table
        outboxLagGauge.set(outboxRepository.countByStatus(OutboxStatus.PENDING));
    }
}
