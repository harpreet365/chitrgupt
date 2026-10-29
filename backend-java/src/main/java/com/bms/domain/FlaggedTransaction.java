package com.bms.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "flagged_transactions")
public class FlaggedTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", updatable = false)
    private TransactionRecord transaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FlagSource source;

    @Column(nullable = false, updatable = false)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flagged_by", updatable = false)
    private User flaggedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlagStatus status = FlagStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Version
    private long version;

    protected FlaggedTransaction() {}

    public FlaggedTransaction(TransactionRecord txn, FlagSource source, String reason, User flaggedBy,
                              OffsetDateTime createdAt) {
        this.transaction = txn;
        this.source = source;
        this.reason = reason;
        this.flaggedBy = flaggedBy;
        this.createdAt = createdAt;
    }

    public void review(FlagStatus outcome, User reviewer, String note) {
        this.status = outcome;
        this.reviewedBy = reviewer;
        this.reviewNote = note;
        this.reviewedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public TransactionRecord getTransaction() { return transaction; }
    public FlagSource getSource() { return source; }
    public String getReason() { return reason; }
    public User getFlaggedBy() { return flaggedBy; }
    public FlagStatus getStatus() { return status; }
    public User getReviewedBy() { return reviewedBy; }
    public String getReviewNote() { return reviewNote; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
}
