package com.bms.txn;

import com.bms.domain.EntryType;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The ONLY code that changes account balances. Each change is a single atomic, guarded SQL statement
 * (no read-modify-write in Java) and must run inside a transaction that also writes the ledger entry.
 * Callers hold row locks on the accounts (SELECT ... FOR UPDATE) before calling.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class LedgerDao {

    private final JdbcTemplate jdbc;

    public LedgerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Current state of an account row, read while holding its row lock. */
    public record LockedAccount(Long id, BigDecimal balance, String status) {}

    /** SELECT ... FOR UPDATE on the account row only; blocks until concurrent money transactions finish. */
    public LockedAccount lockForUpdate(Long accountId) {
        return jdbc.queryForObject("SELECT id, balance, status FROM bank_accounts WHERE id = ? FOR UPDATE",
                (rs, i) -> new LockedAccount(rs.getLong("id"), rs.getBigDecimal("balance"), rs.getString("status")),
                accountId);
    }

    /** Credits an ACTIVE account and returns the new balance. */
    public Optional<BigDecimal> credit(Long accountId, BigDecimal amount) {
        return single("""
                UPDATE bank_accounts SET balance = balance + ?, version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'ACTIVE'
                RETURNING balance""", amount, accountId);
    }

    /** Debits an ACTIVE account only if the funds are available; returns empty otherwise. */
    public Optional<BigDecimal> debit(Long accountId, BigDecimal amount) {
        return single("""
                UPDATE bank_accounts SET balance = balance - ?, version = version + 1, updated_at = now()
                WHERE id = ? AND status = 'ACTIVE' AND balance >= ?
                RETURNING balance""", amount, accountId, amount);
    }

    public void insertEntry(Long transactionId, Long accountId, EntryType type, BigDecimal amount,
                            BigDecimal balanceAfter, OffsetDateTime at) {
        jdbc.update("""
                INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount, balance_after, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                transactionId, accountId, type.name(), amount, balanceAfter, Timestamp.from(at.toInstant()));
    }

    private Optional<BigDecimal> single(String sql, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, BigDecimal.class, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
