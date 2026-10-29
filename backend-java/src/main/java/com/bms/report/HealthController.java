package com.bms.report;

import com.bms.analytics.AnalyticsClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public, non-sensitive health check used by the start script and the login page banner. */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final AnalyticsClient analytics;

    public HealthController(JdbcTemplate jdbc, AnalyticsClient analytics) {
        this.jdbc = jdbc;
        this.analytics = analytics;
    }

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        boolean db;
        try {
            db = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (Exception e) {
            db = false;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", db ? "ok" : "degraded");
        m.put("database", db ? "up" : "down");
        m.put("analytics", analytics.healthy() ? "up" : "down");
        m.put("simulation", true);
        m.put("time", OffsetDateTime.now());
        return m;
    }
}
