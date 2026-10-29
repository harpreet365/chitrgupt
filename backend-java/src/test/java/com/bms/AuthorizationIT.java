package com.bms;

import com.bms.domain.Permission;
import com.bms.domain.User;
import com.bms.support.ApiClient;
import com.bms.support.IntegrationTest;
import com.bms.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Object-level and function-level authorisation, enforced by the backend regardless of the UI. */
@IntegrationTest
class AuthorizationIT {

    @LocalServerPort int port;
    @Autowired TestData data;

    private ApiClient as(String username) {
        ApiClient api = new ApiClient(port);
        assertThat(api.login(username, TestData.PASSWORD).status()).isEqualTo(200);
        return api;
    }

    @Test
    void customersCannotReachAdminOrStaffFunctions() {
        var c = data.customer("0");
        ApiClient api = as(c.username());
        assertThat(api.get("/api/v1/admin/stats").status()).isEqualTo(403);
        assertThat(api.get("/api/v1/admin/users").status()).isEqualTo(403);
        assertThat(api.get("/api/v1/admin/audit-logs").status()).isEqualTo(403);
        assertThat(api.get("/api/v1/staff/dashboard").status()).isEqualTo(403);
        assertThat(api.get("/api/v1/customers").status()).isEqualTo(403);
        assertThat(api.post("/api/v1/customers", Map.of("username", "x.y.z", "email", "x@y.example", "fullName", "X")).status())
                .isEqualTo(403);
    }

    @Test
    void employeesCannotUseAdministratorFunctionsOrEscalatePrivileges() {
        User emp = data.employee(EnumSet.allOf(Permission.class));
        ApiClient api = as(emp.getUsername());
        assertThat(api.get("/api/v1/admin/users").status()).isEqualTo(403);
        assertThat(api.patch("/api/v1/admin/users/" + emp.getId() + "/role", Map.of("role", "ADMIN")).status()).isEqualTo(403);
        assertThat(api.put("/api/v1/admin/users/" + emp.getId() + "/permissions", Map.of("permissions", java.util.List.of())).status())
                .isEqualTo(403);
        assertThat(api.get("/api/v1/admin/login-activity").status()).isEqualTo(403);
        assertThat(api.get("/api/v1/staff/dashboard").status()).isEqualTo(200);
    }

    @Test
    void customerCannotAccessAnotherCustomersAccountsTransactionsOrReceipts() {
        var alice = data.customer("500.00");
        var bob = data.customer("500.00");
        ApiClient bobApi = as(bob.username());
        String bobRef = bobApi.post("/api/v1/transactions/withdrawals",
                Map.of("accountNumber", bob.accountNumber(), "amount", "10.00")).text("reference");

        ApiClient api = as(alice.username());
        assertThat(api.get("/api/v1/accounts/" + bob.account().getId()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/transactions?accountId=" + bob.account().getId()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/transactions?accountNumber=" + bob.accountNumber()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/transactions/" + bobRef).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/customers/" + bob.profile().getId()).status()).isEqualTo(403);
        var steal = api.post("/api/v1/transactions/withdrawals", Map.of("accountNumber", bob.accountNumber(), "amount", "10.00"));
        assertThat(steal.status()).isEqualTo(404);
        var pull = api.post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", bob.accountNumber(),
                "destinationAccountNumber", alice.accountNumber(), "amount", "10.00"), UUID.randomUUID().toString());
        assertThat(pull.status()).isEqualTo(404);
        assertThat(data.balance(bob.accountNumber())).isEqualByComparingTo("490.00");

        var mine = api.get("/api/v1/accounts");
        assertThat(mine.body().path("content")).hasSize(1);
        assertThat(mine.body().path("content").get(0).path("accountNumber").asText()).isEqualTo(alice.accountNumber());
    }

    @Test
    void employeePermissionsAreEnforcedPerOperation() {
        var c = data.customer("1000.00");
        User limited = data.employee(EnumSet.of(Permission.CUSTOMER_VIEW, Permission.CASH_DEPOSIT));
        ApiClient api = as(limited.getUsername());
        assertThat(api.post("/api/v1/transactions/deposits", Map.of("accountNumber", c.accountNumber(), "amount", "50.00")).status())
                .isEqualTo(201);
        assertThat(api.post("/api/v1/transactions/withdrawals", Map.of("accountNumber", c.accountNumber(), "amount", "50.00")).status())
                .isEqualTo(403);
        var other = data.customer("0");
        assertThat(api.post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", c.accountNumber(),
                "destinationAccountNumber", other.accountNumber(), "amount", "5.00"), UUID.randomUUID().toString()).status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/customers/" + c.profile().getId() + "/accounts", Map.of("accountType", "CURRENT")).status())
                .isEqualTo(403);
        assertThat(data.balance(c.accountNumber())).isEqualByComparingTo("1050.00");

        User noView = data.employee(EnumSet.noneOf(Permission.class));
        assertThat(as(noView.getUsername()).get("/api/v1/customers/" + c.profile().getId()).status()).isEqualTo(403);
    }

    @Test
    void administratorsCannotMoveMoney() {
        var c = data.customer("100.00");
        User admin = data.admin();
        var res = as(admin.getUsername()).post("/api/v1/transactions/deposits", Map.of("accountNumber", c.accountNumber(), "amount", "1.00"));
        assertThat(res.status()).isEqualTo(403);
        assertThat(data.balance(c.accountNumber())).isEqualByComparingTo("100.00");
    }

    @Test
    void unknownFieldsSuchAsBalanceOrRoleAreRejectedNotSilentlyApplied() {
        var c = data.customer("100.00");
        ApiClient api = as(c.username());
        var res = api.postRaw("/api/v1/transactions/deposits",
                "{\"accountNumber\":\"" + c.accountNumber() + "\",\"amount\":\"1.00\",\"balance\":\"999999\"}");
        assertThat(res.status()).isEqualTo(400);
        var profile = api.put("/api/v1/me/profile", Map.of("role", "ADMIN"));
        assertThat(profile.status()).isEqualTo(400);
        assertThat(api.get("/api/v1/me").text("role")).isEqualTo("CUSTOMER");
        assertThat(data.balance(c.accountNumber())).isEqualByComparingTo("100.00");
    }

    @Test
    void customersSeeCounterpartyAccountsOnlyMasked() {
        var alice = data.customer("300.00");
        var bob = data.customer("0");
        ApiClient api = as(alice.username());
        var receipt = api.post("/api/v1/transactions/transfers", Map.of("sourceAccountNumber", alice.accountNumber(),
                "destinationAccountNumber", bob.accountNumber(), "amount", "25.00"), UUID.randomUUID().toString());
        assertThat(receipt.status()).isEqualTo(201);
        assertThat(receipt.text("destinationAccountNumber")).startsWith("XXXXXXXX").endsWith(bob.accountNumber().substring(8));
        assertThat(receipt.text("sourceAccountNumber")).isEqualTo(alice.accountNumber());

        var bobView = as(bob.username()).get("/api/v1/transactions/" + receipt.text("reference"));
        assertThat(bobView.status()).isEqualTo(200);
        assertThat(bobView.body().path("transaction").path("direction").asText()).isEqualTo("CREDIT");
        assertThat(bobView.body().path("ledgerEntries")).hasSize(1);   // bob never sees alice's balance
    }
}
