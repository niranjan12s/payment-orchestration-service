package com.payments.orchestrator.repository;

import com.payments.orchestrator.domain.PaymentIntent;
import com.payments.orchestrator.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    /**
     * Resolves unique payment intent via composite business identifier.
     */
    Optional<PaymentIntent> findByMerchantIdAndMerchantOrderId(UUID merchantId, String merchantOrderId);

    /**
     * Efficient status count — used by worker gauges to avoid full table scans.
     */
    long countByStatus(PaymentStatus status);


    /**
     * Polls pending payment intents for reconciliation using a pessimistic write lock and postgres-native SKIP LOCKED.
     * Prevents multi-worker collisions and duplicate reconciliation checks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT i FROM PaymentIntent i WHERE i.status = :status ORDER BY i.updatedAt ASC")
    List<PaymentIntent> findTopPendingForReconciliation(@Param("status") PaymentStatus status, Pageable pageable);

    /**
     * Polls pending payment intents eligible for retry under Skip Locked.
     * Selects intents whose status is PENDING and whose active attempt status is FAILED with a retry-safe error code.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT DISTINCT i FROM PaymentIntent i JOIN FETCH i.attempts a " +
           "WHERE i.status = :status AND a.status = 'FAILED' AND a.errorCode IN :retrySafeCodes " +
           "ORDER BY i.updatedAt ASC")
    List<PaymentIntent> findTopPendingForRetry(
            @Param("status") PaymentStatus status,
            @Param("retrySafeCodes") Set<String> retrySafeCodes,
            Pageable pageable
    );

    @Query("SELECT MIN(i.createdAt) FROM PaymentIntent i WHERE i.status = :status")
    Optional<java.time.OffsetDateTime> findOldestPendingCreatedAt(@Param("status") PaymentStatus status);

    @Query("SELECT i FROM PaymentIntent i LEFT JOIN FETCH i.attempts WHERE i.intentId = :id")
    Optional<PaymentIntent> findByIdWithAttempts(@Param("id") UUID id);
}
