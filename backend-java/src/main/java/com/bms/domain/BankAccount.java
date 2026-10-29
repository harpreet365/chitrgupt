package com.bms.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The balance column is read-only for JPA (insertable/updatable = false): no entity update,
 * request binding or admin edit can change it. Balances move only through {@code LedgerDao}, which applies
 * guarded SQL updates inside the same database transaction that writes the ledger entries.
 */
@Entity
@Table(name = "bank_accounts")
public class BankAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, updatable = false)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "customer_id", updatable = false)
    private CustomerProfile customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false)
    private AccountType accountType;

    @Column(nullable = false, updatable = false)
    private String currency = "INR";

    @Column(nullable = false, insertable = false, updatable = false)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "opened_by", updatable = false)
    private Long openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private OffsetDateTime openedAt = OffsetDateTime.now();

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(nullable = false, updatable = false)
    private boolean demo;

    @Version
    private long version;

    protected BankAccount() {}

    public BankAccount(String accountNumber, CustomerProfile customer, AccountType type, Long openedBy, boolean demo) {
        this.accountNumber = accountNumber;
        this.customer = customer;
        this.accountType = type;
        this.openedBy = openedBy;
        this.demo = demo;
    }

    @PreUpdate
    void touch() { this.updatedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public CustomerProfile getCustomer() { return customer; }
    public AccountType getAccountType() { return accountType; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance == null ? new BigDecimal("0.00") : balance; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public Long getOpenedBy() { return openedBy; }
    public OffsetDateTime getOpenedAt() { return openedAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(OffsetDateTime t) { this.closedAt = t; }
    public boolean isDemo() { return demo; }
}
