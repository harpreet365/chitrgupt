package com.bms.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal browser-like HTTP client: keeps cookies (session + XSRF-TOKEN) and echoes the CSRF token in the
 * X-XSRF-TOKEN header, exactly as the React frontend does.
 */
public class ApiClient {
    public record Response(int status, JsonNode body, Map<String, java.util.List<String>> headers) {
        public String text(String field) { return body.path(field).asText(); }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

    public ApiClient(int port) {
        this.base = "http://127.0.0.1:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Response login(String username, String password) {
        get("/api/v1/auth/csrf");
        return post("/api/v1/auth/login", Map.of("username", username, "password", password));
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), null);
    }

    public Response post(String path, Object body) {
        return post(path, body, null);
    }

    public Response post(String path, Object body, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).POST(bodyOf(body));
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        return send(b, "json");
    }

    public Response postRaw(String path, String rawJson) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(rawJson)), "json");
    }

    public Response put(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).PUT(bodyOf(body)), "json");
    }

    public Response patch(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).method("PATCH", bodyOf(body)), "json");
    }

    /** Sends a mutating request WITHOUT the CSRF header. */
    public Response postWithoutCsrf(String path, Object body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).POST(bodyOf(body))
                    .header("Content-Type", "application/json").build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Response(res.statusCode(), parse(res.body()), res.headers().map());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String sessionCookie() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("BMS_SESSION"))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    public void setSessionCookie(String value) {
        cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("BMS_SESSION")).toList()
                .forEach(c -> cookies.getCookieStore().remove(null, c));
        HttpCookie c = new HttpCookie("BMS_SESSION", value);
        c.setPath("/");
        c.setVersion(0);
        cookies.getCookieStore().add(URI.create(base), c);
    }

    private HttpRequest.BodyPublisher bodyOf(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(body == null ? "{}" : MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Response send(HttpRequest.Builder b, String type) {
        try {
            if (type != null) b.header("Content-Type", "application/json");
            String token = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue).findFirst().orElse(null);
            if (token != null) b.header("X-XSRF-TOKEN", token);
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(res.statusCode(), parse(res.body()), res.headers().map());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode parse(String s) {
        try {
            return s == null || s.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(s);
        } catch (Exception e) {
            return MAPPER.createObjectNode().put("raw", s);
        }
    }
}
