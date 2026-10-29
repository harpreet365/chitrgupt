package com.bms.domain;

/** Configurable permissions an administrator grants to individual employees. */
public enum Permission {
    CUSTOMER_VIEW,
    CUSTOMER_REGISTER,
    ACCOUNT_CREATE,
    CASH_DEPOSIT,
    CASH_WITHDRAWAL,
    TRANSFER,
    TRANSACTION_FLAG;

    /** Granted automatically to new employees; withdrawals and transfers need an explicit grant. */
    public static java.util.Set<Permission> employeeDefaults() {
        return java.util.EnumSet.of(CUSTOMER_VIEW, CUSTOMER_REGISTER, ACCOUNT_CREATE, CASH_DEPOSIT, TRANSACTION_FLAG);
    }
}
