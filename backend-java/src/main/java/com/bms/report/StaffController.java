package com.bms.report;

import com.bms.config.BmsProperties;
import com.bms.domain.AccountStatus;
import com.bms.domain.FlagStatus;
import com.bms.domain.TxnStatus;
import com.bms.repo.BankAccountRepository;
import com.bms.repo.FlaggedTransactionRepository;
import com.bms.repo.TransactionRepository;
import com.bms.security.CurrentUser;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Branch dashboard counters for employees (and admins). */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffController {

    private final TransactionRepository transactions;
    private final FlaggedTransactionRepository flags;
    private final BankAccountRepository accounts;
    private final ZoneId zone;

    public StaffController(TransactionRepository transactions, FlaggedTransactionRepository flags,
                           BankAccountRepository accounts, BmsProperties props) {
        this.transactions = transactions;
        this.flags = flags;
        this.accounts = accounts;
        this.zone = ZoneId.of(props.businessTimezone());
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Long me = CurrentUser.get().id();
        OffsetDateTime today = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime since24h = OffsetDateTime.now().minusHours(24);
        Specification<com.bms.domain.TransactionRecord> failed24h = (r, q, cb) -> cb.and(
                cb.equal(r.get("status"), TxnStatus.FAILED), cb.greaterThanOrEqualTo(r.get("createdAt"), since24h));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("myOperationsToday", transactions.countInitiatedSince(me, today));
        m.put("myOpenFlags", flags.countByFlaggedBy_IdAndStatus(me, FlagStatus.OPEN));
        m.put("openFlagsTotal", flags.countByStatus(FlagStatus.OPEN));
        m.put("suspendedAccounts", accounts.countByStatus(AccountStatus.SUSPENDED));
        m.put("failedTransactions24h", transactions.count(failed24h));
        return m;
    }
}
