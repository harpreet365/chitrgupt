package com.bms.repo;

import com.bms.domain.AccountStatus;
import com.bms.domain.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long>, JpaSpecificationExecutor<BankAccount> {

    Optional<BankAccount> findByAccountNumber(String accountNumber);

    List<BankAccount> findByCustomer_IdOrderByOpenedAtAsc(Long customerId);

    @Query("select a.id from BankAccount a where a.customer.user.id = :userId")
    List<Long> findIdsOwnedByUser(Long userId);

    /** Row lock (SELECT ... FOR UPDATE). Callers lock accounts in ascending id order to avoid deadlocks. */
    @Query(value = "select * from bank_accounts where id = :id for update", nativeQuery = true)
    Optional<BankAccount> lockById(Long id);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountSequence();

    long countByStatus(AccountStatus status);

    @Query("select coalesce(sum(a.balance), 0) from BankAccount a where a.status <> com.bms.domain.AccountStatus.CLOSED")
    BigDecimal totalDeposits();
}
