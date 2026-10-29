package com.bms.user;

import com.bms.domain.CustomerProfile;
import com.bms.domain.LoginActivity;
import com.bms.domain.Permission;
import com.bms.domain.User;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.TreeSet;

/** Response DTOs. Entities are never serialised directly, so hashes and internal fields cannot leak. */
public final class UserViews {
    private UserViews() {}

    public record UserView(Long id, String username, String email, String fullName, String role, String status,
                           Set<String> permissions, boolean mustChangePassword, int failedLoginAttempts,
                           OffsetDateTime lockedUntil, OffsetDateTime lastLoginAt, OffsetDateTime lastFailedLoginAt,
                           OffsetDateTime createdAt, boolean demo) {
        public static UserView of(User u) {
            Set<String> perms = new TreeSet<>();
            u.getPermissions().forEach(p -> perms.add(p.name()));
            return new UserView(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.roleName().name(),
                    u.getStatus().name(), perms, u.isMustChangePassword(), u.getFailedLoginAttempts(),
                    u.getLockedUntil(), u.getLastLoginAt(), u.getLastFailedLoginAt(), u.getCreatedAt(), u.isDemo());
        }
    }

    public record ProfileView(Long customerId, String customerNumber, String phone, LocalDate dateOfBirth,
                              String addressLine, String city, String postalCode) {
        public static ProfileView of(CustomerProfile c) {
            if (c == null) return null;
            return new ProfileView(c.getId(), c.getCustomerNumber(), c.getPhone(), c.getDateOfBirth(),
                    c.getAddressLine(), c.getCity(), c.getPostalCode());
        }
    }

    /** The signed-in user's own view, including security information for "last login" displays. */
    public record MeView(Long id, String username, String email, String fullName, String role, Set<String> permissions,
                         boolean mustChangePassword, OffsetDateTime currentLoginAt, OffsetDateTime previousLoginAt,
                         OffsetDateTime lastFailedLoginAt, OffsetDateTime passwordChangedAt, boolean demo,
                         ProfileView customerProfile) {
        public static MeView of(User u, OffsetDateTime previousLogin, CustomerProfile profile) {
            Set<String> perms = new TreeSet<>();
            if (u.getRole().getName().name().equals("EMPLOYEE")) {
                for (Permission p : u.getPermissions()) perms.add(p.name());
            }
            return new MeView(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.roleName().name(), perms,
                    u.isMustChangePassword(), u.getLastLoginAt(), previousLogin, u.getLastFailedLoginAt(),
                    u.getPasswordChangedAt(), u.isDemo(), ProfileView.of(profile));
        }
    }

    public record LoginActivityView(Long id, Long userId, String username, String outcome, String ipAddress,
                                    String userAgent, OffsetDateTime createdAt) {
        public static LoginActivityView of(LoginActivity a) {
            return new LoginActivityView(a.getId(), a.getUserId(), a.getUsernameAttempted(), a.getOutcome().name(),
                    a.getIpAddress(), a.getUserAgent(), a.getCreatedAt());
        }
    }
}
