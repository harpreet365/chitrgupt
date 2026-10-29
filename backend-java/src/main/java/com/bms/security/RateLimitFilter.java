package com.bms.security;

import com.bms.config.BmsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple fixed-window (per minute) rate limiter, keyed by client IP. Sufficient for a single-instance
 * educational deployment; a multi-instance deployment would need a shared store (e.g. Redis).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long minute, AtomicInteger count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final BmsProperties.RateLimit limits;
    private final JsonResponses json;

    public RateLimitFilter(BmsProperties.RateLimit limits, JsonResponses json) {
        this.limits = limits;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String uri = req.getRequestURI();
        String ip = req.getRemoteAddr();
        boolean post = "POST".equals(req.getMethod());

        boolean signIn = uri.equals("/api/v1/auth/login") || uri.equals("/api/v1/auth/register")
                || uri.equals("/api/v1/auth/google");
        if (post && signIn && !allow("login:" + ip, limits.loginPerMinute())) {
            reject(req, res);
            return;
        }
        if (post && uri.startsWith("/api/v1/transactions/") && !allow("money:" + ip, limits.moneyPerMinute())) {
            reject(req, res);
            return;
        }
        if (!allow("api:" + ip, limits.apiPerMinute())) {
            reject(req, res);
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean allow(String key, int limit) {
        long minute = System.currentTimeMillis() / 60_000;
        Window w = windows.compute(key, (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
        }
        return w.count().incrementAndGet() <= limit;
    }

    private void reject(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setHeader("Retry-After", "60");
        json.write(req, res, 429, "RATE_LIMITED", "Too many requests. Please wait a minute and try again.");
    }
}
