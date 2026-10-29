package com.bms.repo;

import com.bms.domain.FlagStatus;
import com.bms.domain.FlaggedTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface FlaggedTransactionRepository extends JpaRepository<FlaggedTransaction, Long> {

    @EntityGraph(attributePaths = {"transaction", "transaction.sourceAccount", "transaction.destinationAccount",
            "flaggedBy", "reviewedBy"})
    Page<FlaggedTransaction> findByStatusIn(Collection<FlagStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"transaction", "flaggedBy", "reviewedBy"})
    Page<FlaggedTransaction> findByFlaggedBy_IdAndStatusIn(Long userId, Collection<FlagStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"transaction", "flaggedBy", "reviewedBy"})
    List<FlaggedTransaction> findByTransaction_IdOrderByCreatedAtDesc(Long transactionId);

    boolean existsByTransaction_IdAndStatus(Long transactionId, FlagStatus status);

    long countByStatus(FlagStatus status);

    long countByFlaggedBy_IdAndStatus(Long userId, FlagStatus status);

    @Query("select f.transaction.id from FlaggedTransaction f where f.status = com.bms.domain.FlagStatus.OPEN and f.transaction.id in :ids")
    Set<Long> openFlagTransactionIds(Collection<Long> ids);
}
