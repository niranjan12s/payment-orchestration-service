package com.payments.orchestrator.repository;

import com.payments.orchestrator.domain.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    /**
     * Resolves all immutable audit events logged for a specific payment intent.
     */
    List<PaymentEvent> findByIntentIdOrderByCreatedAtAsc(UUID intentId);
}
