package com.payments.orchestrator.worker;

import com.payments.orchestrator.domain.PaymentAttempt;
import com.payments.orchestrator.domain.PaymentIntent;
import com.payments.orchestrator.domain.PaymentStatus;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.service.RetryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RetryWorker {

    private static final Logger log = LoggerFactory.getLogger(RetryWorker.class);

    private static final Set<String> RETRY_SAFE_CODES = Set.of(
            "NOT_FOUND",
            "RETRY_SAFE_DECLINE",
            "REQUEST_NEVER_REACHED_PROCESSOR",
            "IDEMPOTENCY_LOOKUP_ABSENT",
            "CONNECT_TIMEOUT",
            "CONNECTION_RESET",
            "CONNECTION_REFUSED",
            "DNS_FAILURE",
            "PSP_TIMEOUT"
    );

    @Autowired
    private PaymentIntentRepository intentRepository;

    @Autowired
    private RetryService retryService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${orchestrator.workers.retry.base-backoff-ms:1000}")
    private long baseBackoffMs;

    @Value("${orchestrator.workers.retry.max-backoff-ms:300000}")
    private long maxBackoffMs;

    private final AtomicLong retryLagGauge = new AtomicLong(0);

    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge("payment.retry.lag", retryLagGauge);
    }

    /**
     * Periodically polls pending transactions eligible for retry checks.
     * Scheduler ticks every 2 seconds by default (configurable).
     */
    @Scheduled(fixedDelayString = "${orchestrator.workers.retry.interval-ms:2000}")
    @Transactional
    public void executeRetryCycle() {
        try {
            MDC.put("request_id", "bg_retry_cycle_" + UUID.randomUUID().toString());
            MDC.put("internal_request_id", "int_" + UUID.randomUUID().toString());

            log.info("Starting background async retry polling cycle...");

            // 1. Transactional Phase: Poll and lock eligible retry batch under SKIP LOCKED
            List<PaymentIntent> eligibleBatch = lockAndSelectRetryBatch();

            if (eligibleBatch.isEmpty()) {
                log.info("No pending payment intents eligible for retry on this cycle.");
            } else {
                log.info("Picked up {} pending intents for async retry. Executing authorization retries...", eligibleBatch.size());

                // 2. Non-Transactional Phase: Execute PSP authorization (outside database locks)
                for (PaymentIntent intent : eligibleBatch) {
                    try {
                        MDC.put("request_id", "bg_retry_" + UUID.randomUUID().toString());
                        MDC.put("correlation_id", intent.getCorrelationId());
                        MDC.put("internal_request_id", "int_" + UUID.randomUUID().toString());
                        MDC.put("intent_id", intent.getIntentId().toString());

                        retryService.executeRetry(intent);
                    } catch (Exception intentRetryException) {
                        log.error("Unhandled exception executing retry for intent: {}.", intent.getIntentId(), intentRetryException);
                    } finally {
                        // Clear intent-specific MDC values
                        MDC.remove("intent_id");
                        MDC.remove("correlation_id");
                    }
                }
            }

            // 3. Telemetry: Count pending retry backlog lag size
            long retryLag = intentRepository.countByStatus(PaymentStatus.PENDING);
            retryLagGauge.set(retryLag);

        } catch (Exception retryCycleException) {
            log.error("Fatal exception in background retry scheduler loop.", retryCycleException);
        } finally {
            MDC.clear();
        }
    }

    /**
     * Polls pending transactions whose active attempt is failed with retry-safe errors.
     * Validates time-based exponential backoff, updates timestamps, and releases locks upon commit.
     */
    @Transactional
    public List<PaymentIntent> lockAndSelectRetryBatch() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Fetch up to 50 pending records (with pessimistic SKIP LOCKED locks)
        List<PaymentIntent> pendingBatch = intentRepository.findTopPendingForRetry(
                PaymentStatus.PENDING, RETRY_SAFE_CODES, PageRequest.of(0, 50)
        );

        List<PaymentIntent> eligibleList = new ArrayList<>();

        for (PaymentIntent intent : pendingBatch) {
            // Find latest/active attempt to check backoff elapsed time
            PaymentAttempt activeAttempt = intent.getAttempts().stream()
                    .max(Comparator.comparing(PaymentAttempt::getCreatedAt))
                    .orElse(null);

            if (activeAttempt == null) {
                continue;
            }

            int retryCount = activeAttempt.getRetryCount();
            
            // Exponential Backoff Formula: baseMs * 2^(retryCount). Capped at maxBackoffMs (5 minutes)
            long backoffMs = baseBackoffMs * (long) Math.pow(2, retryCount);
            backoffMs = Math.min(backoffMs, maxBackoffMs);

            long msSinceUpdate = Duration.between(activeAttempt.getUpdatedAt(), now).toMillis();

            if (msSinceUpdate < backoffMs) {
                log.debug("Payment intent {} retry deferred. Elapsed: {}ms, Backoff required: {}ms.",
                        intent.getIntentId(), msSinceUpdate, backoffMs);
                continue;
            }

            // Update intent updatedAt to push it to the end of the queue and indicate it was picked up
            intent.setUpdatedAt(now);
            intentRepository.save(intent);

            eligibleList.add(intent);
        }

        return eligibleList;
    }
}
