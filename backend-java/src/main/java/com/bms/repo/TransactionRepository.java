package com.bms.repo;

import com.bms.domain.TransactionRecord;
import com.bms.domain.TxnStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<TransactionRecord, Long>,
        JpaSpecificationExecutor<TransactionRecord> {

    @EntityGraph(attributePaths = {"sourceAccount", "destinationAccount", "initiatedBy"})
    Optional<TransactionRecord> findByReference(String reference);

    @Override
    @EntityGraph(attributePaths = {"sourceAccount", "destinationAccount", "initiatedBy"})
    Page<TransactionRecord> findAll(Specification<TransactionRecord> spec, Pageable pageable);

    long countByStatus(TxnStatus status);

    long countByCreatedAtGreaterThanEqual(OffsetDateTime from);

    @Query("select coalesce(sum(t.amount), 0) from TransactionRecord t where t.status = com.bms.domain.TxnStatus.COMPLETED")
    BigDecimal completedVolume();

    @Query("select coalesce(sum(t.amount), 0) from TransactionRecord t where t.status = com.bms.domain.TxnStatus.COMPLETED and t.createdAt >= :from")
    BigDecimal completedVolumeSince(OffsetDateTime from);

    /** Completed outgoing amount (withdrawals + transfers) for an account since a point in time. */
    @Query("""
            select coalesce(sum(t.amount), 0) from TransactionRecord t
            where t.sourceAccount.id = :accountId and t.status = com.bms.domain.TxnStatus.COMPLETED
              and t.createdAt >= :from
            """)
    BigDecimal outflowSince(Long accountId, OffsetDateTime from);

    @Query("""
            select count(t) from TransactionRecord t
            where t.sourceAccount.id = :accountId and t.status = com.bms.domain.TxnStatus.COMPLETED
              and t.createdAt >= :from
            """)
    long outflowCountSince(Long accountId, OffsetDateTime from);

    @Query("select count(t) from TransactionRecord t where t.initiatedBy.id = :userId and t.createdAt >= :from")
    long countInitiatedSince(Long userId, OffsetDateTime from);
}
