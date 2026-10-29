package com.bms.account;

import com.bms.account.AccountViews.AccountView;
import com.bms.analytics.AnalyticsClient;
import com.bms.common.PageResponse;
import com.bms.common.Paging;
import com.bms.domain.AccountStatus;
import com.bms.domain.AccountType;
import com.bms.domain.RoleName;
import com.bms.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class AccountController {

    public record OpenAccountRequest(@NotNull AccountType accountType) {}

    public record AccountStatusRequest(@NotNull AccountStatus status, @Size(max = 200) String reason) {}

    private final AccountService service;
    private final AnalyticsClient analytics;

    public AccountController(AccountService service, AnalyticsClient analytics) {
        this.service = service;
        this.analytics = analytics;
    }

    /** Customers: their own accounts. Staff: searchable list of all accounts (requires view permission). */
    @GetMapping("/accounts")
    public Object list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) AccountStatus status,
                       @RequestParam(required = false) AccountType type,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(required = false) String sort) {
        if (CurrentUser.is(RoleName.CUSTOMER)) {
            List<AccountView> mine = service.mine().stream().map(AccountView::of).toList();
            return Map.of("content", mine);
        }
        var pageable = Paging.of(page, size, sort, Set.of("openedAt", "balance", "accountNumber"), "openedAt,desc");
        return PageResponse.of(service.search(q, status, type, pageable), AccountView::of);
    }

    @GetMapping("/accounts/{id}")
    public AccountView get(@PathVariable Long id) {
        return AccountView.of(service.get(id));
    }

    /** Account activity summary computed by the Python analytics service, after Java's ownership check. */
    @GetMapping("/accounts/{id}/analytics")
    public Object analytics(@PathVariable Long id,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var account = service.get(id);
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return analytics.accountSummary(account.getId(), start, end);
    }

    @GetMapping("/customers/{customerId}/accounts")
    public List<AccountView> forCustomer(@PathVariable Long customerId) {
        return service.forCustomer(customerId).stream().map(AccountView::of).toList();
    }

    @PostMapping("/customers/{customerId}/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView open(@PathVariable Long customerId, @Valid @RequestBody OpenAccountRequest body) {
        return AccountView.of(service.open(customerId, body.accountType()));
    }

    @PatchMapping("/admin/accounts/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountView setStatus(@PathVariable Long id, @Valid @RequestBody AccountStatusRequest body) {
        return AccountView.of(service.setStatus(id, body.status(), body.reason()));
    }
}
