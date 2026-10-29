package com.bms.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Client network details for logs. X-Forwarded-For is intentionally ignored: it is client-controlled and
 * this application is not deployed behind a trusted proxy by default.
 */
public final class ClientInfo {
    private ClientInfo() {}

    public static String ip() {
        HttpServletRequest req = current();
        return req == null ? null : req.getRemoteAddr();
    }

    public static String userAgent() {
        HttpServletRequest req = current();
        if (req == null) return null;
        String ua = req.getHeader("User-Agent");
        if (ua == null) return null;
        return ua.length() > 255 ? ua.substring(0, 255) : ua;
    }

    private static HttpServletRequest current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}
