package com.bms.repo;

import com.bms.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByTransactionIdOrderByIdAsc(Long transactionId);

    long countByTransactionId(Long transactionId);
}
