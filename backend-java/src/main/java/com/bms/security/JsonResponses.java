package com.bms.security;

import com.bms.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** Writes security failures using the same JSON error shape as the rest of the API. */
public final class JsonResponses {
    private final ObjectMapper mapper;

    public JsonResponses(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletRequest req, HttpServletResponse res, int status, String code, String message)
            throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), ApiError.of(status, code, message, req.getRequestURI()));
    }

    public AuthenticationEntryPoint entryPoint() {
        return (req, res, ex) -> write(req, res, 401, "UNAUTHENTICATED", "Authentication required");
    }

    public AccessDeniedHandler accessDenied() {
        return (req, res, ex) -> {
            String code = req.getRequestURI().startsWith("/api") && ex.getClass().getSimpleName().contains("Csrf")
                    ? "CSRF_TOKEN_INVALID" : "FORBIDDEN";
            String msg = code.equals("CSRF_TOKEN_INVALID")
                    ? "Security token missing or expired. Refresh the page and try again."
                    : "You do not have permission to perform this action";
            write(req, res, 403, code, msg);
        };
    }
}
