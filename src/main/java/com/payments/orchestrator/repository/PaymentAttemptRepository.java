package com.payments.orchestrator.repository;

import com.payments.orchestrator.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    /**
     * Resolves attempt via provider reference (essential for webhook correlation).
     */
    Optional<PaymentAttempt> findByProviderReference(String providerReference);

    /**
     * Resolves attempt via provider and provider reference to avoid cross-provider ambiguity.
     */
    Optional<PaymentAttempt> findByProviderNameAndProviderReference(String providerName, String providerReference);
}
