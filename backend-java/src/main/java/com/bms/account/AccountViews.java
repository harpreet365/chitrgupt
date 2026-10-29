package com.bms.account;

import com.bms.domain.BankAccount;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public final class AccountViews {
    private AccountViews() {}

    public record AccountView(Long id, String accountNumber, String accountType, String currency, BigDecimal balance,
                              String status, Long customerId, String customerNumber, String holderName,
                              OffsetDateTime openedAt, OffsetDateTime closedAt, boolean demo) {
        public static AccountView of(BankAccount a) {
            return new AccountView(a.getId(), a.getAccountNumber(), a.getAccountType().name(), a.getCurrency(),
                    a.getBalance(), a.getStatus().name(), a.getCustomer().getId(), a.getCustomer().getCustomerNumber(),
                    a.getCustomer().getUser().getFullName(), a.getOpenedAt(), a.getClosedAt(), a.isDemo());
        }
    }
}
