package com.bms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates service-to-service calls (Python analytics -> /internal/**, stop script -> /actuator/shutdown)
 * with a shared secret compared in constant time. Calls must also originate from the loopback interface.
 * If INTERNAL_API_TOKEN is unset or shorter than 32 characters, internal endpoints stay locked.
 */
public class ServiceTokenFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Service-Token";
    private final byte[] expected;

    public ServiceTokenFilter(String token) {
        this.expected = token != null && token.length() >= 32 ? token.getBytes(StandardCharsets.UTF_8) : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return !(uri.startsWith("/internal/") || uri.startsWith("/actuator/shutdown"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String supplied = req.getHeader(HEADER);
        if (expected != null && supplied != null && isLoopback(req.getRemoteAddr())
                && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("analytics-service", null,
                    List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
            SecurityContextHolder.setContext(ctx);
        }
        chain.doFilter(req, res);
    }

    private static boolean isLoopback(String addr) {
        try {
            return addr != null && InetAddress.getByName(addr).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
