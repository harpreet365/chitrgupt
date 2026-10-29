package com.bms.security;

import com.bms.domain.LoginActivity;
import com.bms.domain.LoginOutcome;
import com.bms.domain.RoleName;
import com.bms.domain.User;
import com.bms.domain.UserStatus;
import com.bms.repo.LoginActivityRepository;
import com.bms.repo.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs on every authenticated request:
 * - rejects sessions of users who were suspended/deactivated, or whose security stamp changed
 *   (password reset, role change) - the session is invalidated immediately;
 * - rebuilds authorities from the database so permission changes take effect without re-login;
 * - blocks everything except password change while a password change is required.
 */
public class UserRefreshFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final LoginActivityRepository loginActivity;
    private final JsonResponses json;

    public UserRefreshFilter(UserRepository users, LoginActivityRepository loginActivity, JsonResponses json) {
        this.users = users;
        this.loginActivity = loginActivity;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            chain.doFilter(req, res);
            return;
        }

        Optional<User> found = users.findById(principal.id());
        if (found.isEmpty() || found.get().getStatus() != UserStatus.ACTIVE
                || found.get().getSecurityStamp() != principal.securityStamp()
                || found.get().roleName() != principal.role()) {
            HttpSession session = req.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            loginActivity.save(new LoginActivity(principal.id(), principal.username(), LoginOutcome.SESSION_REVOKED,
                    req.getRemoteAddr(), ClientInfo.userAgent(), OffsetDateTime.now()));
            json.write(req, res, 401, "SESSION_REVOKED", "Your session has ended. Please sign in again.");
            return;
        }

        User user = found.get();
        SecurityContext fresh = SecurityContextHolder.createEmptyContext();
        fresh.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authoritiesOf(user)));
        SecurityContextHolder.setContext(fresh);

        if (user.isMustChangePassword() && !allowedDuringPasswordChange(req)) {
            json.write(req, res, 403, "PASSWORD_CHANGE_REQUIRED", "You must change your password before continuing.");
            return;
        }
        chain.doFilter(req, res);
    }

    public static List<GrantedAuthority> authoritiesOf(User user) {
        List<GrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority("ROLE_" + user.roleName().name()));
        if (user.roleName() == RoleName.EMPLOYEE) {
            user.getPermissions().forEach(p -> list.add(new SimpleGrantedAuthority("PERM_" + p.name())));
        }
        return list;
    }

    private static boolean allowedDuringPasswordChange(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri.startsWith("/api/v1/auth/") || (uri.equals("/api/v1/me") && "GET".equals(req.getMethod()));
    }
}
