package com.bms.account;

import com.bms.audit.AuditService;
import com.bms.common.ApiException;
import com.bms.domain.AccountStatus;
import com.bms.domain.AccountType;
import com.bms.domain.BankAccount;
import com.bms.domain.CustomerProfile;
import com.bms.domain.Permission;
import com.bms.domain.UserStatus;
import com.bms.repo.BankAccountRepository;
import com.bms.repo.CustomerProfileRepository;
import com.bms.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AccountService {

    private final BankAccountRepository accounts;
    private final CustomerProfileRepository customers;
    private final AccessGuard guard;
    private final AuditService audit;

    public AccountService(BankAccountRepository accounts, CustomerProfileRepository customers, AccessGuard guard,
                          AuditService audit) {
        this.accounts = accounts;
        this.customers = customers;
        this.guard = guard;
        this.audit = audit;
    }

    /** Opens an account with a zero balance. Funds can only arrive later through recorded transactions. */
    @Transactional
    public BankAccount open(Long customerId, AccountType type) {
        guard.requireStaffPermission(Permission.ACCOUNT_CREATE);
        return openInternal(customerId, type, CurrentUser.get().id(), false);
    }

    @Transactional
    public BankAccount openInternal(Long customerId, AccountType type, Long openedBy, boolean demo) {
        CustomerProfile c = customers.findById(customerId).orElseThrow(() -> ApiException.notFound("Customer"));
        if (c.getUser().getStatus() != UserStatus.ACTIVE) {
            throw ApiException.unprocessable("CUSTOMER_INACTIVE", "Accounts can only be opened for active customers");
        }
        String number = AccountNumbers.fromSequence(accounts.nextAccountSequence());
        BankAccount a = accounts.saveAndFlush(new BankAccount(number, c, type, openedBy, demo));
        audit.record("ACCOUNT_OPENED", "ACCOUNT", a.getId(),
                Map.of("accountNumber", number, "type", type.name(), "customerNumber", c.getCustomerNumber()));
        return accounts.findById(a.getId()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<BankAccount> mine() {
        Long userId = CurrentUser.get().id();
        return customers.findByUser_Id(userId)
                .map(c -> accounts.findByCustomer_IdOrderByOpenedAtAsc(c.getId()))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<BankAccount> forCustomer(Long customerId) {
        guard.viewableCustomer(customerId);
        return accounts.findByCustomer_IdOrderByOpenedAtAsc(customerId);
    }

    @Transactional(readOnly = true)
    public Page<BankAccount> search(String q, AccountStatus status, AccountType type, Pageable pageable) {
        guard.requireStaffView();
        Specification<BankAccount> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String term = q.trim();
                var user = root.join("customer").join("user");
                ps.add(cb.or(cb.equal(root.get("accountNumber"), term),
                        cb.equal(cb.upper(root.get("customer").get("customerNumber")), term.toUpperCase()),
                        cb.like(cb.lower(user.get("fullName")), "%" + term.toLowerCase() + "%")));
            }
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (type != null) ps.add(cb.equal(root.get("accountType"), type));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return accounts.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public BankAccount get(Long id) {
        return guard.viewableAccount(id);
    }

    /** Administrator-only status management. A closed account cannot be reopened and must have zero balance. */
    @Transactional
    public BankAccount setStatus(Long id, AccountStatus status, String reason) {
        BankAccount a = accounts.lockById(id).orElseThrow(() -> ApiException.notFound("Account"));
        AccountStatus old = a.getStatus();
        if (old == status) return a;
        if (old == AccountStatus.CLOSED) {
            throw ApiException.unprocessable("ACCOUNT_CLOSED", "A closed account cannot be reopened");
        }
        if (status == AccountStatus.CLOSED && a.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw ApiException.unprocessable("BALANCE_NOT_ZERO", "Only accounts with a zero balance can be closed");
        }
        a.setStatus(status);
        if (status == AccountStatus.CLOSED) a.setClosedAt(OffsetDateTime.now());
        audit.record("ACCOUNT_" + (status == AccountStatus.ACTIVE ? "ACTIVATED" : status.name()), "ACCOUNT", id,
                Map.of("accountNumber", a.getAccountNumber(), "from", old.name(), "to", status.name(),
                        "reason", reason == null ? "" : reason));
        return a;
    }
}
