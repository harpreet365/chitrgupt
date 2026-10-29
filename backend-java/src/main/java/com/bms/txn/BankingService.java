package com.bms.txn;

import com.bms.audit.AuditService;
import com.bms.common.ApiException;
import com.bms.config.BmsProperties;
import com.bms.domain.AccountStatus;
import com.bms.domain.BankAccount;
import com.bms.domain.Channel;
import com.bms.domain.EntryType;
import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import com.bms.domain.TransactionRecord;
import com.bms.domain.TxnStatus;
import com.bms.domain.TxnType;
import com.bms.domain.User;
import com.bms.repo.BankAccountRepository;
import com.bms.repo.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes deposits, withdrawals and transfers. Every call is one database transaction:
 * claim idempotency key -> authorise -> lock accounts (ascending id) -> validate -> insert transaction ->
 * guarded balance updates + ledger entries -> audit -> auto-flag rules. Any exception rolls back everything.
 */
@Service
public class BankingService {

    /** Result of a money operation; {@code replayed} is true when an idempotency key matched an earlier request. */
    public record Outcome(TransactionRecord transaction, Map<Long, BigDecimal> balancesAfter, boolean replayed) {}

    private final BankAccountRepository accounts;
    private final TransactionRepository transactions;
    private final LedgerDao ledger;
    private final IdempotencyService idempotency;
    private final AutoFlagService autoFlag;
    private final AuditService audit;
    private final EntityManager em;
    private final BmsProperties props;

    public BankingService(BankAccountRepository accounts, TransactionRepository transactions, LedgerDao ledger,
                          IdempotencyService idempotency, AutoFlagService autoFlag, AuditService audit,
                          EntityManager em, BmsProperties props) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.autoFlag = autoFlag;
        this.audit = audit;
        this.em = em;
        this.props = props;
    }

    @Transactional
    public Outcome execute(Actor actor, MoneyCommand cmd, OffsetDateTime at, boolean demo) {
        TxnType type = cmd.type();
        BigDecimal amount = validateAmount(cmd.amount());
        String description = cleanDescription(cmd.description());
        boolean hasSource = type != TxnType.DEPOSIT;
        boolean hasDestination = type != TxnType.WITHDRAWAL;

        if (cmd.idempotencyKey() != null) {
            String hash = IdempotencyService.hash(type.name(), cmd.sourceAccountNumber(), cmd.destinationAccountNumber(),
                    amount.toPlainString(), description);
            var earlier = idempotency.claim(actor.userId(), cmd.idempotencyKey(), type.name(), hash);
            if (earlier.isPresent()) {
                return new Outcome(transactions.findById(earlier.get()).orElseThrow(), Map.of(), true);
            }
        }

        Channel channel = authoriseRole(actor, type);
        BankAccount source = hasSource ? resolve(cmd.sourceAccountNumber(), "Source account") : null;
        BankAccount destination = hasDestination ? resolve(cmd.destinationAccountNumber(), "Destination account") : null;
        authoriseOwnership(actor, type, source, destination);
        if (source != null && destination != null && source.getId().equals(destination.getId())) {
            throw ApiException.badRequest("SAME_ACCOUNT", "Source and destination accounts must be different");
        }

        // Lock rows in a global order (ascending id) so opposing transfers cannot deadlock.
        List<BankAccount> involved = new ArrayList<>();
        if (source != null) involved.add(source);
        if (destination != null) involved.add(destination);
        involved.sort(Comparator.comparing(BankAccount::getId));
        Map<Long, LedgerDao.LockedAccount> locked = new LinkedHashMap<>();
        for (BankAccount a : involved) locked.put(a.getId(), ledger.lockForUpdate(a.getId()));

        var ctx = new BankingRuleException.FailureContext(type, amount, source == null ? null : source.getId(),
                destination == null ? null : destination.getId(), actor.userId(), channel, description, demo);
        for (BankAccount a : involved) {
            String status = locked.get(a.getId()).status();
            if (!AccountStatus.ACTIVE.name().equals(status)) {
                String which = a == source ? "Source" : "Destination";
                throw new BankingRuleException("ACCOUNT_NOT_ACTIVE", which + " account is " + status.toLowerCase(), ctx);
            }
        }
        if (source != null) {
            if (actor.role() == RoleName.CUSTOMER) checkDailyLimit(source, amount, at, ctx);
            if (locked.get(source.getId()).balance().compareTo(amount) < 0) {
                throw new BankingRuleException("INSUFFICIENT_FUNDS", "Insufficient funds in the source account", ctx);
            }
        }

        TransactionRecord txn = transactions.save(new TransactionRecord(References.newReference(at), type,
                TxnStatus.COMPLETED, amount, source, destination, em.getReference(User.class, actor.userId()),
                channel, description, null, demo, at));

        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        if (source != null) {
            BigDecimal after = ledger.debit(source.getId(), amount).orElseThrow(() ->
                    new BankingRuleException("INSUFFICIENT_FUNDS", "Insufficient funds in the source account", ctx));
            ledger.insertEntry(txn.getId(), source.getId(), EntryType.DEBIT, amount, after, at);
            balances.put(source.getId(), after);
        }
        if (destination != null) {
            BigDecimal after = ledger.credit(destination.getId(), amount).orElseThrow(() ->
                    new BankingRuleException("ACCOUNT_NOT_ACTIVE", "Destination account is not active", ctx));
            ledger.insertEntry(txn.getId(), destination.getId(), EntryType.CREDIT, amount, after, at);
            balances.put(destination.getId(), after);
        }
        if (cmd.idempotencyKey() != null) {
            idempotency.complete(actor.userId(), cmd.idempotencyKey(), txn.getId());
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", txn.getReference());
        details.put("amount", amount.toPlainString());
        details.put("currency", "INR");
        if (source != null) details.put("source", source.getAccountNumber());
        if (destination != null) details.put("destination", destination.getAccountNumber());
        details.put("channel", channel.name());
        details.put("simulated", true);
        audit.record(type.name() + "_COMPLETED", "TRANSACTION", txn.getReference(), details);

        autoFlag.evaluate(txn, source, at);
        return new Outcome(txn, balances, false);
    }

    /**
     * Role-level checks, before any account lookup: administrators do not move money (separation of duties);
     * employees need the specific permission for the operation.
     */
    private Channel authoriseRole(Actor actor, TxnType type) {
        return switch (actor.role()) {
            case CUSTOMER -> Channel.ONLINE;
            case EMPLOYEE -> {
                Permission needed = switch (type) {
                    case DEPOSIT -> Permission.CASH_DEPOSIT;
                    case WITHDRAWAL -> Permission.CASH_WITHDRAWAL;
                    case TRANSFER -> Permission.TRANSFER;
                };
                if (!actor.permissions().contains(needed)) {
                    throw ApiException.forbidden("Your account is not authorised for " + type.name().toLowerCase() + "s");
                }
                yield Channel.BRANCH;
            }
            case ADMIN -> throw ApiException.forbidden(
                    "Administrators cannot initiate money movements (separation of duties)");
        };
    }

    /**
     * Customers act only on their own accounts (a deposit is simulated funds into their own account).
     * Accounts they do not own are reported as "not found" so other customers' accounts cannot be probed.
     */
    private void authoriseOwnership(Actor actor, TxnType type, BankAccount source, BankAccount destination) {
        if (actor.role() != RoleName.CUSTOMER) return;
        BankAccount owned = type == TxnType.DEPOSIT ? destination : source;
        if (!owned.getCustomer().getUser().getId().equals(actor.userId())) {
            throw ApiException.notFound(type == TxnType.DEPOSIT ? "Destination account" : "Source account");
        }
    }

    private void checkDailyLimit(BankAccount source, BigDecimal amount, OffsetDateTime at,
                                 BankingRuleException.FailureContext ctx) {
        ZoneId zone = ZoneId.of(props.businessTimezone());
        OffsetDateTime startOfDay = at.atZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime();
        BigDecimal used = transactions.outflowSince(source.getId(), startOfDay);
        if (used.add(amount).compareTo(props.limits().customerDailyOutflow()) > 0) {
            throw new BankingRuleException("DAILY_LIMIT_EXCEEDED", "Daily online withdrawal/transfer limit of INR "
                    + props.limits().customerDailyOutflow().toPlainString() + " would be exceeded", ctx);
        }
    }

    private BankAccount resolve(String accountNumber, String label) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw ApiException.badRequest("ACCOUNT_REQUIRED", label + " number is required");
        }
        return accounts.findByAccountNumber(accountNumber.trim()).orElseThrow(() -> ApiException.notFound(label));
    }

    BigDecimal validateAmount(BigDecimal raw) {
        if (raw == null) throw ApiException.badRequest("INVALID_AMOUNT", "Amount is required");
        BigDecimal amount = raw.stripTrailingZeros();
        if (amount.scale() > 2) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount can have at most 2 decimal places");
        }
        if (amount.signum() <= 0) throw ApiException.badRequest("INVALID_AMOUNT", "Amount must be greater than zero");
        if (amount.compareTo(props.limits().maxSingleAmount()) > 0) {
            throw ApiException.badRequest("AMOUNT_TOO_LARGE",
                    "Amount exceeds the per-transaction limit of INR " + props.limits().maxSingleAmount().toPlainString());
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    static String cleanDescription(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\p{Cntrl}", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > 140 ? s.substring(0, 140) : s;
    }
}
