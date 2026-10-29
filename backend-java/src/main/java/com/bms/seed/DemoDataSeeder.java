package com.bms.seed;

import com.bms.account.AccountService;
import com.bms.auth.PasswordPolicy;
import com.bms.common.ApiException;
import com.bms.config.BmsProperties;
import com.bms.customer.CustomerService;
import com.bms.domain.AccountType;
import com.bms.domain.BankAccount;
import com.bms.domain.CustomerProfile;
import com.bms.domain.FlagSource;
import com.bms.domain.FlaggedTransaction;
import com.bms.domain.LoginActivity;
import com.bms.domain.LoginOutcome;
import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import com.bms.domain.TxnType;
import com.bms.domain.User;
import com.bms.repo.FlaggedTransactionRepository;
import com.bms.repo.LoginActivityRepository;
import com.bms.repo.TransactionRepository;
import com.bms.repo.UserRepository;
import com.bms.security.AppUserPrincipal;
import com.bms.security.UserRefreshFilter;
import com.bms.txn.Actor;
import com.bms.txn.BankingFacade;
import com.bms.txn.BankingRuleException;
import com.bms.txn.MoneyCommand;
import com.bms.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * DEVELOPMENT ONLY (DEMO_SEED=true). Creates clearly labelled demonstration users, accounts and ~60 days of
 * history. All money movements go through the real BankingFacade, so balances, ledger entries, failures and
 * audit records are produced by the same code paths as live requests. A fixed random seed makes the data
 * reproducible (relative to the current date). Demo passwords come from DEMO_PASSWORD, never from code.
 */
@Component
@Order(2)
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String MARKER_USER = "employee.demo";
    private static final int HISTORY_DAYS = 60;

    private record DemoCustomer(String username, String fullName, String phone, String city, LocalDate dob, boolean current) {}

    private static final List<DemoCustomer> CUSTOMERS = List.of(
            new DemoCustomer("aarav.demo", "Aarav Sharma (Demo)", "+91 98100 00001", "New Delhi", LocalDate.of(1990, 4, 12), true),
            new DemoCustomer("priya.demo", "Priya Nair (Demo)", "+91 98100 00002", "Kochi", LocalDate.of(1987, 9, 3), false),
            new DemoCustomer("rohan.demo", "Rohan Mehta (Demo)", "+91 98100 00003", "Mumbai", LocalDate.of(1995, 1, 22), true),
            new DemoCustomer("ananya.demo", "Ananya Iyer (Demo)", "+91 98100 00004", "Chennai", LocalDate.of(1999, 7, 15), false),
            new DemoCustomer("vikram.demo", "Vikram Singh (Demo)", "+91 98100 00005", "Jaipur", LocalDate.of(1982, 11, 30), true),
            new DemoCustomer("meera.demo", "Meera Kapoor (Demo)", "+91 98100 00006", "Pune", LocalDate.of(1993, 2, 8), false));

    private final BmsProperties props;
    private final UserRepository users;
    private final UserService userService;
    private final CustomerService customerService;
    private final AccountService accountService;
    private final BankingFacade banking;
    private final TransactionRepository transactions;
    private final FlaggedTransactionRepository flags;
    private final LoginActivityRepository logins;

    public DemoDataSeeder(BmsProperties props, UserRepository users, UserService userService,
                          CustomerService customerService, AccountService accountService, BankingFacade banking,
                          TransactionRepository transactions, FlaggedTransactionRepository flags,
                          LoginActivityRepository logins) {
        this.props = props;
        this.users = users;
        this.userService = userService;
        this.customerService = customerService;
        this.accountService = accountService;
        this.banking = banking;
        this.transactions = transactions;
        this.flags = flags;
        this.logins = logins;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.demo().seed()) return;
        String password = props.demo().password();
        if (password == null || password.isBlank()) {
            log.warn("DEMO_SEED=true but DEMO_PASSWORD is empty - demo data not created");
            return;
        }
        if (users.existsByUsernameIgnoreCase(MARKER_USER)) {
            log.info("Demo data already present - skipping seeding");
            return;
        }
        try {
            PasswordPolicy.validate(password, null);
        } catch (ApiException e) {
            log.warn("DEMO_PASSWORD does not meet the password policy ({}) - demo data not created", e.getMessage());
            return;
        }
        log.info("Seeding demonstration data (DEMO_SEED=true)...");
        seed(password);
        log.info("Demonstration data created: 2 employees, {} customers, {} transactions",
                CUSTOMERS.size(), transactions.count());
    }

    private void seed(String password) {
        ZoneId zone = ZoneId.of(props.businessTimezone());
        Random rnd = new Random(42);

        User created = userService.createUser(MARKER_USER, "employee.demo@bank.example", "Neha Verma (Demo Employee)",
                password, RoleName.EMPLOYEE, false, true);
        final User employee = userService.setPermissions(created.getId(), java.util.EnumSet.allOf(Permission.class));
        userService.createUser("teller.demo", "teller.demo@bank.example", "Arjun Rao (Demo Teller)", password,
                RoleName.EMPLOYEE, false, true);
        Actor staff = Actor.of(employee);

        List<User> customerUsers = new ArrayList<>();
        List<List<BankAccount>> customerAccounts = new ArrayList<>();
        OffsetDateTime start = LocalDate.now(zone).minusDays(HISTORY_DAYS).atTime(10, 0).atZone(zone).toOffsetDateTime();

        runAs(employee, () -> {
            for (DemoCustomer dc : CUSTOMERS) {
                CustomerProfile p = customerService.createProfile(new CustomerService.NewCustomer(dc.username(),
                        dc.username() + "@mail.example", dc.fullName(), dc.phone(), dc.dob(), "Demo Street 1", dc.city(),
                        "110001", null), password, false, true, null);
                List<BankAccount> list = new ArrayList<>();
                list.add(accountService.openInternal(p.getId(), AccountType.SAVINGS, null, true));
                if (dc.current()) list.add(accountService.openInternal(p.getId(), AccountType.CURRENT, null, true));
                customerUsers.add(p.getUser());
                customerAccounts.add(list);
            }
        });

        // Opening balances, deposited at the branch
        runAs(employee, () -> {
            for (List<BankAccount> list : customerAccounts) {
                for (BankAccount a : list) {
                    BigDecimal amount = money(20_000 + rnd.nextInt(180_000));
                    exec(staff, new MoneyCommand(TxnType.DEPOSIT, null, a.getAccountNumber(), amount, "Opening deposit (demo)", null), start);
                }
            }
        });

        String[] notes = {"Groceries", "Rent share", "Electricity bill", "Dinner split", "Savings sweep", "Salary (demo)",
                "Mobile recharge", "Books", "Travel", "Gift"};
        for (int day = HISTORY_DAYS - 1; day >= 0; day--) {
            int count = 2 + rnd.nextInt(6);
            for (int i = 0; i < count; i++) {
                int c = rnd.nextInt(customerUsers.size());
                User cu = customerUsers.get(c);
                List<BankAccount> own = customerAccounts.get(c);
                BankAccount acct = own.get(rnd.nextInt(own.size()));
                OffsetDateTime at = LocalDate.now(zone).minusDays(day).atTime(9 + rnd.nextInt(10), rnd.nextInt(60))
                        .atZone(zone).toOffsetDateTime();
                if (at.isAfter(OffsetDateTime.now())) at = OffsetDateTime.now().minusMinutes(5 + rnd.nextInt(30));
                int kind = rnd.nextInt(10);
                String note = notes[rnd.nextInt(notes.length)];
                final OffsetDateTime when = at;
                if (kind < 3) {
                    BigDecimal amt = money(500 + rnd.nextInt(25_000));
                    boolean branch = rnd.nextBoolean();
                    runAs(branch ? employee : cu, () -> exec(branch ? staff : Actor.of(cu),
                            new MoneyCommand(TxnType.DEPOSIT, null, acct.getAccountNumber(), amt, note, null), when));
                } else if (kind < 6) {
                    BigDecimal amt = money(200 + rnd.nextInt(kind == 5 ? 90_000 : 12_000));
                    runAs(cu, () -> exec(Actor.of(cu),
                            new MoneyCommand(TxnType.WITHDRAWAL, acct.getAccountNumber(), null, amt, note, null), when));
                } else {
                    int other = rnd.nextInt(customerAccounts.size());
                    List<BankAccount> theirs = customerAccounts.get(other);
                    BankAccount dest = theirs.get(rnd.nextInt(theirs.size()));
                    if (dest.getId().equals(acct.getId())) continue;
                    BigDecimal amt = money(100 + rnd.nextInt(15_000));
                    runAs(cu, () -> exec(Actor.of(cu), new MoneyCommand(TxnType.TRANSFER, acct.getAccountNumber(),
                            dest.getAccountNumber(), amt, note, null), when));
                }
            }
        }

        // A large branch deposit (auto-flagged by the large-amount rule) and a manual review flag
        BankAccount big = customerAccounts.get(4).get(0);
        runAs(employee, () -> exec(staff, new MoneyCommand(TxnType.DEPOSIT, null, big.getAccountNumber(),
                money(150_000), "Property sale proceeds (demo)", null), OffsetDateTime.now().minusDays(2)));
        org.springframework.data.jpa.domain.Specification<com.bms.domain.TransactionRecord> withdrawals = (r, q, cb) -> cb.and(
                cb.equal(r.get("type"), TxnType.WITHDRAWAL), cb.equal(r.get("status"), com.bms.domain.TxnStatus.COMPLETED));
        transactions.findAll(withdrawals, org.springframework.data.domain.PageRequest.of(0, 1,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .stream().findFirst().ifPresent(t -> {
                    if (!flags.existsByTransaction_IdAndStatus(t.getId(), com.bms.domain.FlagStatus.OPEN)) {
                        flags.save(new FlaggedTransaction(t, FlagSource.MANUAL,
                                "Unusual cash withdrawal pattern reported by branch (demo)", employee,
                                OffsetDateTime.now().minusHours(20)));
                    }
                });

        // A few failed sign-in attempts so the login-activity screens have something to show
        OffsetDateTime now = OffsetDateTime.now();
        logins.save(new LoginActivity(customerUsers.get(0).getId(), "aarav.demo", LoginOutcome.BAD_CREDENTIALS,
                "127.0.0.1", "demo-seed", now.minusHours(5)));
        logins.save(new LoginActivity(null, "admin1", LoginOutcome.BAD_CREDENTIALS, "127.0.0.1", "demo-seed", now.minusHours(3)));
        logins.save(new LoginActivity(null, "root", LoginOutcome.BAD_CREDENTIALS, "127.0.0.1", "demo-seed", now.minusHours(3)));
    }

    private void exec(Actor actor, MoneyCommand cmd, OffsetDateTime at) {
        try {
            banking.execute(actor, cmd, at, true);
        } catch (BankingRuleException expected) {
            // e.g. insufficient funds: recorded as a FAILED transaction, which is realistic demo data
        }
    }

    private static BigDecimal money(int rupees) {
        return BigDecimal.valueOf(rupees).setScale(2, RoundingMode.UNNECESSARY);
    }

    private void runAs(User user, Runnable r) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AppUserPrincipal(user.getId(), user.getUsername(), user.roleName(), user.getSecurityStamp()),
                null, UserRefreshFilter.authoritiesOf(user)));
        SecurityContextHolder.setContext(ctx);
        try {
            r.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
