package com.payments.orchestrator.repository;

import com.payments.orchestrator.domain.PaymentIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotency, Long> {

    /**
     * Resolves stored response and hash verification matching the idempotency key.
     */
    Optional<PaymentIdempotency> findByIdempotencyKey(String idempotencyKey);

    /**
     * Purges expired idempotency keys (TTL job).
     */
    @Modifying
    @Query("DELETE FROM PaymentIdempotency p WHERE p.expiresAt < :now")
    int deleteExpiredKeys(@Param("now") OffsetDateTime now);
}
