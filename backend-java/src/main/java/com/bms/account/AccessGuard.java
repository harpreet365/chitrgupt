package com.bms.account;

import com.bms.common.ApiException;
import com.bms.domain.BankAccount;
import com.bms.domain.CustomerProfile;
import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import com.bms.repo.BankAccountRepository;
import com.bms.repo.CustomerProfileRepository;
import com.bms.security.AppUserPrincipal;
import com.bms.security.CurrentUser;
import org.springframework.stereotype.Component;

/**
 * Server-side object-level authorisation. Customers are confined to their own records; anything they do
 * not own is reported as "not found" so that IDs belonging to other customers cannot be probed.
 */
@Component
public class AccessGuard {

    private final BankAccountRepository accounts;
    private final CustomerProfileRepository customers;

    public AccessGuard(BankAccountRepository accounts, CustomerProfileRepository customers) {
        this.accounts = accounts;
        this.customers = customers;
    }

    public BankAccount viewableAccount(Long accountId) {
        BankAccount a = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Account"));
        checkCanView(a);
        return a;
    }

    public void checkCanView(BankAccount a) {
        AppUserPrincipal me = CurrentUser.get();
        switch (me.role()) {
            case CUSTOMER -> {
                if (!a.getCustomer().getUser().getId().equals(me.id())) throw ApiException.notFound("Account");
            }
            case EMPLOYEE -> requireStaffView();
            case ADMIN -> { }
        }
    }

    public CustomerProfile viewableCustomer(Long customerId) {
        requireStaffView();
        return customers.findById(customerId).orElseThrow(() -> ApiException.notFound("Customer"));
    }

    public void requireStaffView() {
        AppUserPrincipal me = CurrentUser.get();
        if (me.role() == RoleName.ADMIN) return;
        if (me.role() == RoleName.EMPLOYEE && CurrentUser.hasPermission(Permission.CUSTOMER_VIEW)) return;
        throw ApiException.forbidden("Your account is not authorised to view customer records");
    }

    /** Administrators always; employees only with the given permission; customers never. */
    public void requireStaffPermission(Permission permission) {
        AppUserPrincipal me = CurrentUser.get();
        if (me.role() == RoleName.ADMIN) return;
        CurrentUser.requireEmployeePermission(permission);
    }
}
