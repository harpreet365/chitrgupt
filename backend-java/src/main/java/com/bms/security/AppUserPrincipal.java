package com.bms.security;

import com.bms.domain.RoleName;

import java.io.Serial;
import java.io.Serializable;

/**
 * What the server-side session remembers about the logged-in user. Authorities are NOT trusted from the
 * session: {@link UserRefreshFilter} reloads them from the database on every request.
 */
public record AppUserPrincipal(Long id, String username, RoleName role, int securityStamp) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String toString() { return username; }
}
