package com.payments.orchestrator.repository;

import com.payments.orchestrator.domain.ProcessedWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedWebhookRepository extends JpaRepository<ProcessedWebhook, Long> {

    /**
     * Checks if a webhook event from a specific provider has already been processed.
     */
    boolean existsByProviderNameAndProviderEventId(String providerName, String providerEventId);
}
