package com.bms.auth;

import com.bms.account.AccountService;
import com.bms.audit.AuditService;
import com.bms.common.ApiException;
import com.bms.config.BmsProperties;
import com.bms.customer.CustomerService;
import com.bms.customer.CustomerService.NewCustomer;
import com.bms.domain.AccountType;
import com.bms.domain.CustomerProfile;
import com.bms.domain.LoginActivity;
import com.bms.domain.LoginOutcome;
import com.bms.domain.RoleName;
import com.bms.domain.User;
import com.bms.domain.UserStatus;
import com.bms.repo.LoginActivityRepository;
import com.bms.repo.UserRepository;
import com.bms.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Public customer sign-up (username + password) and "Sign in with Google" (verified Google ID token). */
@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);
    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    public record SelfSignup(String username, String email, String fullName, String password, String phone,
                             LocalDate dateOfBirth, AccountType accountType) {}

    private final BmsProperties.Signup config;
    private final CustomerService customers;
    private final AccountService accounts;
    private final UserRepository users;
    private final LoginActivityRepository loginActivity;
    private final AuditService audit;
    private final NimbusJwtDecoder googleDecoder;

    public SignupService(BmsProperties props, CustomerService customers, AccountService accounts, UserRepository users,
                         LoginActivityRepository loginActivity, AuditService audit) {
        this.config = props.signup() != null ? props.signup() : new BmsProperties.Signup(false, null);
        this.customers = customers;
        this.accounts = accounts;
        this.users = users;
        this.loginActivity = loginActivity;
        this.audit = audit;
        this.googleDecoder = config.googleEnabled() ? googleDecoder(config.googleClientId().trim()) : null;
    }

    public boolean selfRegistrationEnabled() { return config.selfRegistration(); }

    public String googleClientId() { return config.googleEnabled() ? config.googleClientId().trim() : null; }

    /** Creates an active customer with the chosen password and opens their first account. */
    @Transactional
    public User register(SelfSignup s, String ip, String userAgent) {
        if (!config.selfRegistration()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SIGNUP_DISABLED", "Online registration is not available");
        }
        CustomerProfile profile = customers.createProfile(
                new NewCustomer(s.username(), s.email(), s.fullName(), s.phone(), s.dateOfBirth(), null, null, null, null),
                s.password(), false, false, null);
        User user = profile.getUser();
        accounts.openInternal(profile.getId(), s.accountType() == null ? AccountType.SAVINGS : s.accountType(),
                user.getId(), false);
        audit.recordAs(user, "CUSTOMER_SELF_REGISTERED", "CUSTOMER", profile.getId(),
                Map.of("customerNumber", profile.getCustomerNumber(), "method", "password"));
        return markSignedIn(user, user.getUsername(), ip, userAgent);
    }

    /**
     * Signs in with a Google ID token. A verified Google email that matches an existing customer signs into that
     * customer; an unknown email creates a new customer with a savings account. Staff and admins must use their
     * password, so a Google account can never grant staff access.
     */
    @Transactional
    public User google(String credential, String ip, String userAgent) {
        if (googleDecoder == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GOOGLE_DISABLED", "Google sign-in is not configured");
        }
        Jwt token;
        try {
            token = googleDecoder.decode(credential);
        } catch (JwtException e) {
            log.info("Rejected Google ID token: {}", e.getMessage());
            throw googleFailure();
        }
        String email = token.getClaimAsString("email");
        if (email == null || email.isBlank() || !Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {
            throw googleFailure();
        }
        email = email.trim().toLowerCase(Locale.ROOT);

        User user = users.findIdByEmail(email).flatMap(users::lockById).orElse(null);
        if (user != null) {
            if (user.roleName() != RoleName.CUSTOMER || user.getStatus() != UserStatus.ACTIVE) {
                loginActivity.save(new LoginActivity(user.getId(), user.getUsername(), LoginOutcome.DISABLED, ip,
                        userAgent, OffsetDateTime.now()));
                throw googleFailure();
            }
            return markSignedIn(user, user.getUsername(), ip, userAgent);
        }

        if (!config.selfRegistration()) throw googleFailure();
        String name = token.getClaimAsString("name");
        if (name == null || name.isBlank()) name = email.substring(0, email.indexOf('@'));
        name = name.length() > 120 ? name.substring(0, 120) : name;
        // The user never sees this password; they sign in with Google (staff can issue a password later if needed)
        CustomerProfile profile = customers.createProfile(
                new NewCustomer(uniqueUsernameFor(email), email, name, null, null, null, null, null, null),
                PasswordPolicy.generateTemporary(), false, false, null);
        User created = profile.getUser();
        accounts.openInternal(profile.getId(), AccountType.SAVINGS, created.getId(), false);
        audit.recordAs(created, "CUSTOMER_SELF_REGISTERED", "CUSTOMER", profile.getId(),
                Map.of("customerNumber", profile.getCustomerNumber(), "method", "google"));
        return markSignedIn(created, created.getUsername(), ip, userAgent);
    }

    private User markSignedIn(User user, String attempted, String ip, String userAgent) {
        OffsetDateTime now = OffsetDateTime.now();
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        loginActivity.save(new LoginActivity(user.getId(), attempted, LoginOutcome.SUCCESS, ip, userAgent, now));
        return user;
    }

    /** Derives a free username from the email's local part, e.g. "Jane.Doe+x@gmail.com" -> "jane.doe.x". */
    private String uniqueUsernameFor(String email) {
        String base = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", ".").replaceAll("\\.{2,}", ".").replaceAll("^[.]+|[.]+$", "");
        if (base.length() > 40) base = base.substring(0, 40);
        if (base.length() < 3) base = (base + "user").substring(0, Math.max(3, base.length()));
        String candidate = base;
        for (int i = 2; users.existsByUsernameIgnoreCase(candidate); i++) candidate = base + i;
        UserService.normaliseUsername(candidate);
        return candidate;
    }

    private static ApiException googleFailure() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED",
                "Google sign-in failed. Use a verified Google account, or sign in with your username and password.");
    }

    private static NimbusJwtDecoder googleDecoder(String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtClaimValidator<Object>(JwtClaimNames.ISS, iss -> iss != null && GOOGLE_ISSUERS.contains(iss.toString())),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(clientId))));
        return decoder;
    }
}
