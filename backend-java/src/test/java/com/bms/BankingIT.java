package com.bms;

import com.bms.domain.AccountStatus;
import com.bms.domain.Permission;
import com.bms.domain.TxnType;
import com.bms.domain.User;
import com.bms.report.ReconciliationService;
import com.bms.repo.BankAccountRepository;
import com.bms.support.ApiClient;
import com.bms.support.IntegrationTest;
import com.bms.support.TestData;
import com.bms.txn.Actor;
import com.bms.txn.BankingFacade;
import com.bms.txn.BankingRuleException;
import com.bms.txn.MoneyCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class BankingIT {

    @LocalServerPort int port;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankingFacade banking;
    @Autowired ReconciliationService reconciliation;
    @Autowired BankAccountRepository accounts;

    private ApiClient as(String username) {
        ApiClient api = new ApiClient(port);
        assertThat(api.login(username, TestData.PASSWORD).status()).isEqualTo(200);
        return api;
    }

    private int ledgerEntries(String reference) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries l JOIN transactions t ON t.id = l.transaction_id WHERE t.reference = ?",
                Integer.class, reference);
    }

    @Test
    void depositIncreasesBalanceAndWritesOneCreditEntry() {
        var c = data.customer("0");
        var r = as(c.username()).post("/api/v1/transactions/deposits",
                Map.of("accountNumber", c.accountNumber(), "amount", "1250.50", "description", "Simulated cash"));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("status")).isEqualTo("COMPLETED");
        assertThat(r.text("reference")).startsWith("TXN");
        assertThat(r.body().path("balanceAfter").decimalValue()).isEqualByComparingTo("1250.50");
        assertThat(r.body().path("simulated").asBoolean()).isTrue();
        assertThat(data.balance(c.accountNumber())).isEqualByComparingTo("1250.50");
        assertThat(ledgerEntries(r.text("reference"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'DEPOSIT_COMPLETED' AND target_id = ?",
                Integer.class, r.text("reference"))).isEqualTo(1);
    }

    @Test
    void invalidAmountsAreRejected() {
        var c = data.customer("100.00");
        ApiClient api = as(c.username());
        for (String amount : List.of("0", "-5.00", "10.123", "abc", "1000000.01", "99999999999999999")) {
            var r = api.postRaw("/api/v1/transactions/deposits",
                    "{\"accountNumber\":\"" + c.accountNumber() + "\",\"amount\":\"" + amount + "\"}");
            assertThat(r.status()).as("amount %s", amount).isEqualTo(400);
        }
        assertThat(data.balance(c.accountNumber())).isEqualByComparingTo("100.00");
    }

    @Test
    void withdrawalDecreasesBalanceAndOverdraftIsRefusedAndRecordedAsFailed() {
        var c = data.customer("500.00");
        ApiClient api = as(c.username());
        var ok = api.post("/api/v1/transactions/withdrawals", Map.of("accountNumber", c.accountNumber(), "amount", "200.00"));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(data.balance(c.accountNumber())).isEqualByComparingTo("300.00");

        var tooMuch = api.post("/api/v1/transactions/withdrawals", Map.of("accountNumber", c.accountNumber(), "amount", "300.01"));
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.text("code")).isEqualTo("INSUFFICIENT_FUNDS");
        String failedRef = tooMuch.text("reference");
        assertThat(failedRef).startsWith("TXN");
        assertThat(data.balance(c.accountNumber())).isEqualByComparingTo("300.00");
        assertThat(jdbc.queryForObject("SELECT status FROM transactions WHERE reference = ?", String.class, failedRef)).isEqualTo("FAILED");
        assertThat(ledgerEntries(failedRef)).isZero();

        var failedVisible = api.get("/api/v1/transactions?status=FAILED&accountId=" + c.account().getId());
        assertThat(failedVisible.body().path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void transferDebitsAndCreditsWithLinkedBalancedLedgerEntries() {
        var alice = data.customer("1000.00");
        var bob = data.customer("50.00");
        var r = as(alice.username()).post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", bob.accountNumber(), "amount", "400.00"), UUID.randomUUID().toString());
        assertThat(r.status()).isEqualTo(201);
        assertThat(data.balance(alice.accountNumber())).isEqualByComparingTo("600.00");
        assertThat(data.balance(bob.accountNumber())).isEqualByComparingTo("450.00");
        var entries = jdbc.queryForList("SELECT l.entry_type, l.amount FROM ledger_entries l JOIN transactions t ON t.id = l.transaction_id WHERE t.reference = ? ORDER BY entry_type",
                r.text("reference"));
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("entry_type")).isEqualTo("CREDIT");
        assertThat(entries.get(1).get("entry_type")).isEqualTo("DEBIT");
        assertThat((BigDecimal) entries.get(0).get("amount")).isEqualByComparingTo((BigDecimal) entries.get(1).get("amount"));
        assertThat(reconciliation.run().consistent()).isTrue();
    }

    @Test
    void transferValidationSameAccountMissingKeyAndUnknownDestination() {
        var alice = data.customer("100.00");
        ApiClient api = as(alice.username());
        var same = api.post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", alice.accountNumber(), "amount", "1.00"), UUID.randomUUID().toString());
        assertThat(same.status()).isEqualTo(400);
        assertThat(same.text("code")).isEqualTo("SAME_ACCOUNT");
        var noKey = api.post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", "501999999999", "amount", "1.00"));
        assertThat(noKey.status()).isEqualTo(400);
        var unknown = api.post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", "501999999999", "amount", "1.00"), UUID.randomUUID().toString());
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(data.balance(alice.accountNumber())).isEqualByComparingTo("100.00");
    }

    @Test
    void transferToSuspendedAccountRollsBackCompletely() {
        var alice = data.customer("100.00");
        var bob = data.customer("0");
        User admin = data.admin();
        assertThat(as(admin.getUsername()).patch("/api/v1/admin/accounts/" + bob.account().getId() + "/status",
                Map.of("status", "SUSPENDED", "reason", "test")).status()).isEqualTo(200);
        int before = jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);

        var r = as(alice.username()).post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", bob.accountNumber(), "amount", "40.00"), UUID.randomUUID().toString());
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("code")).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(data.balance(alice.accountNumber())).isEqualByComparingTo("100.00");
        assertThat(data.balance(bob.accountNumber())).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isEqualTo(before);
        assertThat(accounts.findById(bob.account().getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void duplicateIdempotencyKeyExecutesTheTransferOnlyOnce() {
        var alice = data.customer("1000.00");
        var bob = data.customer("0");
        ApiClient api = as(alice.username());
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", bob.accountNumber(), "amount", "250.00");
        var first = api.post("/api/v1/transactions/transfers", body, key);
        var retry = api.post("/api/v1/transactions/transfers", body, key);
        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().path("replayed").asBoolean()).isTrue();
        assertThat(retry.text("reference")).isEqualTo(first.text("reference"));
        assertThat(data.balance(alice.accountNumber())).isEqualByComparingTo("750.00");
        assertThat(data.balance(bob.accountNumber())).isEqualByComparingTo("250.00");

        var reused = api.post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", bob.accountNumber(), "amount", "999.00"), key);
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.text("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(data.balance(alice.accountNumber())).isEqualByComparingTo("750.00");
    }

    @Test
    void concurrentDuplicateRequestsWithTheSameKeyMoveMoneyOnce() throws Exception {
        var alice = data.customer("1000.00");
        var bob = data.customer("0");
        ApiClient api = as(alice.username());
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", bob.accountNumber(), "amount", "100.00");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ApiClient.Response>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> { go.await(); return api.post("/api/v1/transactions/transfers", body, key); }));
        }
        go.countDown();
        Set<String> refs = ConcurrentHashMap.newKeySet();
        for (var f : results) {
            var r = f.get(30, TimeUnit.SECONDS);
            assertThat(r.status()).isIn(200, 201, 409);
            if (r.status() != 409) refs.add(r.text("reference"));
        }
        pool.shutdown();
        assertThat(refs).hasSize(1);
        assertThat(data.balance(alice.accountNumber())).isEqualByComparingTo("900.00");
        assertThat(data.balance(bob.accountNumber())).isEqualByComparingTo("100.00");
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawTheAccount() throws Exception {
        var c = data.customer("1000.00");
        Actor actor = Actor.of(c.user());
        int attempts = 25;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                try {
                    banking.execute(actor, new MoneyCommand(TxnType.WITHDRAWAL, c.accountNumber(), null,
                            new BigDecimal("100.00"), "race", null));
                    ok.incrementAndGet();
                } catch (BankingRuleException e) {
                    if (e.getCode().equals("INSUFFICIENT_FUNDS")) insufficient.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (var f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(ok.get()).isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(attempts - 10);
        assertThat(data.balance(c.accountNumber())).isEqualByComparingTo("0.00");
        assertThat(reconciliation.run().consistent()).isTrue();
    }

    @Test
    void concurrentOpposingTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        var a = data.customer("5000.00");
        var b = data.customer("5000.00");
        User teller = data.employee(EnumSet.allOf(Permission.class));
        Actor actor = Actor.of(teller);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            boolean ab = i % 2 == 0;
            fs.add(pool.submit(() -> {
                go.await();
                banking.execute(actor, new MoneyCommand(TxnType.TRANSFER, ab ? a.accountNumber() : b.accountNumber(),
                        ab ? b.accountNumber() : a.accountNumber(), new BigDecimal("37.00"), "pingpong",
                        UUID.randomUUID().toString()));
                return null;
            }));
        }
        go.countDown();
        for (var f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        BigDecimal total = data.balance(a.accountNumber()).add(data.balance(b.accountNumber()));
        assertThat(total).isEqualByComparingTo("10000.00");
        assertThat(data.balance(a.accountNumber())).isEqualByComparingTo("5000.00");
        assertThat(reconciliation.run().consistent()).isTrue();
    }

    @Test
    void ledgerAndTransactionHistoryAreAppendOnly() {
        var c = data.customer("10.00");
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE account_id = ?", c.account().getId()))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transactions WHERE destination_account_id = ?", c.account().getId()))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE bank_accounts SET balance = -1 WHERE id = ?", c.account().getId()))
                .rootCause().hasMessageContaining("balance");
    }

    @Test
    void historyShowsOnlyOwnTransactionsWithCorrectDirectionAndFilters() {
        var alice = data.customer("800.00");
        var bob = data.customer("0");
        ApiClient api = as(alice.username());
        api.post("/api/v1/transactions/withdrawals", Map.of("accountNumber", alice.accountNumber(), "amount", "30.00"));
        api.post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", bob.accountNumber(), "amount", "70.00"), UUID.randomUUID().toString());

        var all = api.get("/api/v1/transactions?size=50");
        assertThat(all.body().path("totalElements").asInt()).isEqualTo(3);  // funding deposit + 2
        var transfers = api.get("/api/v1/transactions?type=TRANSFER");
        assertThat(transfers.body().path("totalElements").asInt()).isEqualTo(1);
        assertThat(transfers.body().path("content").get(0).path("direction").asText()).isEqualTo("DEBIT");
        assertThat(transfers.body().path("content").get(0).path("amount").decimalValue()).isEqualByComparingTo("70.00");
        var range = api.get("/api/v1/transactions?minAmount=50&maxAmount=100");
        assertThat(range.body().path("totalElements").asInt()).isEqualTo(1);
        var paged = api.get("/api/v1/transactions?size=2&page=1&sort=amount,asc");
        assertThat(paged.body().path("content")).hasSize(1);
        assertThat(paged.body().path("totalPages").asInt()).isEqualTo(2);
        assertThat(api.get("/api/v1/transactions?sort=password,asc").status()).isEqualTo(400);

        var bobHistory = as(bob.username()).get("/api/v1/transactions");
        assertThat(bobHistory.body().path("totalElements").asInt()).isEqualTo(1);
        assertThat(bobHistory.body().path("content").get(0).path("direction").asText()).isEqualTo("CREDIT");
    }

    @Test
    void largeTransactionsAreAutoFlaggedAndEmployeesCanFlagManually() {
        var c = data.customer("0");
        User emp = data.employee(EnumSet.allOf(Permission.class));
        ApiClient api = as(emp.getUsername());
        var big = api.post("/api/v1/transactions/deposits", Map.of("accountNumber", c.accountNumber(), "amount", "150000.00"));
        assertThat(big.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flagged_transactions f JOIN transactions t ON t.id = f.transaction_id WHERE t.reference = ? AND f.source = 'AUTO'",
                Integer.class, big.text("reference"))).isEqualTo(1);

        var small = api.post("/api/v1/transactions/deposits", Map.of("accountNumber", c.accountNumber(), "amount", "10.00"));
        var flag = api.post("/api/v1/transactions/" + small.text("reference") + "/flags", Map.of("reason", "Customer behaviour unusual"));
        assertThat(flag.status()).isEqualTo(201);
        var dup = api.post("/api/v1/transactions/" + small.text("reference") + "/flags", Map.of("reason", "Second flag attempt"));
        assertThat(dup.status()).isEqualTo(409);

        User admin = data.admin();
        var review = as(admin.getUsername()).patch("/api/v1/admin/flags/" + flag.body().path("id").asLong(),
                Map.of("status", "RESOLVED", "note", "Verified with customer"));
        assertThat(review.status()).isEqualTo(200);
        assertThat(review.text("status")).isEqualTo("RESOLVED");
    }
}
