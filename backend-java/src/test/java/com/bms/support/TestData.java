package com.bms.support;

import com.bms.account.AccountService;
import com.bms.customer.CustomerService;
import com.bms.domain.AccountType;
import com.bms.domain.BankAccount;
import com.bms.domain.CustomerProfile;
import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import com.bms.domain.TxnType;
import com.bms.domain.User;
import com.bms.repo.BankAccountRepository;
import com.bms.txn.Actor;
import com.bms.txn.BankingFacade;
import com.bms.txn.MoneyCommand;
import com.bms.user.UserService;
import org.springframework.boot.test.context.TestComponent;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates isolated users/accounts for each test (unique names, so tests never share state). */
@TestComponent
public class TestData {
    public static final String PASSWORD = "Str0ngPassw0rd!";
    private static final AtomicInteger SEQ = new AtomicInteger();

    public record Customer(User user, CustomerProfile profile, BankAccount account) {
        public String username() { return user.getUsername(); }
        public String accountNumber() { return account.getAccountNumber(); }
    }

    private final UserService users;
    private final CustomerService customers;
    private final AccountService accounts;
    private final BankingFacade banking;
    private final BankAccountRepository accountRepo;
    private User funder;

    public TestData(UserService users, CustomerService customers, AccountService accounts, BankingFacade banking,
                    BankAccountRepository accountRepo) {
        this.users = users;
        this.customers = customers;
        this.accounts = accounts;
        this.banking = banking;
        this.accountRepo = accountRepo;
    }

    public static String unique(String prefix) {
        return prefix + "." + System.nanoTime() % 1_000_000 + "." + SEQ.incrementAndGet();
    }

    public User admin() {
        return users.createUser(unique("admin"), unique("admin") + "@test.example", "Test Admin", PASSWORD,
                RoleName.ADMIN, false, false);
    }

    public User employee(Set<Permission> permissions) {
        User u = users.createUser(unique("emp"), unique("emp") + "@test.example", "Test Employee", PASSWORD,
                RoleName.EMPLOYEE, false, false);
        return users.setPermissions(u.getId(), permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions));
    }

    public Customer customer(String openingBalance) {
        String name = unique("cust");
        CustomerProfile p = customers.createProfile(new CustomerService.NewCustomer(name, name + "@test.example",
                "Test Customer " + name, null, null, null, null, null, null), PASSWORD, false, false, null);
        BankAccount a = accounts.openInternal(p.getId(), AccountType.SAVINGS, null, false);
        if (openingBalance != null && new BigDecimal(openingBalance).signum() > 0) {
            fund(a.getAccountNumber(), openingBalance);
        }
        return new Customer(p.getUser(), p, a);
    }

    public BankAccount secondAccount(Customer c) {
        return accounts.openInternal(c.profile().getId(), AccountType.CURRENT, null, false);
    }

    public void fund(String accountNumber, String amount) {
        banking.execute(Actor.of(funder()), new MoneyCommand(TxnType.DEPOSIT, null, accountNumber,
                new BigDecimal(amount), "test funding", null));
    }

    public BigDecimal balance(String accountNumber) {
        return accountRepo.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    private synchronized User funder() {
        if (funder == null) funder = employee(EnumSet.allOf(Permission.class));
        return funder;
    }
}
