package com.bms.txn;

import com.bms.domain.Permission;
import com.bms.domain.RoleName;
import com.bms.domain.User;
import com.bms.security.AppUserPrincipal;
import com.bms.security.CurrentUser;

import java.util.EnumSet;
import java.util.Set;

/** Who is initiating a money movement, with the permissions that apply to them. */
public record Actor(Long userId, String username, RoleName role, Set<Permission> permissions) {

    public static Actor current() {
        AppUserPrincipal p = CurrentUser.get();
        Set<Permission> perms = EnumSet.noneOf(Permission.class);
        for (Permission perm : Permission.values()) {
            if (CurrentUser.hasPermission(perm)) perms.add(perm);
        }
        return new Actor(p.id(), p.username(), p.role(), perms);
    }

    public static Actor of(User u) {
        Set<Permission> perms = EnumSet.noneOf(Permission.class);
        if (u.roleName() == RoleName.EMPLOYEE) perms.addAll(u.getPermissions());
        return new Actor(u.getId(), u.getUsername(), u.roleName(), perms);
    }
}
