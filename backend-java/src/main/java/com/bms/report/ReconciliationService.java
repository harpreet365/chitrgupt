package com.bms.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Independent integrity check of the ledger invariants:
 * 1. every account balance equals SUM(credits) - SUM(debits) of its ledger entries;
 * 2. every completed transfer has exactly one debit and one credit, both equal to the transaction amount;
 * 3. completed deposits/withdrawals have exactly one entry; failed transactions have none.
 */
@Service
public class ReconciliationService {

    public record Result(boolean consistent, int accountsChecked, int transactionsChecked,
                         List<Map<String, Object>> balanceMismatches, List<Map<String, Object>> entryMismatches,
                         OffsetDateTime checkedAt) {}

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result run() {
        Integer accounts = jdbc.queryForObject("SELECT count(*) FROM bank_accounts", Integer.class);
        Integer txns = jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class);
        List<Map<String, Object>> balance = jdbc.queryForList("""
                SELECT a.account_number, a.balance,
                       COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE -l.amount END), 0) AS ledger_balance
                FROM bank_accounts a LEFT JOIN ledger_entries l ON l.account_id = a.id
                GROUP BY a.id, a.account_number, a.balance
                HAVING a.balance <> COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE -l.amount END), 0)
                LIMIT 100""");
        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT t.reference, t.type, t.status, t.amount,
                       COUNT(l.id) AS entries,
                       COALESCE(SUM(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount END), 0) AS debits,
                       COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount END), 0) AS credits
                FROM transactions t LEFT JOIN ledger_entries l ON l.transaction_id = t.id
                GROUP BY t.id, t.reference, t.type, t.status, t.amount
                HAVING (t.status = 'FAILED' AND COUNT(l.id) <> 0)
                    OR (t.status = 'COMPLETED' AND t.type = 'TRANSFER' AND (COUNT(l.id) <> 2
                        OR COALESCE(SUM(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount END), 0) <> t.amount
                        OR COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount END), 0) <> t.amount))
                    OR (t.status = 'COMPLETED' AND t.type = 'DEPOSIT' AND (COUNT(l.id) <> 1
                        OR COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount END), 0) <> t.amount))
                    OR (t.status = 'COMPLETED' AND t.type = 'WITHDRAWAL' AND (COUNT(l.id) <> 1
                        OR COALESCE(SUM(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount END), 0) <> t.amount))
                LIMIT 100""");
        return new Result(balance.isEmpty() && entries.isEmpty(), accounts == null ? 0 : accounts,
                txns == null ? 0 : txns, balance, entries, OffsetDateTime.now());
    }
}
