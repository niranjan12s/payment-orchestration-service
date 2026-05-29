package com.payments.orchestrator.worker;

import com.payments.orchestrator.domain.OutboxStatus;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class OutboxPruningScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPruningScheduler.class);

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    /**
     * Periodically prunes historical successfully processed outbox records from the database.
     * Defaults to running once every hour (3,600,000 milliseconds) but is fully configurable.
     * Retains outbox event history for a 24-hour audit retention window.
     */
    @Scheduled(fixedDelayString = "${orchestrator.workers.outbox-pruner.interval-ms:3600000}")
    @Transactional
    public void pruneHistoricalOutboxRecords() {
        OffsetDateTime retentionDate = OffsetDateTime.now(ZoneOffset.UTC).minusHours(24);
        log.info("Executing scheduled outbox history cleanup job (Pruning processed events older than {})...", retentionDate);
        
        try {
            int prunedCount = outboxRepository.pruneProcessedOutbox(OutboxStatus.PROCESSED, retentionDate);
            if (prunedCount > 0) {
                log.info("Successfully pruned {} historical processed outbox events from the database.", prunedCount);
            } else {
                log.debug("No historical processed outbox events found to prune.");
            }
        } catch (Exception outboxPruningException) {
            log.error("Failed to execute scheduled outbox history cleanup pruning job.", outboxPruningException);
        }
    }
}
