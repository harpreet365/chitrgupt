package com.bms.txn;

import com.bms.domain.TxnType;

import java.math.BigDecimal;

public record MoneyCommand(TxnType type, String sourceAccountNumber, String destinationAccountNumber,
                           BigDecimal amount, String description, String idempotencyKey) {}
