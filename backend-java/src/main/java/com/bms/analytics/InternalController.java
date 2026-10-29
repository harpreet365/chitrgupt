package com.bms.analytics;

import com.bms.common.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Read-only transaction feed for the Python analytics service. Requires ROLE_SERVICE (X-Service-Token from
 * loopback). It exposes no names, emails or balances - only what is needed to compute statistics.
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalController {

    public record FeedItem(String reference, String type, String status, BigDecimal amount, String currency,
                           Long sourceAccountId, String sourceAccountNumber, Long destinationAccountId,
                           String destinationAccountNumber, String channel, OffsetDateTime createdAt) {}

    public record FeedPage(List<FeedItem> items, int page, int size, boolean last) {}

    private static final int MAX_SIZE = 2000;
    private final JdbcTemplate jdbc;

    public InternalController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/transactions")
    public FeedPage transactions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1000") int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE || !from.isBefore(to) || from.plusDays(400).isBefore(to)) {
            throw ApiException.badRequest("INVALID_FEED_REQUEST", "Invalid page, size or date range (max 400 days)");
        }
        String sql = """
                SELECT t.reference, t.type, t.status, t.amount, t.currency, t.source_account_id, s.account_number AS src_no,
                       t.destination_account_id, d.account_number AS dst_no, t.channel, t.created_at
                FROM transactions t
                LEFT JOIN bank_accounts s ON s.id = t.source_account_id
                LEFT JOIN bank_accounts d ON d.id = t.destination_account_id
                WHERE t.created_at >= ? AND t.created_at < ?
                  AND (CAST(? AS BIGINT) IS NULL OR t.source_account_id = ? OR t.destination_account_id = ?)
                ORDER BY t.created_at, t.id
                LIMIT ? OFFSET ?""";
        List<FeedItem> items = jdbc.query(sql, (rs, i) -> new FeedItem(
                        rs.getString("reference"), rs.getString("type"), rs.getString("status"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), (Long) rs.getObject("source_account_id"), rs.getString("src_no"),
                        (Long) rs.getObject("destination_account_id"), rs.getString("dst_no"), rs.getString("channel"),
                        rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC)),
                Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()), accountId, accountId, accountId,
                size + 1, (long) page * size);
        boolean last = items.size() <= size;
        return new FeedPage(last ? items : items.subList(0, size), page, size, last);
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
