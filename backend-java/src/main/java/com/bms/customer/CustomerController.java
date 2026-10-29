package com.bms.customer;

import com.bms.account.AccountViews.AccountView;
import com.bms.common.PageResponse;
import com.bms.common.Paging;
import com.bms.domain.AccountType;
import com.bms.domain.CustomerProfile;
import com.bms.repo.BankAccountRepository;
import com.bms.repo.LoginActivityRepository;
import com.bms.user.UserViews.LoginActivityView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Customer records for staff. Permission checks live in CustomerService/AccessGuard (server side). */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    public record RegisterCustomerRequest(
            @NotBlank @Size(max = 50) String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be 7-20 digits, spaces, + or -") String phone,
            @Past LocalDate dateOfBirth,
            @Size(max = 200) String addressLine,
            @Size(max = 80) String city,
            @Pattern(regexp = "^[A-Za-z0-9 \\-]{3,12}$", message = "must be 3-12 letters or digits") String postalCode,
            AccountType initialAccountType) {}

    public record UpdateCustomerRequest(
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be 7-20 digits, spaces, + or -") String phone,
            @Size(max = 200) String addressLine,
            @Size(max = 80) String city,
            @Pattern(regexp = "^[A-Za-z0-9 \\-]{3,12}$", message = "must be 3-12 letters or digits") String postalCode) {}

    public record CustomerSummary(Long id, String customerNumber, Long userId, String username, String fullName,
                                  String email, String phone, String status, OffsetDateTime createdAt, boolean demo) {
        static CustomerSummary of(CustomerProfile c) {
            var u = c.getUser();
            return new CustomerSummary(c.getId(), c.getCustomerNumber(), u.getId(), u.getUsername(), u.getFullName(),
                    u.getEmail(), c.getPhone(), u.getStatus().name(), c.getCreatedAt(), u.isDemo());
        }
    }

    public record CustomerDetail(CustomerSummary customer, LocalDate dateOfBirth, String addressLine, String city,
                                 String postalCode, OffsetDateTime lastLoginAt, OffsetDateTime lastFailedLoginAt,
                                 List<AccountView> accounts, List<LoginActivityView> recentLogins) {}

    public record RegisteredResponse(CustomerSummary customer, List<AccountView> accounts, String temporaryPassword) {}

    private final CustomerService service;
    private final BankAccountRepository accounts;
    private final LoginActivityRepository logins;

    public CustomerController(CustomerService service, BankAccountRepository accounts, LoginActivityRepository logins) {
        this.service = service;
        this.accounts = accounts;
        this.logins = logins;
    }

    @GetMapping
    public PageResponse<CustomerSummary> search(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of("createdAt"), "createdAt,desc");
        return PageResponse.of(service.search(q, pageable), CustomerSummary::of);
    }

    @GetMapping("/{id}")
    public CustomerDetail get(@PathVariable Long id) {
        CustomerProfile c = service.get(id);
        var accts = accounts.findByCustomer_IdOrderByOpenedAtAsc(id).stream().map(AccountView::of).toList();
        var recent = logins.findByUserId(c.getUser().getId(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))).map(LoginActivityView::of).getContent();
        return new CustomerDetail(CustomerSummary.of(c), c.getDateOfBirth(), c.getAddressLine(), c.getCity(),
                c.getPostalCode(), c.getUser().getLastLoginAt(), c.getUser().getLastFailedLoginAt(), accts, recent);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegisteredResponse register(@Valid @RequestBody RegisterCustomerRequest b) {
        var r = service.register(new CustomerService.NewCustomer(b.username(), b.email(), b.fullName(), b.phone(),
                b.dateOfBirth(), b.addressLine(), b.city(), b.postalCode(), b.initialAccountType()));
        var accts = accounts.findByCustomer_IdOrderByOpenedAtAsc(r.profile().getId()).stream().map(AccountView::of).toList();
        return new RegisteredResponse(CustomerSummary.of(r.profile()), accts, r.temporaryPassword());
    }

    @PutMapping("/{id}")
    public CustomerSummary update(@PathVariable Long id, @Valid @RequestBody UpdateCustomerRequest b) {
        return CustomerSummary.of(service.updateContact(id, b.email(), b.phone(), b.addressLine(), b.city(), b.postalCode()));
    }
}
