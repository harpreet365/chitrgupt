package com.bms.txn;

import com.bms.audit.AuditService;
import com.bms.domain.BankAccount;
import com.bms.domain.TransactionRecord;
import com.bms.domain.TxnStatus;
import com.bms.domain.User;
import com.bms.repo.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a FAILED transaction after the money transaction rolled back (and released its locks).
 * Failed transactions never have ledger entries and never change balances.
 */
@Service
public class FailureRecorder {

    private final TransactionRepository transactions;
    private final AuditService audit;
    private final EntityManager em;

    public FailureRecorder(TransactionRepository transactions, AuditService audit, EntityManager em) {
        this.transactions = transactions;
        this.audit = audit;
        this.em = em;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String record(BankingRuleException ex, OffsetDateTime at) {
        var c = ex.getContext();
        BankAccount src = c.sourceAccountId() == null ? null : em.getReference(BankAccount.class, c.sourceAccountId());
        BankAccount dst = c.destinationAccountId() == null ? null : em.getReference(BankAccount.class, c.destinationAccountId());
        TransactionRecord t = transactions.save(new TransactionRecord(References.newReference(at), c.type(),
                TxnStatus.FAILED, c.amount(), src, dst, em.getReference(User.class, c.initiatorId()), c.channel(),
                c.description(), ex.getMessage(), c.demo(), at));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", t.getReference());
        details.put("code", ex.getCode());
        details.put("amount", c.amount().toPlainString());
        audit.record(c.type().name() + "_FAILED", "TRANSACTION", t.getReference(), details);
        return t.getReference();
    }
}
