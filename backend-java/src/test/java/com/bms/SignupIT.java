package com.bms;

import com.bms.support.ApiClient;
import com.bms.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SignupIT {

    private static final String PASSWORD = "Signup-Passw0rd";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private Map<String, Object> signup(String username) {
        Map<String, Object> b = new HashMap<>();
        b.put("username", username);
        b.put("email", username + "@mail.example");
        b.put("fullName", "Self Signup");
        b.put("password", PASSWORD);
        b.put("dateOfBirth", "1995-06-01");
        return b;
    }

    private ApiClient anonymous() {
        ApiClient api = new ApiClient(port);
        api.get("/api/v1/auth/csrf");
        return api;
    }

    @Test
    void providersEndpointIsPublicAndHidesGoogleWhenNotConfigured() {
        var res = new ApiClient(port).get("/api/v1/auth/providers");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("selfRegistration").asBoolean()).isTrue();
        assertThat(res.body().has("googleClientId")).isFalse();
    }

    @Test
    void selfRegistrationCreatesSignedInCustomerWithOneSavingsAccount() {
        String username = "self." + System.nanoTime();
        ApiClient api = anonymous();
        var res = api.post("/api/v1/auth/register", signup(username));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.text("role")).isEqualTo("CUSTOMER");
        assertThat(res.body().path("mustChangePassword").asBoolean()).isFalse();

        var accounts = api.get("/api/v1/accounts");
        assertThat(accounts.status()).isEqualTo(200);
        assertThat(accounts.body().path("content")).hasSize(1);
        assertThat(accounts.body().path("content").get(0).path("accountType").asText()).isEqualTo("SAVINGS");
        assertThat(accounts.body().path("content").get(0).path("balance").decimalValue()).isZero();

        assertThat(new ApiClient(port).login(username, PASSWORD).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CUSTOMER_SELF_REGISTERED' AND actor_username = ?",
                Integer.class, username)).isEqualTo(1);
    }

    @Test
    void selfRegistrationRejectsDuplicatesUnderageWeakPasswordsAndRoleInjection() {
        String username = "dup." + System.nanoTime();
        assertThat(anonymous().post("/api/v1/auth/register", signup(username)).status()).isEqualTo(201);
        assertThat(anonymous().post("/api/v1/auth/register", signup(username)).status()).isEqualTo(409);

        var minor = signup("minor." + System.nanoTime());
        minor.put("dateOfBirth", LocalDate.now().minusYears(10).toString());
        assertThat(anonymous().post("/api/v1/auth/register", minor).status()).isEqualTo(400);

        var weak = signup("weak." + System.nanoTime());
        weak.put("password", "short");
        assertThat(anonymous().post("/api/v1/auth/register", weak).status()).isEqualTo(400);

        var escalate = signup("esc." + System.nanoTime());
        escalate.put("role", "ADMIN");
        assertThat(anonymous().post("/api/v1/auth/register", escalate).status()).isEqualTo(400);
    }

    @Test
    void googleSignInIsRefusedWhenNotConfigured() {
        var res = anonymous().post("/api/v1/auth/google", Map.of("credential", "not-a-token"));
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.text("code")).isEqualTo("GOOGLE_DISABLED");
    }
}
