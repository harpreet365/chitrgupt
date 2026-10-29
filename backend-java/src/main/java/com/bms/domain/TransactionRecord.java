package com.bms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A completed or failed money movement. Rows are append-only (enforced by a database trigger). */
@Entity
@Immutable
@Table(name = "transactions")
public class TransactionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxnType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxnStatus status;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "INR";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private BankAccount sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private BankAccount destinationAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by")
    private User initiatedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    private String description;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(nullable = false)
    private boolean simulated = true;

    @Column(nullable = false)
    private boolean demo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected TransactionRecord() {}

    public TransactionRecord(String reference, TxnType type, TxnStatus status, BigDecimal amount,
                             BankAccount source, BankAccount destination, User initiatedBy, Channel channel,
                             String description, String failureReason, boolean demo, OffsetDateTime createdAt) {
        this.reference = reference;
        this.type = type;
        this.status = status;
        this.amount = amount;
        this.sourceAccount = source;
        this.destinationAccount = destination;
        this.initiatedBy = initiatedBy;
        this.channel = channel;
        this.description = description;
        this.failureReason = failureReason;
        this.demo = demo;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public TxnType getType() { return type; }
    public TxnStatus getStatus() { return status; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BankAccount getSourceAccount() { return sourceAccount; }
    public BankAccount getDestinationAccount() { return destinationAccount; }
    public User getInitiatedBy() { return initiatedBy; }
    public Channel getChannel() { return channel; }
    public String getDescription() { return description; }
    public String getFailureReason() { return failureReason; }
    public boolean isSimulated() { return simulated; }
    public boolean isDemo() { return demo; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
