package com.bms.user;

import com.bms.audit.AuditService;
import com.bms.auth.PasswordPolicy;
import com.bms.common.ApiException;
import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import com.bms.domain.User;
import com.bms.domain.UserStatus;
import com.bms.repo.RoleRepository;
import com.bms.repo.UserRepository;
import com.bms.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class UserService {

    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,50}$");

    /** A created user plus the one-time temporary password (returned once to the creator, never stored or logged). */
    public record CreatedUser(User user, String temporaryPassword) {}

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(UserRepository users, RoleRepository roles, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.audit = audit;
    }

    /** Low-level creation used by onboarding flows, bootstrap and demo seeding. */
    @Transactional
    public User createUser(String rawUsername, String rawEmail, String fullName, String rawPassword, RoleName role,
                           boolean mustChangePassword, boolean demo) {
        String username = normaliseUsername(rawUsername);
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("USERNAME_IN_USE", "That username is already taken");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("EMAIL_IN_USE", "That email address is already registered");
        }
        PasswordPolicy.validate(rawPassword, username);
        User u = new User(username, email, fullName.trim(), encoder.encode(rawPassword),
                roles.findByName(role).orElseThrow());
        u.setMustChangePassword(mustChangePassword);
        u.setDemo(demo);
        if (role == RoleName.EMPLOYEE) {
            u.getPermissions().addAll(Permission.employeeDefaults());
        }
        return users.saveAndFlush(u);
    }

    @Transactional
    public CreatedUser createStaff(String username, String email, String fullName, RoleName role) {
        if (role == RoleName.CUSTOMER) {
            throw ApiException.badRequest("INVALID_ROLE", "Customers are registered through customer onboarding");
        }
        String temp = PasswordPolicy.generateTemporary();
        User u = createUser(username, email, fullName, temp, role, true, false);
        audit.record("USER_CREATED", "USER", u.getId(), Map.of("username", u.getUsername(), "role", role.name()));
        return new CreatedUser(u, temp);
    }

    @Transactional(readOnly = true)
    public Page<User> search(String q, RoleName role, UserStatus status, Pageable pageable) {
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("fullName")), like),
                        cb.like(cb.lower(root.get("email")), like)));
            }
            if (role != null) ps.add(cb.equal(root.get("role").get("name"), role));
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return users.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public User get(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional
    public User update(Long id, String fullName, String email) {
        User u = get(id);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (fullName != null && !fullName.isBlank() && !fullName.trim().equals(u.getFullName())) {
            changes.put("fullName", Map.of("from", u.getFullName(), "to", fullName.trim()));
            u.setFullName(fullName.trim());
        }
        if (email != null && !email.equalsIgnoreCase(u.getEmail())) {
            if (users.emailTakenByOther(email, id)) {
                throw ApiException.conflict("EMAIL_IN_USE", "That email address is already registered");
            }
            changes.put("email", Map.of("from", u.getEmail(), "to", email.trim().toLowerCase(Locale.ROOT)));
            u.setEmail(email.trim().toLowerCase(Locale.ROOT));
        }
        if (!changes.isEmpty()) audit.record("USER_UPDATED", "USER", id, changes);
        return u;
    }

    @Transactional
    public User setStatus(Long id, UserStatus status, String reason) {
        User u = users.lockById(id).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getId().equals(CurrentUser.get().id())) {
            throw ApiException.badRequest("SELF_MODIFICATION", "You cannot change the status of your own account");
        }
        if (u.roleName() == RoleName.ADMIN && status != UserStatus.ACTIVE
                && users.countByRole_NameAndStatus(RoleName.ADMIN, UserStatus.ACTIVE) <= 1) {
            throw ApiException.badRequest("LAST_ADMIN", "At least one active administrator must remain");
        }
        UserStatus old = u.getStatus();
        if (old == status) return u;
        u.setStatus(status);
        if (status != UserStatus.ACTIVE) u.revokeSessions();
        audit.record(status == UserStatus.ACTIVE ? "USER_ACTIVATED" : "USER_" + status.name(), "USER", id,
                Map.of("from", old.name(), "to", status.name(), "reason", reason == null ? "" : reason));
        return u;
    }

    /** Role changes are limited to staff (EMPLOYEE <-> ADMIN); customers own accounts and cannot become staff. */
    @Transactional
    public User setRole(Long id, RoleName role) {
        User u = users.lockById(id).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getId().equals(CurrentUser.get().id())) {
            throw ApiException.badRequest("SELF_MODIFICATION", "You cannot change your own role");
        }
        if (u.roleName() == RoleName.CUSTOMER || role == RoleName.CUSTOMER) {
            throw ApiException.badRequest("INVALID_ROLE_CHANGE", "Only staff roles (EMPLOYEE, ADMIN) can be reassigned");
        }
        RoleName old = u.roleName();
        if (old == role) return u;
        if (old == RoleName.ADMIN && users.countByRole_NameAndStatus(RoleName.ADMIN, UserStatus.ACTIVE) <= 1) {
            throw ApiException.badRequest("LAST_ADMIN", "At least one active administrator must remain");
        }
        u.setRole(roles.findByName(role).orElseThrow());
        u.getPermissions().clear();
        if (role == RoleName.EMPLOYEE) u.getPermissions().addAll(Permission.employeeDefaults());
        u.revokeSessions();
        audit.record("ROLE_CHANGED", "USER", id, Map.of("from", old.name(), "to", role.name()));
        return u;
    }

    @Transactional
    public User setPermissions(Long id, Set<Permission> permissions) {
        User u = get(id);
        if (u.roleName() != RoleName.EMPLOYEE) {
            throw ApiException.badRequest("NOT_AN_EMPLOYEE", "Permissions apply to employees only");
        }
        Set<Permission> before = EnumSet.noneOf(Permission.class);
        before.addAll(u.getPermissions());
        u.getPermissions().clear();
        u.getPermissions().addAll(permissions);
        audit.record("PERMISSIONS_CHANGED", "USER", id,
                Map.of("from", before.stream().map(Enum::name).sorted().toList(),
                        "to", permissions.stream().map(Enum::name).sorted().toList()));
        return u;
    }

    @Transactional
    public CreatedUser resetPassword(Long id) {
        User u = users.lockById(id).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getId().equals(CurrentUser.get().id())) {
            throw ApiException.badRequest("SELF_MODIFICATION", "Use the change-password screen for your own account");
        }
        String temp = PasswordPolicy.generateTemporary();
        u.setPasswordHash(encoder.encode(temp));
        u.setMustChangePassword(true);
        u.setFailedLoginAttempts(0);
        u.setLockedUntil(null);
        u.revokeSessions();
        audit.record("PASSWORD_RESET", "USER", id, Map.of("username", u.getUsername()));
        return new CreatedUser(u, temp);
    }

    @Transactional
    public User unlock(Long id) {
        User u = users.lockById(id).orElseThrow(() -> ApiException.notFound("User"));
        u.setFailedLoginAttempts(0);
        u.setLockedUntil(null);
        audit.record("USER_UNLOCKED", "USER", id, Map.of());
        return u;
    }

    public static String normaliseUsername(String raw) {
        String username = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest("INVALID_USERNAME",
                    "Username must be 3-50 characters: lower-case letters, digits, dot, dash or underscore");
        }
        return username;
    }
}
