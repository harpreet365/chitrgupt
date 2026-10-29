package com.bms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "bms")
public record BmsProperties(
        String dbMode,
        EmbeddedDb embeddedDb,
        String businessTimezone,
        Security security,
        Limits limits,
        Flagging flagging,
        Analytics analytics,
        BootstrapAdmin bootstrapAdmin,
        Demo demo,
        Signup signup) {

    public record EmbeddedDb(int port, String dataDir) {}

    public record Security(int maxFailedAttempts, Duration lockoutDuration, String internalApiToken,
                           List<String> corsAllowedOrigins, RateLimit rateLimit) {}

    public record RateLimit(int loginPerMinute, int moneyPerMinute, int apiPerMinute) {}

    public record Limits(BigDecimal maxSingleAmount, BigDecimal customerDailyOutflow) {}

    public record Flagging(BigDecimal largeAmountThreshold, int rapidOutflowCount, Duration rapidOutflowWindow) {}

    public record Analytics(String baseUrl, String serviceToken, Duration timeout) {}

    public record BootstrapAdmin(String username, String email, String password, boolean mustChangePassword) {}

    public record Demo(boolean seed, String password) {}

    /** Public customer sign-up. Google sign-in is enabled only when googleClientId is set. */
    public record Signup(boolean selfRegistration, String googleClientId) {
        public boolean googleEnabled() { return googleClientId != null && !googleClientId.isBlank(); }
    }
}
