package com.bms.txn;

import com.bms.account.AccessGuard;
import com.bms.audit.AuditService;
import com.bms.common.ApiException;
import com.bms.common.PageResponse;
import com.bms.domain.FlagSource;
import com.bms.domain.FlagStatus;
import com.bms.domain.FlaggedTransaction;
import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import com.bms.domain.TransactionRecord;
import com.bms.domain.User;
import com.bms.repo.FlaggedTransactionRepository;
import com.bms.repo.TransactionRepository;
import com.bms.repo.UserRepository;
import com.bms.security.AppUserPrincipal;
import com.bms.security.CurrentUser;
import com.bms.txn.TransactionViews.FlagView;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;

@Service
public class FlagService {

    private final FlaggedTransactionRepository flags;
    private final TransactionRepository transactions;
    private final UserRepository users;
    private final AccessGuard guard;
    private final AuditService audit;

    public FlagService(FlaggedTransactionRepository flags, TransactionRepository transactions, UserRepository users,
                       AccessGuard guard, AuditService audit) {
        this.flags = flags;
        this.transactions = transactions;
        this.users = users;
        this.guard = guard;
        this.audit = audit;
    }

    /** Employees (with TRANSACTION_FLAG) and administrators can queue a transaction for review. */
    @Transactional
    public FlagView flag(String reference, String reason) {
        guard.requireStaffPermission(Permission.TRANSACTION_FLAG);
        TransactionRecord t = transactions.findByReference(reference.trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("Transaction"));
        if (flags.existsByTransaction_IdAndStatus(t.getId(), FlagStatus.OPEN)) {
            throw ApiException.conflict("ALREADY_FLAGGED", "This transaction already has an open review flag");
        }
        User me = users.getReferenceById(CurrentUser.get().id());
        FlaggedTransaction f = flags.save(new FlaggedTransaction(t, FlagSource.MANUAL, reason.trim(), me, OffsetDateTime.now()));
        audit.record("TRANSACTION_FLAGGED", "TRANSACTION", t.getReference(), Map.of("flagId", f.getId(), "reason", reason.trim()));
        return FlagView.of(f);
    }

    /** Administrators see every flag; employees see the flags they raised. */
    @Transactional(readOnly = true)
    public PageResponse<FlagView> list(Collection<FlagStatus> statuses, Pageable pageable) {
        AppUserPrincipal me = CurrentUser.get();
        Collection<FlagStatus> st = statuses == null || statuses.isEmpty() ? EnumSet.allOf(FlagStatus.class) : statuses;
        var page = me.role() == RoleName.EMPLOYEE
                ? flags.findByFlaggedBy_IdAndStatusIn(me.id(), st, pageable)
                : flags.findByStatusIn(st, pageable);
        return PageResponse.of(page, FlagView::of);
    }

    @Transactional
    public FlagView review(Long flagId, FlagStatus outcome, String note) {
        if (outcome == FlagStatus.OPEN) {
            throw ApiException.badRequest("INVALID_REVIEW", "A review must resolve or dismiss the flag");
        }
        FlaggedTransaction f = flags.findById(flagId).orElseThrow(() -> ApiException.notFound("Flag"));
        if (f.getStatus() != FlagStatus.OPEN) {
            throw ApiException.conflict("ALREADY_REVIEWED", "This flag has already been reviewed");
        }
        f.review(outcome, users.getReferenceById(CurrentUser.get().id()), note == null ? null : note.trim());
        audit.record("FLAG_" + outcome.name(), "TRANSACTION", f.getTransaction().getReference(),
                Map.of("flagId", f.getId(), "note", note == null ? "" : note.trim()));
        flags.flush();
        return FlagView.of(flags.findById(flagId).orElseThrow());
    }
}
