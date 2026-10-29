package com.bms.txn;

import com.bms.domain.BankAccount;
import com.bms.domain.FlaggedTransaction;
import com.bms.domain.LedgerEntry;
import com.bms.domain.TransactionRecord;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

public final class TransactionViews {
    private TransactionViews() {}

    /**
     * @param direction relative to the viewer: DEBIT (money left the viewer's account), CREDIT, INTERNAL
     *                  (between the viewer's own accounts), or null for staff views without an account filter.
     */
    public record TransactionView(Long id, String reference, String type, String status, BigDecimal amount,
                                  String currency, String sourceAccountNumber, String destinationAccountNumber,
                                  String direction, String channel, String description, String failureReason,
                                  String initiatedBy, boolean simulated, boolean demo, boolean flagged,
                                  OffsetDateTime createdAt) {}

    public record LedgerEntryView(Long id, String accountNumber, String entryType, BigDecimal amount,
                                  BigDecimal balanceAfter, OffsetDateTime createdAt) {}

    public record FlagView(Long id, String transactionReference, Long transactionId, BigDecimal amount, String type,
                           String source, String reason, String flaggedBy, String status, String reviewedBy,
                           String reviewNote, OffsetDateTime createdAt, OffsetDateTime reviewedAt) {
        public static FlagView of(FlaggedTransaction f) {
            TransactionRecord t = f.getTransaction();
            return new FlagView(f.getId(), t.getReference(), t.getId(), t.getAmount(), t.getType().name(),
                    f.getSource().name(), f.getReason(), f.getFlaggedBy() == null ? "system" : f.getFlaggedBy().getUsername(),
                    f.getStatus().name(), f.getReviewedBy() == null ? null : f.getReviewedBy().getUsername(),
                    f.getReviewNote(), f.getCreatedAt(), f.getReviewedAt());
        }
    }

    public record TransactionDetail(TransactionView transaction, List<LedgerEntryView> ledgerEntries,
                                    List<FlagView> flags, String receiptNotice) {}

    /** Receipt returned by deposit/withdraw/transfer. balanceAfter is for the account the caller acted on. */
    public record Receipt(String reference, String type, String status, BigDecimal amount, String currency,
                          String sourceAccountNumber, String destinationAccountNumber, String description,
                          String channel, BigDecimal balanceAfter, boolean replayed, boolean simulated,
                          OffsetDateTime createdAt, String notice) {}

    public static final String SIMULATION_NOTICE =
            "Educational banking simulation - no real money was moved and no payment network was contacted.";

    /** Customers see counterparty accounts they do not own only in masked form. */
    public static String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) return accountNumber;
        return "XXXXXXXX" + accountNumber.substring(accountNumber.length() - 4);
    }

    static String number(BankAccount a, Set<Long> visibleIds, boolean maskOthers) {
        if (a == null) return null;
        if (!maskOthers || visibleIds.contains(a.getId())) return a.getAccountNumber();
        return mask(a.getAccountNumber());
    }

    static LedgerEntryView entry(LedgerEntry e, String accountNumber) {
        return new LedgerEntryView(e.getId(), accountNumber, e.getEntryType().name(), e.getAmount(), e.getBalanceAfter(),
                e.getCreatedAt());
    }
}
