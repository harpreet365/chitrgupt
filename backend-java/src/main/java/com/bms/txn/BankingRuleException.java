package com.bms.txn;

import com.bms.domain.Channel;
import com.bms.domain.TxnType;

import java.math.BigDecimal;

/**
 * A business-rule rejection (insufficient funds, inactive account, limit exceeded) discovered after the
 * accounts were resolved and authorised. The surrounding database transaction is rolled back completely;
 * afterwards a FAILED transaction record is written in a separate transaction for traceability.
 */
public class BankingRuleException extends RuntimeException {

    public record FailureContext(TxnType type, BigDecimal amount, Long sourceAccountId, Long destinationAccountId,
                                 Long initiatorId, Channel channel, String description, boolean demo) {}

    private final String code;
    private final FailureContext context;
    private String failedReference;

    public BankingRuleException(String code, String message, FailureContext context) {
        super(message);
        this.code = code;
        this.context = context;
    }

    public String getCode() { return code; }
    public FailureContext getContext() { return context; }
    public String getFailedReference() { return failedReference; }
    void setFailedReference(String ref) { this.failedReference = ref; }
}
