package com.bms.security;

import com.bms.common.ApiException;
import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the authenticated principal established by the security filter chain. */
public final class CurrentUser {
    private CurrentUser() {}

    public static AppUserPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
    }

    public static boolean is(RoleName role) {
        return get().role() == role;
    }

    public static boolean hasPermission(Permission permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PERM_" + permission.name()));
    }

    /** Employees need the explicit permission; administrators and customers are handled by callers. */
    public static void requireEmployeePermission(Permission permission) {
        if (!is(RoleName.EMPLOYEE) || !hasPermission(permission)) {
            throw ApiException.forbidden("Your account is not authorised for " + permission.name().replace('_', ' ').toLowerCase());
        }
    }
}
