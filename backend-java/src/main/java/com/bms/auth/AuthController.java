package com.bms.auth;

import com.bms.common.ApiException;
import com.bms.security.AppUserPrincipal;
import com.bms.security.ClientInfo;
import com.bms.security.CurrentUser;
import com.bms.security.UserRefreshFilter;
import com.bms.user.MeService;
import com.bms.user.UserViews.MeView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import com.bms.domain.AccountType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** One message for every failure: unknown user, wrong password, locked, suspended. */
    static final String GENERIC_LOGIN_FAILURE = "Sign-in failed. Check your username and password, or try again later.";

    public record LoginRequest(@NotBlank @Size(max = 254) String username, @NotBlank @Size(max = 128) String password) {}

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {}

    public record RegisterRequest(
            @NotBlank @Size(max = 50) String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(max = 128) String password,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be 7-20 digits, spaces, + or -") String phone,
            @NotNull @Past LocalDate dateOfBirth,
            AccountType accountType) {}

    public record GoogleRequest(@NotBlank @Size(max = 4096) String credential) {}

    private final AuthService auth;
    private final SignupService signup;
    private final MeService me;
    private final SecurityContextRepository contextRepository;
    private final CookieCsrfTokenRepository csrfRepository;

    public AuthController(AuthService auth, SignupService signup, MeService me,
                          SecurityContextRepository contextRepository, CookieCsrfTokenRepository csrfRepository) {
        this.auth = auth;
        this.signup = signup;
        this.me = me;
        this.contextRepository = contextRepository;
        this.csrfRepository = csrfRepository;
    }

    /** Tells the sign-in page which options to show. The Google client id is public by design. */
    @GetMapping("/providers")
    public Map<String, Object> providers() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selfRegistration", signup.selfRegistrationEnabled());
        if (signup.googleClientId() != null) out.put("googleClientId", signup.googleClientId());
        return out;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public MeView register(@Valid @RequestBody RegisterRequest b, HttpServletRequest req, HttpServletResponse res) {
        var user = signup.register(new SignupService.SelfSignup(b.username(), b.email(), b.fullName(), b.password(),
                b.phone(), b.dateOfBirth(), b.accountType()), req.getRemoteAddr(), ClientInfo.userAgent());
        startSession(user, req, res);
        return me.me(user.getId());
    }

    @PostMapping("/google")
    public MeView google(@Valid @RequestBody GoogleRequest b, HttpServletRequest req, HttpServletResponse res) {
        var user = signup.google(b.credential(), req.getRemoteAddr(), ClientInfo.userAgent());
        startSession(user, req, res);
        return me.me(user.getId());
    }

    /** Issues the XSRF-TOKEN cookie (any request does; this endpoint exists so the SPA can ask explicitly). */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    /** Lets the SPA restore state after a page refresh without exposing anything to anonymous callers. */
    @GetMapping("/session")
    public Map<String, Object> session() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AppUserPrincipal p) {
            return Map.of("authenticated", true, "user", me.me(p.id()));
        }
        return Map.of("authenticated", false);
    }

    @PostMapping("/login")
    public MeView login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        AuthService.LoginResult result = auth.attemptLogin(body.username(), body.password(),
                req.getRemoteAddr(), ClientInfo.userAgent());
        if (!result.success()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", GENERIC_LOGIN_FAILURE);
        }
        var user = result.user();
        startSession(user, req, res);
        return me.me(user.getId());
    }

    private void startSession(com.bms.domain.User user, HttpServletRequest req, HttpServletResponse res) {
        // Session fixation protection: discard any pre-login session and start a fresh one
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate();
        req.getSession(true);

        AppUserPrincipal principal = new AppUserPrincipal(user.getId(), user.getUsername(), user.roleName(),
                user.getSecurityStamp());
        establish(principal, user, req, res);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AppUserPrincipal p) {
            auth.recordLogout(p.id(), p.username(), req.getRemoteAddr(), ClientInfo.userAgent());
        }
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        csrfRepository.saveToken(null, req, res);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body,
                                               HttpServletRequest req, HttpServletResponse res) {
        AppUserPrincipal current = CurrentUser.get();
        int newStamp = auth.changePassword(current.id(), body.currentPassword(), body.newPassword());
        // Other sessions are now revoked; keep this one, with a new id and the new security stamp
        req.changeSessionId();
        AppUserPrincipal updated = new AppUserPrincipal(current.id(), current.username(), current.role(), newStamp);
        establish(updated, me.loadUser(current.id()), req, res);
        return ResponseEntity.noContent().build();
    }

    private void establish(AppUserPrincipal principal, com.bms.domain.User user, HttpServletRequest req,
                           HttpServletResponse res) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                UserRefreshFilter.authoritiesOf(user)));
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, req, res);
    }
}
