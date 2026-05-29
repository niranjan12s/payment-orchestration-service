package com.payments.orchestrator.worker;

import com.payments.orchestrator.repository.PaymentIdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class IdempotencyPruningScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPruningScheduler.class);

    @Autowired
    private PaymentIdempotencyRepository repository;

    /**
     * Periodically prunes expired idempotency records from the database.
     * Defaults to running once every hour (3,600,000 milliseconds) but is fully configurable.
     */
    @Scheduled(fixedDelayString = "${orchestrator.workers.idempotency-pruner.interval-ms:3600000}")
    @Transactional
    public void pruneExpiredIdempotencyKeys() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        log.info("Executing scheduled idempotency TTL pruner job...");
        try {
            int deletedCount = repository.deleteExpiredKeys(now);
            if (deletedCount > 0) {
                log.info("Successfully pruned {} expired idempotency records from the database.", deletedCount);
            } else {
                log.debug("No expired idempotency records found to prune.");
            }
        } catch (Exception idempotencyPruningException) {
            log.error("Failed to prune expired idempotency records from the database.", idempotencyPruningException);
        }
    }
}
