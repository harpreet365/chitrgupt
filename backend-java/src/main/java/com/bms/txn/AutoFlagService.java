package com.bms.txn;

import com.bms.audit.AuditService;
import com.bms.config.BmsProperties;
import com.bms.domain.BankAccount;
import com.bms.domain.FlagSource;
import com.bms.domain.FlaggedTransaction;
import com.bms.domain.TransactionRecord;
import com.bms.repo.FlaggedTransactionRepository;
import com.bms.repo.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Simple, explainable rules that queue unusual completed transactions for administrator review. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AutoFlagService {

    private final BmsProperties props;
    private final TransactionRepository transactions;
    private final FlaggedTransactionRepository flags;
    private final AuditService audit;

    public AutoFlagService(BmsProperties props, TransactionRepository transactions, FlaggedTransactionRepository flags,
                           AuditService audit) {
        this.props = props;
        this.transactions = transactions;
        this.flags = flags;
        this.audit = audit;
    }

    public void evaluate(TransactionRecord txn, BankAccount source, OffsetDateTime at) {
        var cfg = props.flagging();
        List<String> reasons = new ArrayList<>();
        if (txn.getAmount().compareTo(cfg.largeAmountThreshold()) >= 0) {
            reasons.add("Large amount (>= INR " + cfg.largeAmountThreshold().toPlainString() + ")");
        }
        if (source != null) {
            long recent = transactions.outflowCountSince(source.getId(), at.minus(cfg.rapidOutflowWindow()));
            if (recent >= cfg.rapidOutflowCount()) {
                reasons.add("Rapid outflows: " + recent + " debits within " + cfg.rapidOutflowWindow().toMinutes() + " minutes");
            }
        }
        if (reasons.isEmpty()) return;
        String reason = String.join("; ", reasons);
        FlaggedTransaction f = flags.save(new FlaggedTransaction(txn, FlagSource.AUTO, reason, null, at));
        audit.record("TRANSACTION_AUTO_FLAGGED", "TRANSACTION", txn.getReference(),
                Map.of("flagId", f.getId(), "reason", reason));
    }
}
