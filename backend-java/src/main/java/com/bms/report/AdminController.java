package com.bms.report;

import com.bms.analytics.AnalyticsClient;
import com.bms.audit.AuditViews.AuditView;
import com.bms.common.ApiException;
import com.bms.common.PageResponse;
import com.bms.common.Paging;
import com.bms.config.BmsProperties;
import com.bms.domain.AccountStatus;
import com.bms.domain.AuditLog;
import com.bms.domain.FlagStatus;
import com.bms.domain.LoginActivity;
import com.bms.domain.LoginOutcome;
import com.bms.domain.RoleName;
import com.bms.domain.TxnStatus;
import com.bms.domain.UserStatus;
import com.bms.repo.AuditLogRepository;
import com.bms.repo.BankAccountRepository;
import com.bms.repo.FlaggedTransactionRepository;
import com.bms.repo.LoginActivityRepository;
import com.bms.repo.TransactionRepository;
import com.bms.repo.UserRepository;
import com.bms.user.UserViews.LoginActivityView;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository users;
    private final BankAccountRepository accounts;
    private final TransactionRepository transactions;
    private final FlaggedTransactionRepository flags;
    private final LoginActivityRepository logins;
    private final AuditLogRepository audit;
    private final ReconciliationService reconciliation;
    private final AnalyticsClient analytics;
    private final ZoneId zone;

    public AdminController(UserRepository users, BankAccountRepository accounts, TransactionRepository transactions,
                           FlaggedTransactionRepository flags, LoginActivityRepository logins, AuditLogRepository audit,
                           ReconciliationService reconciliation, AnalyticsClient analytics, BmsProperties props) {
        this.users = users;
        this.accounts = accounts;
        this.transactions = transactions;
        this.flags = flags;
        this.logins = logins;
        this.audit = audit;
        this.reconciliation = reconciliation;
        this.analytics = analytics;
        this.zone = ZoneId.of(props.businessTimezone());
    }

    /** Headline metrics computed directly from the authoritative database. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        OffsetDateTime today = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime last24h = OffsetDateTime.now().minusHours(24);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalCustomers", users.countByRole_Name(RoleName.CUSTOMER));
        m.put("activeCustomers", users.countByRole_NameAndStatus(RoleName.CUSTOMER, UserStatus.ACTIVE));
        m.put("totalEmployees", users.countByRole_Name(RoleName.EMPLOYEE));
        m.put("activeAccounts", accounts.countByStatus(AccountStatus.ACTIVE));
        m.put("suspendedAccounts", accounts.countByStatus(AccountStatus.SUSPENDED));
        m.put("totalDepositsHeld", accounts.totalDeposits());
        m.put("transactionCount", transactions.count());
        m.put("failedTransactionCount", transactions.countByStatus(TxnStatus.FAILED));
        m.put("transactionVolume", transactions.completedVolume());
        m.put("transactionsToday", transactions.countByCreatedAtGreaterThanEqual(today));
        m.put("volumeToday", transactions.completedVolumeSince(today));
        m.put("openFlags", flags.countByStatus(FlagStatus.OPEN));
        m.put("failedLogins24h", logins.countByOutcomeInAndCreatedAtGreaterThanEqual(
                EnumSet.of(LoginOutcome.BAD_CREDENTIALS, LoginOutcome.LOCKED, LoginOutcome.DISABLED), last24h));
        m.put("currency", "INR");
        m.put("generatedAt", OffsetDateTime.now());
        return m;
    }

    @GetMapping("/login-activity")
    public PageResponse<LoginActivityView> loginActivity(@RequestParam(required = false) String username,
                                                         @RequestParam(required = false) LoginOutcome outcome,
                                                         @RequestParam(required = false, defaultValue = "false") boolean failedOnly,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        Specification<LoginActivity> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (username != null && !username.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("usernameAttempted")), "%" + username.trim().toLowerCase(Locale.ROOT) + "%"));
            }
            if (outcome != null) ps.add(cb.equal(root.get("outcome"), outcome));
            if (failedOnly) ps.add(root.get("outcome").in(LoginOutcome.BAD_CREDENTIALS, LoginOutcome.LOCKED, LoginOutcome.DISABLED));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay(zone).toOffsetDateTime()));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        var pageable = Paging.of(page, size, null, Set.of("createdAt"), "createdAt,desc");
        return PageResponse.of(logins.findAll(spec, pageable), LoginActivityView::of);
    }

    @GetMapping("/audit-logs")
    public PageResponse<AuditView> auditLogs(@RequestParam(required = false) String actor,
                                             @RequestParam(required = false) String action,
                                             @RequestParam(required = false) String targetType,
                                             @RequestParam(required = false) String targetId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        Specification<AuditLog> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (actor != null && !actor.isBlank()) ps.add(cb.like(cb.lower(root.get("actorUsername")), "%" + actor.trim().toLowerCase(Locale.ROOT) + "%"));
            if (action != null && !action.isBlank()) ps.add(cb.like(root.get("action"), "%" + action.trim().toUpperCase(Locale.ROOT) + "%"));
            if (targetType != null && !targetType.isBlank()) ps.add(cb.equal(root.get("targetType"), targetType.trim().toUpperCase(Locale.ROOT)));
            if (targetId != null && !targetId.isBlank()) ps.add(cb.equal(root.get("targetId"), targetId.trim()));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay(zone).toOffsetDateTime()));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        var pageable = Paging.of(page, size, null, Set.of("createdAt"), "createdAt,desc");
        return PageResponse.of(audit.findAll(spec, pageable), AuditView::of);
    }

    @GetMapping("/reconciliation")
    public ReconciliationService.Result reconcile() {
        return reconciliation.run();
    }

    // ---- Python analytics (proxied; the browser never talks to the analytics service directly) ----

    @GetMapping("/analytics/summary")
    public JsonNode summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(zone) : to;
        return analytics.summary(from == null ? end.minusDays(29) : from, end);
    }

    @GetMapping("/analytics/periodic")
    public JsonNode periodic(@RequestParam(defaultValue = "daily") String period,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        checkPeriod(period);
        LocalDate end = to == null ? LocalDate.now(zone) : to;
        LocalDate start = from == null ? switch (period) {
            case "weekly" -> end.minusWeeks(11);
            case "monthly" -> end.minusMonths(11).withDayOfMonth(1);
            default -> end.minusDays(29);
        } : from;
        return analytics.periodic(period, start, end);
    }

    @GetMapping("/analytics/trends")
    public JsonNode trends(@RequestParam(defaultValue = "30") int days) {
        if (days < 7 || days > 365) throw ApiException.badRequest("INVALID_DAYS", "days must be between 7 and 365");
        return analytics.trends(days);
    }

    @GetMapping("/analytics/top-accounts")
    public JsonNode topAccounts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 50) throw ApiException.badRequest("INVALID_LIMIT", "limit must be between 1 and 50");
        LocalDate end = to == null ? LocalDate.now(zone) : to;
        return analytics.topAccounts(from == null ? end.minusDays(29) : from, end, limit);
    }

    @GetMapping("/reports")
    public JsonNode report(@RequestParam(defaultValue = "daily") String period,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        checkPeriod(period);
        return analytics.report(period, date == null ? LocalDate.now(zone) : date);
    }

    private static void checkPeriod(String period) {
        if (!Set.of("daily", "weekly", "monthly").contains(period)) {
            throw ApiException.badRequest("INVALID_PERIOD", "period must be daily, weekly or monthly");
        }
    }
}
