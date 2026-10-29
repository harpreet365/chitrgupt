package com.bms.txn;

import com.bms.common.ApiException;
import com.bms.common.PageResponse;
import com.bms.common.Paging;
import com.bms.domain.FlagStatus;
import com.bms.domain.TxnStatus;
import com.bms.domain.TxnType;
import com.bms.txn.TransactionViews.FlagView;
import com.bms.txn.TransactionViews.Receipt;
import com.bms.txn.TransactionViews.TransactionDetail;
import com.bms.txn.TransactionViews.TransactionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private static final String ACCOUNT_PATTERN = "^\\d{12}$";
    private static final String ACCOUNT_MESSAGE = "must be a 12-digit account number";

    public record DepositRequest(
            @NotBlank @Pattern(regexp = ACCOUNT_PATTERN, message = ACCOUNT_MESSAGE) String accountNumber,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 140) String description) {}

    public record WithdrawalRequest(
            @NotBlank @Pattern(regexp = ACCOUNT_PATTERN, message = ACCOUNT_MESSAGE) String accountNumber,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 140) String description) {}

    public record TransferRequest(
            @NotBlank @Pattern(regexp = ACCOUNT_PATTERN, message = ACCOUNT_MESSAGE) String sourceAccountNumber,
            @NotBlank @Pattern(regexp = ACCOUNT_PATTERN, message = ACCOUNT_MESSAGE) String destinationAccountNumber,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 140) String description) {}

    public record FlagRequest(@NotBlank @Size(min = 5, max = 300) String reason) {}

    public record ReviewRequest(@NotNull FlagStatus status, @Size(max = 300) String note) {}

    private final BankingFacade banking;
    private final TransactionQueryService queries;
    private final FlagService flags;

    public TransactionController(BankingFacade banking, TransactionQueryService queries, FlagService flags) {
        this.banking = banking;
        this.queries = queries;
        this.flags = flags;
    }

    @GetMapping("/transactions")
    public PageResponse<TransactionView> search(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String accountNumber,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) TxnType type,
            @RequestParam(required = false) TxnStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String reference,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var pageable = Paging.of(page, size, sort, Set.of("createdAt", "amount"), "createdAt,desc");
        var filter = new TransactionQueryService.Filter(accountId, accountNumber, customerId, type, status, from, to,
                minAmount, maxAmount, reference, mine);
        return queries.search(filter, pageable);
    }

    @GetMapping("/transactions/{reference}")
    public TransactionDetail detail(@PathVariable String reference) {
        return queries.detail(reference);
    }

    @PostMapping("/transactions/deposits")
    public ResponseEntity<Receipt> deposit(@Valid @RequestBody DepositRequest body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return respond(new MoneyCommand(TxnType.DEPOSIT, null, body.accountNumber(), body.amount(), body.description(), key));
    }

    @PostMapping("/transactions/withdrawals")
    public ResponseEntity<Receipt> withdraw(@Valid @RequestBody WithdrawalRequest body,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return respond(new MoneyCommand(TxnType.WITHDRAWAL, body.accountNumber(), null, body.amount(), body.description(), key));
    }

    /** Transfers require an Idempotency-Key so that a retried request can never move money twice. */
    @PostMapping("/transactions/transfers")
    public ResponseEntity<Receipt> transfer(@Valid @RequestBody TransferRequest body,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Transfers require an Idempotency-Key header");
        }
        return respond(new MoneyCommand(TxnType.TRANSFER, body.sourceAccountNumber(), body.destinationAccountNumber(),
                body.amount(), body.description(), key));
    }

    @PostMapping("/transactions/{reference}/flags")
    @ResponseStatus(HttpStatus.CREATED)
    public FlagView flag(@PathVariable String reference, @Valid @RequestBody FlagRequest body) {
        return flags.flag(reference, body.reason());
    }

    @GetMapping("/staff/flags")
    public PageResponse<FlagView> listFlags(@RequestParam(required = false) List<FlagStatus> status,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return flags.list(status, Paging.of(page, size, null, Set.of("createdAt"), "createdAt,desc"));
    }

    @PatchMapping("/admin/flags/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public FlagView review(@PathVariable Long id, @Valid @RequestBody ReviewRequest body) {
        return flags.review(id, body.status(), body.note());
    }

    private ResponseEntity<Receipt> respond(MoneyCommand cmd) {
        String key = cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank() ? null : cmd.idempotencyKey().trim();
        MoneyCommand normalised = new MoneyCommand(cmd.type(), cmd.sourceAccountNumber(), cmd.destinationAccountNumber(),
                cmd.amount(), cmd.description(), key);
        var outcome = banking.execute(Actor.current(), normalised);
        Receipt receipt = queries.receipt(outcome);
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(outcome.replayed()))
                .body(receipt);
    }
}
