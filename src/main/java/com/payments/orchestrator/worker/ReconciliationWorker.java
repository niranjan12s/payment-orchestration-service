package com.payments.orchestrator.worker;

import com.payments.orchestrator.domain.AttemptStatus;
import com.payments.orchestrator.domain.PaymentAttempt;
import com.payments.orchestrator.domain.PaymentIntent;
import com.payments.orchestrator.domain.PaymentStatus;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.service.ReconciliationService;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);

    @Autowired
    private PaymentIntentRepository intentRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private MeterRegistry meterRegistry;

    private final AtomicLong reconciliationBacklogGauge = new AtomicLong(0);

    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge("payment.reconciliation.backlog", reconciliationBacklogGauge);
    }

    /**
     * Periodically wakes up to run the reconciliation polling cycle.
     * Fixed delay is configured via application.yml, defaulting to 45 seconds.
     */
    @Scheduled(fixedDelayString = "${orchestrator.workers.reconciliation.interval-ms:45000}")
    @Transactional
    public void executeReconciliationCycle() {
        try {
            MDC.put("request_id", "bg_recon_cycle_" + UUID.randomUUID().toString());
            MDC.put("internal_request_id", "int_" + UUID.randomUUID().toString());

            log.info("Starting background reconciliation polling cycle...");
            
            // 1. Transactional Phase: Poll and Lock eligible pending intents
            List<PaymentIntent> eligibleBatch = lockAndSelectReconciliationBatch();
            
            if (eligibleBatch.isEmpty()) {
                log.info("No pending payment intents eligible for reconciliation on this cycle.");
            } else {
                log.info("Picked up {} payment intents for status reconciliation. Initiating queries...", eligibleBatch.size());
                
                // 2. Non-Transactional Phase: Execute status queries (safely outside locks)
                for (PaymentIntent intent : eligibleBatch) {
                    try {
                        MDC.put("request_id", "bg_recon_" + UUID.randomUUID().toString());
                        MDC.put("correlation_id", intent.getCorrelationId());
                        MDC.put("internal_request_id", "int_" + UUID.randomUUID().toString());
                        MDC.put("intent_id", intent.getIntentId().toString());

                        reconciliationService.reconcileIntent(intent);
                    } catch (Exception intentReconciliationException) {
                        log.error("Unhandled exception processing reconciliation for intent: {}.", intent.getIntentId(), intentReconciliationException);
                    } finally {
                        // Clear intent-specific MDC values
                        MDC.remove("intent_id");
                        MDC.remove("correlation_id");
                    }
                }
            }

            // 3. Telemetry: Count backlog size
            long backlogSize = intentRepository.countByStatus(PaymentStatus.PENDING);
            reconciliationBacklogGauge.set(backlogSize);
            
        } catch (Exception reconciliationCycleException) {
            log.error("Fatal exception in background reconciliation scheduler loop.", reconciliationCycleException);
        } finally {
            MDC.clear();
        }
    }

    /**
     * Polls pending payment intents inside a short-lived transaction using SELECT FOR UPDATE SKIP LOCKED.
     * Evaluates retry backoffs, updates timestamps to defer subsequent polls, and releases locks upon commit.
     */
    @Transactional
    public List<PaymentIntent> lockAndSelectReconciliationBatch() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        
        // Poll up to 50 PENDING intents (locked via SKIP LOCKED)
        List<PaymentIntent> pendingBatch = intentRepository.findTopPendingForReconciliation(
                PaymentStatus.PENDING, PageRequest.of(0, 50)
        );

        List<PaymentIntent> eligibleList = new ArrayList<>();

        for (PaymentIntent intent : pendingBatch) {
            // Find active attempt to evaluate retry count
            PaymentAttempt activeAttempt = intent.getAttempts().stream()
                    .filter(a -> a.getStatus() == AttemptStatus.PROCESSING || a.getStatus() == AttemptStatus.PENDING)
                    .findFirst()
                    .orElse(null);

            if (activeAttempt == null) {
                activeAttempt = intent.getAttempts().stream()
                        .max(Comparator.comparing(PaymentAttempt::getCreatedAt))
                        .orElse(null);
            }

            int retryCount = activeAttempt != null ? activeAttempt.getRetryCount() : 0;
            
            // Retry Backoff Formula: base 60 seconds * Math.pow(2, retryCount), capped at 15 minutes (900 seconds)
            long backoffSeconds = 60L * Math.min(15, (long) Math.pow(2, retryCount));
            long secondsSinceUpdate = Duration.between(intent.getUpdatedAt(), now).getSeconds();

            if (secondsSinceUpdate < backoffSeconds) {
                // Not ready to check yet; skip this intent for now
                log.debug("Payment intent {} in backoff. Elapsed: {}s, Backoff required: {}s. Skipping.",
                        intent.getIntentId(), secondsSinceUpdate, backoffSeconds);
                continue;
            }

            // Update updatedAt to "now" to push it to the end of the queue and indicate it was checked on this cycle
            intent.setUpdatedAt(now);
            intentRepository.save(intent);
            
            eligibleList.add(intent);
        }

        return eligibleList;
    }
}
