package com.payments.orchestrator.repository;

import com.payments.orchestrator.domain.OutboxStatus;
import com.payments.orchestrator.domain.PaymentOutbox;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentOutboxRepository extends JpaRepository<PaymentOutbox, UUID> {

    /**
     * Polls pending outbox records using a pessimistic write lock and postgres-native SKIP LOCKED.
     * Prevents lock contention and double-publishing when multiple worker instances execute concurrently.
     * In Hibernate 6, a lock timeout hint of "-2" translates to SQL "FOR UPDATE SKIP LOCKED" for PostgreSQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT o FROM PaymentOutbox o WHERE o.status = :status ORDER BY o.createdAt ASC")
    List<PaymentOutbox> findTopPendingForPublishing(@Param("status") OutboxStatus status, Pageable pageable);

    /**
     * Prunes processed outbox rows older than the specified retention window.
     */
    @Modifying
    @Query("DELETE FROM PaymentOutbox o WHERE o.status = :status AND o.processedAt < :retentionDate")
    int pruneProcessedOutbox(@Param("status") OutboxStatus status, @Param("retentionDate") OffsetDateTime retentionDate);

    /**
     * Counts the total outbox records matching a specific status (e.g. for lag metrics).
     */
    long countByStatus(OutboxStatus status);
}
