package com.bms.txn;

import com.bms.account.AccessGuard;
import com.bms.common.ApiException;
import com.bms.common.PageResponse;
import com.bms.config.BmsProperties;
import com.bms.domain.BankAccount;
import com.bms.domain.Channel;
import com.bms.domain.LedgerEntry;
import com.bms.domain.RoleName;
import com.bms.domain.TransactionRecord;
import com.bms.domain.TxnStatus;
import com.bms.domain.TxnType;
import com.bms.repo.BankAccountRepository;
import com.bms.repo.FlaggedTransactionRepository;
import com.bms.repo.LedgerEntryRepository;
import com.bms.repo.TransactionRepository;
import com.bms.security.AppUserPrincipal;
import com.bms.security.CurrentUser;
import com.bms.txn.TransactionViews.FlagView;
import com.bms.txn.TransactionViews.LedgerEntryView;
import com.bms.txn.TransactionViews.Receipt;
import com.bms.txn.TransactionViews.TransactionDetail;
import com.bms.txn.TransactionViews.TransactionView;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TransactionQueryService {

    public record Filter(Long accountId, String accountNumber, Long customerId, TxnType type, TxnStatus status,
                         LocalDate from, LocalDate to, BigDecimal minAmount, BigDecimal maxAmount, String reference,
                         boolean initiatedByMe) {}

    private final TransactionRepository transactions;
    private final BankAccountRepository accounts;
    private final LedgerEntryRepository ledger;
    private final FlaggedTransactionRepository flags;
    private final AccessGuard guard;
    private final BmsProperties props;

    public TransactionQueryService(TransactionRepository transactions, BankAccountRepository accounts,
                                   LedgerEntryRepository ledger, FlaggedTransactionRepository flags, AccessGuard guard,
                                   BmsProperties props) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.ledger = ledger;
        this.flags = flags;
        this.guard = guard;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionView> search(Filter f, Pageable pageable) {
        AppUserPrincipal me = CurrentUser.get();
        boolean customer = me.role() == RoleName.CUSTOMER;
        Set<Long> scope = null;      // account ids the query is restricted to (customers)
        Set<Long> perspective = new HashSet<>();

        Long accountId = f.accountId();
        if (f.accountNumber() != null && !f.accountNumber().isBlank()) {
            BankAccount a = accounts.findByAccountNumber(f.accountNumber().trim()).orElse(null);
            if (a == null) return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0);
            accountId = a.getId();
        }

        if (customer) {
            scope = new HashSet<>(accounts.findIdsOwnedByUser(me.id()));
            if (accountId != null && !scope.contains(accountId)) throw ApiException.notFound("Account");
            perspective.addAll(scope);
        } else {
            guard.requireStaffView();
            if (accountId != null) perspective.add(accountId);
        }
        if (accountId != null) scope = Set.of(accountId);

        final Set<Long> restrict = scope;
        final Long customerId = customer ? null : f.customerId();
        ZoneId zone = ZoneId.of(props.businessTimezone());
        Specification<TransactionRecord> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (restrict != null) {
                if (restrict.isEmpty()) return cb.disjunction();
                ps.add(cb.or(root.get("sourceAccount").get("id").in(restrict),
                        root.get("destinationAccount").get("id").in(restrict)));
            }
            if (customerId != null) {
                var src = root.join("sourceAccount", jakarta.persistence.criteria.JoinType.LEFT);
                var dst = root.join("destinationAccount", jakarta.persistence.criteria.JoinType.LEFT);
                ps.add(cb.or(cb.equal(src.get("customer").get("id"), customerId),
                        cb.equal(dst.get("customer").get("id"), customerId)));
            }
            if (f.type() != null) ps.add(cb.equal(root.get("type"), f.type()));
            if (f.status() != null) ps.add(cb.equal(root.get("status"), f.status()));
            if (f.from() != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                    f.from().atStartOfDay(zone).toOffsetDateTime()));
            if (f.to() != null) ps.add(cb.lessThan(root.get("createdAt"),
                    f.to().plusDays(1).atStartOfDay(zone).toOffsetDateTime()));
            if (f.minAmount() != null) ps.add(cb.greaterThanOrEqualTo(root.get("amount"), f.minAmount()));
            if (f.maxAmount() != null) ps.add(cb.lessThanOrEqualTo(root.get("amount"), f.maxAmount()));
            if (f.reference() != null && !f.reference().isBlank()) {
                ps.add(cb.equal(root.get("reference"), f.reference().trim().toUpperCase()));
            }
            if (f.initiatedByMe()) ps.add(cb.equal(root.get("initiatedBy").get("id"), me.id()));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "'from' must not be after 'to'");
        }
        Page<TransactionRecord> page = transactions.findAll(spec, pageable);
        Set<Long> flagged = customer || page.isEmpty() ? Set.of()
                : flags.openFlagTransactionIds(page.getContent().stream().map(TransactionRecord::getId).toList());
        return PageResponse.of(page, t -> view(t, me, perspective, flagged.contains(t.getId())));
    }

    @Transactional(readOnly = true)
    public TransactionDetail detail(String reference) {
        AppUserPrincipal me = CurrentUser.get();
        TransactionRecord t = transactions.findByReference(reference.trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("Transaction"));
        boolean customer = me.role() == RoleName.CUSTOMER;
        Set<Long> perspective = new HashSet<>();
        if (customer) {
            Set<Long> own = new HashSet<>(accounts.findIdsOwnedByUser(me.id()));
            boolean involved = (t.getSourceAccount() != null && own.contains(t.getSourceAccount().getId()))
                    || (t.getDestinationAccount() != null && own.contains(t.getDestinationAccount().getId()));
            if (!involved) throw ApiException.notFound("Transaction");
            perspective.addAll(own);
        } else {
            guard.requireStaffView();
        }

        List<LedgerEntryView> entries = new ArrayList<>();
        Map<Long, String> numbers = new java.util.HashMap<>();
        if (t.getSourceAccount() != null) numbers.put(t.getSourceAccount().getId(), t.getSourceAccount().getAccountNumber());
        if (t.getDestinationAccount() != null) numbers.put(t.getDestinationAccount().getId(), t.getDestinationAccount().getAccountNumber());
        for (LedgerEntry e : ledger.findByTransactionIdOrderByIdAsc(t.getId())) {
            if (customer && !perspective.contains(e.getAccountId())) continue;   // never show others' balances
            entries.add(TransactionViews.entry(e, numbers.get(e.getAccountId())));
        }
        List<FlagView> flagViews = customer ? List.of()
                : flags.findByTransaction_IdOrderByCreatedAtDesc(t.getId()).stream().map(FlagView::of).toList();
        boolean open = flagViews.stream().anyMatch(fv -> fv.status().equals("OPEN"));
        return new TransactionDetail(view(t, me, perspective, open), entries, flagViews, TransactionViews.SIMULATION_NOTICE);
    }

    /** Builds the receipt for a just-executed (or replayed) operation, from the caller's perspective. */
    @Transactional(readOnly = true)
    public Receipt receipt(BankingService.Outcome outcome) {
        AppUserPrincipal me = CurrentUser.get();
        TransactionRecord t = transactions.findByReference(outcome.transaction().getReference()).orElseThrow();
        BankAccount acted = t.getType() == TxnType.DEPOSIT ? t.getDestinationAccount() : t.getSourceAccount();
        BigDecimal balanceAfter = outcome.balancesAfter().get(acted.getId());
        if (balanceAfter == null) {
            balanceAfter = ledger.findByTransactionIdOrderByIdAsc(t.getId()).stream()
                    .filter(e -> e.getAccountId().equals(acted.getId())).map(LedgerEntry::getBalanceAfter)
                    .findFirst().orElse(null);
        }
        boolean customer = me.role() == RoleName.CUSTOMER;
        Set<Long> own = customer ? new HashSet<>(accounts.findIdsOwnedByUser(me.id())) : Set.of();
        return new Receipt(t.getReference(), t.getType().name(), t.getStatus().name(), t.getAmount(), t.getCurrency(),
                TransactionViews.number(t.getSourceAccount(), own, customer),
                TransactionViews.number(t.getDestinationAccount(), own, customer),
                t.getDescription(), t.getChannel().name(), balanceAfter, outcome.replayed(), t.isSimulated(),
                t.getCreatedAt(), TransactionViews.SIMULATION_NOTICE);
    }

    private TransactionView view(TransactionRecord t, AppUserPrincipal me, Set<Long> perspective, boolean flagged) {
        boolean customer = me.role() == RoleName.CUSTOMER;
        Long src = t.getSourceAccount() == null ? null : t.getSourceAccount().getId();
        Long dst = t.getDestinationAccount() == null ? null : t.getDestinationAccount().getId();
        String direction = null;
        boolean srcMine = src != null && perspective.contains(src);
        boolean dstMine = dst != null && perspective.contains(dst);
        if (srcMine && dstMine) direction = "INTERNAL";
        else if (srcMine) direction = "DEBIT";
        else if (dstMine) direction = "CREDIT";

        String initiatedBy;
        if (!customer) initiatedBy = t.getInitiatedBy().getUsername();
        else if (t.getInitiatedBy().getId().equals(me.id())) initiatedBy = "You";
        else if (t.getChannel() == Channel.BRANCH) initiatedBy = "Bank branch";
        else initiatedBy = "Another customer";

        return new TransactionView(t.getId(), t.getReference(), t.getType().name(), t.getStatus().name(), t.getAmount(),
                t.getCurrency(), TransactionViews.number(t.getSourceAccount(), perspective, customer),
                TransactionViews.number(t.getDestinationAccount(), perspective, customer), direction,
                t.getChannel().name(), t.getDescription(), t.getFailureReason(), initiatedBy, t.isSimulated(), t.isDemo(),
                flagged, t.getCreatedAt());
    }
}
