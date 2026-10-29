package com.bms.txn;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Entry point for money operations. Deliberately NOT transactional: it lets the money transaction roll back
 * first, then records the failure in a fresh transaction (so the failed record cannot be lost or block on
 * row locks held by the rolled-back attempt).
 */
@Service
public class BankingFacade {

    private final BankingService banking;
    private final FailureRecorder failures;

    public BankingFacade(BankingService banking, FailureRecorder failures) {
        this.banking = banking;
        this.failures = failures;
    }

    public BankingService.Outcome execute(Actor actor, MoneyCommand cmd) {
        return execute(actor, cmd, OffsetDateTime.now(), false);
    }

    public BankingService.Outcome execute(Actor actor, MoneyCommand cmd, OffsetDateTime at, boolean demo) {
        try {
            return banking.execute(actor, cmd, at, demo);
        } catch (BankingRuleException ex) {
            ex.setFailedReference(failures.record(ex, at));
            throw ex;
        }
    }
}
