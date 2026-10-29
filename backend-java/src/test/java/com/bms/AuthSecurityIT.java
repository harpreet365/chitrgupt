package com.bms;

import com.bms.domain.User;
import com.bms.support.ApiClient;
import com.bms.support.IntegrationTest;
import com.bms.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AuthSecurityIT {

    @LocalServerPort int port;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;

    @Test
    void validLoginSucceedsAndReturnsRoleAndSessionCookieFlags() {
        var c = data.customer("0");
        ApiClient api = new ApiClient(port);
        var res = api.login(c.username(), TestData.PASSWORD);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text("role")).isEqualTo("CUSTOMER");
        assertThat(res.body().has("passwordHash")).isFalse();
        String setCookie = String.join(";", res.headers().getOrDefault("set-cookie", java.util.List.of()));
        assertThat(setCookie).contains("BMS_SESSION").containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Strict");
        assertThat(api.get("/api/v1/me").status()).isEqualTo(200);
    }

    @Test
    void invalidLoginFailsWithTheSameGenericMessageForUnknownUsersAndWrongPasswords() {
        var c = data.customer("0");
        var wrongPassword = new ApiClient(port).login(c.username(), "WrongPassw0rd!");
        var unknownUser = new ApiClient(port).login("no.such.user", "WrongPassw0rd!");
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownUser.status()).isEqualTo(401);
        assertThat(wrongPassword.text("message")).isEqualTo(unknownUser.text("message"));
        Integer logged = jdbc.queryForObject("SELECT count(*) FROM login_activity WHERE username_attempted = ? AND outcome = 'BAD_CREDENTIALS'",
                Integer.class, c.username());
        assertThat(logged).isEqualTo(1);
    }

    @Test
    void accountIsTemporarilyLockedAfterRepeatedFailures() {
        var c = data.customer("0");
        for (int i = 0; i < 3; i++) new ApiClient(port).login(c.username(), "WrongPassw0rd!");
        var correct = new ApiClient(port).login(c.username(), TestData.PASSWORD);
        assertThat(correct.status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM users WHERE id = ?", Boolean.class, c.user().getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_activity WHERE user_id = ? AND outcome = 'LOCKED'",
                Integer.class, c.user().getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ACCOUNT_LOCKED' AND target_id = ?",
                Integer.class, String.valueOf(c.user().getId()))).isEqualTo(1);
    }

    @Test
    void logoutInvalidatesTheSessionServerSide() {
        var c = data.customer("0");
        ApiClient api = new ApiClient(port);
        api.login(c.username(), TestData.PASSWORD);
        String oldSession = api.sessionCookie();
        assertThat(api.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        assertThat(api.get("/api/v1/me").status()).isEqualTo(401);

        ApiClient replay = new ApiClient(port);
        replay.setSessionCookie(oldSession);
        assertThat(replay.get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void mutatingRequestsWithoutCsrfTokenAreRejected() {
        var c = data.customer("0");
        ApiClient api = new ApiClient(port);
        api.login(c.username(), TestData.PASSWORD);
        var res = api.postWithoutCsrf("/api/v1/transactions/deposits", Map.of("accountNumber", c.accountNumber(), "amount", "10.00"));
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.text("code")).isEqualTo("CSRF_TOKEN_INVALID");
    }

    @Test
    void suspendedUserLosesActiveSessionAndCannotLogIn() {
        var c = data.customer("100.00");
        User admin = data.admin();
        ApiClient customer = new ApiClient(port);
        customer.login(c.username(), TestData.PASSWORD);
        assertThat(customer.get("/api/v1/accounts").status()).isEqualTo(200);

        ApiClient adminApi = new ApiClient(port);
        adminApi.login(admin.getUsername(), TestData.PASSWORD);
        var suspend = adminApi.patch("/api/v1/admin/users/" + c.user().getId() + "/status", Map.of("status", "SUSPENDED", "reason", "test"));
        assertThat(suspend.status()).isEqualTo(200);

        var afterSuspend = customer.post("/api/v1/transactions/withdrawals", Map.of("accountNumber", c.accountNumber(), "amount", "10.00"));
        assertThat(afterSuspend.status()).isEqualTo(401);
        assertThat(afterSuspend.text("code")).isEqualTo("SESSION_REVOKED");
        assertThat(data.balance(c.accountNumber())).isEqualByComparingTo("100.00");
        assertThat(new ApiClient(port).login(c.username(), TestData.PASSWORD).status()).isEqualTo(401);
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndRevokesOtherSessions() {
        var c = data.customer("0");
        ApiClient first = new ApiClient(port);
        ApiClient second = new ApiClient(port);
        first.login(c.username(), TestData.PASSWORD);
        second.login(c.username(), TestData.PASSWORD);

        assertThat(first.post("/api/v1/auth/password", Map.of("currentPassword", "nope", "newPassword", "An0therStrongOne")).status())
                .isEqualTo(400);
        assertThat(first.post("/api/v1/auth/password", Map.of("currentPassword", TestData.PASSWORD, "newPassword", "short")).status())
                .isEqualTo(400);
        assertThat(first.post("/api/v1/auth/password", Map.of("currentPassword", TestData.PASSWORD, "newPassword", "An0therStrongOne")).status())
                .isEqualTo(204);

        assertThat(first.get("/api/v1/me").status()).isEqualTo(200);        // this session continues
        assertThat(second.get("/api/v1/me").status()).isEqualTo(401);       // the other one is revoked
        assertThat(new ApiClient(port).login(c.username(), "An0therStrongOne").status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'PASSWORD_CHANGED' AND actor_user_id = ?",
                Integer.class, c.user().getId())).isEqualTo(1);
    }

    @Test
    void temporaryPasswordMustBeChangedBeforeAnythingElse() {
        User admin = data.admin();
        ApiClient adminApi = new ApiClient(port);
        adminApi.login(admin.getUsername(), TestData.PASSWORD);
        var created = adminApi.post("/api/v1/admin/users", Map.of("username", TestData.unique("newemp"),
                "email", TestData.unique("e") + "@test.example", "fullName", "New Employee", "role", "EMPLOYEE"));
        assertThat(created.status()).isEqualTo(201);
        String username = created.body().path("user").path("username").asText();
        String temp = created.text("temporaryPassword");

        ApiClient emp = new ApiClient(port);
        var login = emp.login(username, temp);
        assertThat(login.body().path("mustChangePassword").asBoolean()).isTrue();
        assertThat(emp.get("/api/v1/customers").text("code")).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(emp.post("/api/v1/auth/password", Map.of("currentPassword", temp, "newPassword", "Brand9NewPassword")).status())
                .isEqualTo(204);
        assertThat(emp.get("/api/v1/customers").status()).isEqualTo(200);
    }

    @Test
    void serviceEndpointsRequireTheServiceToken() {
        var anonymous = new ApiClient(port).get("/internal/v1/ping");
        assertThat(anonymous.status()).isEqualTo(401);
        var c = data.customer("0");
        ApiClient customer = new ApiClient(port);
        customer.login(c.username(), TestData.PASSWORD);
        assertThat(customer.get("/internal/v1/ping").status()).isIn(401, 403);
    }
}
