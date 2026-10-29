package com.bms.user;

import com.bms.common.PageResponse;
import com.bms.common.Paging;
import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import com.bms.domain.UserStatus;
import com.bms.user.UserViews.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.EnumSet;
import java.util.Set;

/** Administrator-only user and role management (also enforced by URL rules in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    public record CreateStaffRequest(@NotBlank @Size(max = 50) String username,
                                     @NotBlank @Email @Size(max = 254) String email,
                                     @NotBlank @Size(max = 120) String fullName,
                                     @NotNull RoleName role) {}

    public record UpdateUserRequest(@Size(min = 1, max = 120) String fullName, @Email @Size(max = 254) String email) {}

    public record StatusRequest(@NotNull UserStatus status, @Size(max = 200) String reason) {}

    public record RoleRequest(@NotNull RoleName role) {}

    public record PermissionsRequest(@NotNull Set<Permission> permissions) {}

    public record CreatedUserResponse(UserView user, String temporaryPassword) {}

    private final UserService service;

    public AdminUserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<UserView> list(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) RoleName role,
                                       @RequestParam(required = false) UserStatus status,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String sort) {
        var pageable = Paging.of(page, size, sort, Set.of("createdAt", "username", "fullName", "lastLoginAt"),
                "createdAt,desc");
        return PageResponse.of(service.search(q, role, status, pageable), UserView::of);
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable Long id) {
        return UserView.of(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedUserResponse create(@Valid @RequestBody CreateStaffRequest body) {
        var created = service.createStaff(body.username(), body.email(), body.fullName(), body.role());
        return new CreatedUserResponse(UserView.of(created.user()), created.temporaryPassword());
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest body) {
        return UserView.of(service.update(id, body.fullName(), body.email()));
    }

    @PatchMapping("/{id}/status")
    public UserView status(@PathVariable Long id, @Valid @RequestBody StatusRequest body) {
        return UserView.of(service.setStatus(id, body.status(), body.reason()));
    }

    @PatchMapping("/{id}/role")
    public UserView role(@PathVariable Long id, @Valid @RequestBody RoleRequest body) {
        return UserView.of(service.setRole(id, body.role()));
    }

    @PutMapping("/{id}/permissions")
    public UserView permissions(@PathVariable Long id, @Valid @RequestBody PermissionsRequest body) {
        Set<Permission> perms = body.permissions().isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(body.permissions());
        return UserView.of(service.setPermissions(id, perms));
    }

    @PostMapping("/{id}/reset-password")
    public CreatedUserResponse resetPassword(@PathVariable Long id) {
        var r = service.resetPassword(id);
        return new CreatedUserResponse(UserView.of(r.user()), r.temporaryPassword());
    }

    @PostMapping("/{id}/unlock")
    public UserView unlock(@PathVariable Long id) {
        return UserView.of(service.unlock(id));
    }
}
