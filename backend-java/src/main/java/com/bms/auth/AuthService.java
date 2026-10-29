package com.bms.auth;

import com.bms.audit.AuditService;
import com.bms.common.ApiException;
import com.bms.config.BmsProperties;
import com.bms.domain.LoginActivity;
import com.bms.domain.LoginOutcome;
import com.bms.domain.User;
import com.bms.domain.UserStatus;
import com.bms.repo.LoginActivityRepository;
import com.bms.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {

    /** Result of a login attempt. The outcome is internal only; clients always see one generic message. */
    public record LoginResult(User user, LoginOutcome outcome) {
        public boolean success() { return outcome == LoginOutcome.SUCCESS; }
    }

    private final UserRepository users;
    private final LoginActivityRepository loginActivity;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final BmsProperties props;
    private final String dummyHash;

    public AuthService(UserRepository users, LoginActivityRepository loginActivity, PasswordEncoder encoder,
                       AuditService audit, BmsProperties props) {
        this.users = users;
        this.loginActivity = loginActivity;
        this.encoder = encoder;
        this.audit = audit;
        this.props = props;
        // Compared against when the user does not exist, so response timing does not reveal valid usernames
        this.dummyHash = encoder.encode("dummy-password-for-timing-equalisation");
    }

    @Transactional
    public LoginResult attemptLogin(String rawUsername, String password, String ip, String userAgent) {
        String username = rawUsername == null ? "" : rawUsername.trim().toLowerCase(Locale.ROOT);
        String attempted = username.length() > 254 ? username.substring(0, 254) : username;
        OffsetDateTime now = OffsetDateTime.now();

        User user = users.findIdByUsername(username).flatMap(users::lockById).orElse(null);
        if (user == null) {
            encoder.matches(password == null ? "" : password, dummyHash);
            loginActivity.save(new LoginActivity(null, attempted, LoginOutcome.BAD_CREDENTIALS, ip, userAgent, now));
            return new LoginResult(null, LoginOutcome.BAD_CREDENTIALS);
        }

        if (user.isLocked(now)) {
            encoder.matches(password == null ? "" : password, dummyHash);
            user.setLastFailedLoginAt(now);
            loginActivity.save(new LoginActivity(user.getId(), attempted, LoginOutcome.LOCKED, ip, userAgent, now));
            return new LoginResult(user, LoginOutcome.LOCKED);
        }

        if (password == null || !encoder.matches(password, user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            user.setLastFailedLoginAt(now);
            if (attempts >= props.security().maxFailedAttempts()) {
                user.setLockedUntil(now.plus(props.security().lockoutDuration()));
                user.setFailedLoginAttempts(0);
                audit.recordAs(user, "ACCOUNT_LOCKED", "USER", user.getId(),
                        Map.of("reason", "too many failed login attempts",
                                "lockedUntil", user.getLockedUntil().toString()));
            }
            loginActivity.save(new LoginActivity(user.getId(), attempted, LoginOutcome.BAD_CREDENTIALS, ip, userAgent, now));
            return new LoginResult(user, LoginOutcome.BAD_CREDENTIALS);
        }

        // Status is checked only after a correct password so that it does not reveal anything to guessers
        if (user.getStatus() != UserStatus.ACTIVE) {
            loginActivity.save(new LoginActivity(user.getId(), attempted, LoginOutcome.DISABLED, ip, userAgent, now));
            return new LoginResult(user, LoginOutcome.DISABLED);
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        loginActivity.save(new LoginActivity(user.getId(), attempted, LoginOutcome.SUCCESS, ip, userAgent, now));
        return new LoginResult(user, LoginOutcome.SUCCESS);
    }

    @Transactional
    public void recordLogout(Long userId, String username, String ip, String userAgent) {
        loginActivity.save(new LoginActivity(userId, username, LoginOutcome.LOGOUT, ip, userAgent, OffsetDateTime.now()));
    }

    /** Changes the password and revokes all other sessions. Returns the user's new security stamp. */
    @Transactional(noRollbackFor = ApiException.class)
    public int changePassword(Long userId, String currentPassword, String newPassword) {
        User user = users.lockById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            audit.recordAs(user, "PASSWORD_CHANGE_FAILED", "USER", user.getId(), Map.of("reason", "current password incorrect"));
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        PasswordPolicy.validate(newPassword, user.getUsername());
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("PASSWORD_REUSED", "New password must differ from the current password");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        user.setPasswordChangedAt(OffsetDateTime.now());
        user.setMustChangePassword(false);
        user.revokeSessions();
        users.saveAndFlush(user);
        audit.recordAs(user, "PASSWORD_CHANGED", "USER", user.getId(), Map.of());
        return user.getSecurityStamp();
    }
}
