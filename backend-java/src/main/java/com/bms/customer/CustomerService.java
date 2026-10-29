package com.bms.customer;

import com.bms.account.AccessGuard;
import com.bms.account.AccountService;
import com.bms.audit.AuditService;
import com.bms.auth.PasswordPolicy;
import com.bms.common.ApiException;
import com.bms.domain.AccountType;
import com.bms.domain.CustomerProfile;
import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import com.bms.domain.User;
import com.bms.repo.CustomerProfileRepository;
import com.bms.security.CurrentUser;
import com.bms.user.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CustomerService {

    public record NewCustomer(String username, String email, String fullName, String phone, LocalDate dateOfBirth,
                              String addressLine, String city, String postalCode, AccountType initialAccountType) {}

    public record Registered(CustomerProfile profile, String temporaryPassword) {}

    private final CustomerProfileRepository customers;
    private final UserService users;
    private final AccountService accounts;
    private final AccessGuard guard;
    private final AuditService audit;

    public CustomerService(CustomerProfileRepository customers, UserService users, AccountService accounts,
                           AccessGuard guard, AuditService audit) {
        this.customers = customers;
        this.users = users;
        this.accounts = accounts;
        this.guard = guard;
        this.audit = audit;
    }

    /** Staff onboarding: creates the customer login with a one-time password that must be changed at first sign-in. */
    @Transactional
    public Registered register(NewCustomer req) {
        guard.requireStaffPermission(Permission.CUSTOMER_REGISTER);
        String temp = PasswordPolicy.generateTemporary();
        CustomerProfile profile = createProfile(req, temp, true, false, CurrentUser.get().id());
        if (req.initialAccountType() != null) {
            accounts.openInternal(profile.getId(), req.initialAccountType(), CurrentUser.get().id(), false);
        }
        return new Registered(profile, temp);
    }

    @Transactional
    public CustomerProfile createProfile(NewCustomer req, String password, boolean mustChange, boolean demo,
                                         Long createdBy) {
        if (req.dateOfBirth() != null && req.dateOfBirth().isAfter(LocalDate.now().minusYears(18))) {
            throw ApiException.badRequest("UNDERAGE", "Customers must be at least 18 years old in this simulation");
        }
        User user = users.createUser(req.username(), req.email(), req.fullName(), password, RoleName.CUSTOMER,
                mustChange, demo);
        String number = "CUS" + customers.nextCustomerNumber();
        CustomerProfile p = new CustomerProfile(user, number, createdBy);
        p.setPhone(trim(req.phone()));
        p.setDateOfBirth(req.dateOfBirth());
        p.setAddressLine(trim(req.addressLine()));
        p.setCity(trim(req.city()));
        p.setPostalCode(trim(req.postalCode()));
        p = customers.saveAndFlush(p);
        audit.record("CUSTOMER_REGISTERED", "CUSTOMER", p.getId(),
                Map.of("customerNumber", number, "username", user.getUsername()));
        return p;
    }

    @Transactional(readOnly = true)
    public Page<CustomerProfile> search(String q, Pageable pageable) {
        guard.requireStaffView();
        return customers.search(q == null ? "" : q.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public CustomerProfile get(Long id) {
        return guard.viewableCustomer(id);
    }

    @Transactional
    public CustomerProfile updateContact(Long id, String email, String phone, String addressLine, String city,
                                         String postalCode) {
        guard.requireStaffPermission(Permission.CUSTOMER_REGISTER);
        CustomerProfile p = customers.findById(id).orElseThrow(() -> ApiException.notFound("Customer"));
        Map<String, Object> changed = new LinkedHashMap<>();
        if (email != null && !email.equalsIgnoreCase(p.getUser().getEmail())) {
            users.update(p.getUser().getId(), null, email);
            changed.put("email", "updated");
        }
        if (phone != null) { p.setPhone(trim(phone)); changed.put("phone", "updated"); }
        if (addressLine != null) { p.setAddressLine(trim(addressLine)); changed.put("addressLine", "updated"); }
        if (city != null) { p.setCity(trim(city)); changed.put("city", "updated"); }
        if (postalCode != null) { p.setPostalCode(trim(postalCode)); changed.put("postalCode", "updated"); }
        if (!changed.isEmpty()) audit.record("CUSTOMER_UPDATED", "CUSTOMER", id, changed);
        return p;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
