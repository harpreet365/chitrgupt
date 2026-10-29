package com.bms.txn;

import com.bms.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Idempotency keys are claimed with INSERT ... ON CONFLICT DO NOTHING inside the money transaction.
 * A concurrent duplicate blocks on the unique index until the first request commits, then sees the
 * committed record and replays its result. If the first request rolls back, its claim disappears too.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyService {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,80}$");

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the transaction id of an earlier identical request, or empty if this request is new. */
    public Optional<Long> claim(Long userId, String key, String operation, String requestHash) {
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-80 letters, digits, '-' or '_'");
        }
        int inserted = jdbc.update("""
                INSERT INTO idempotency_records (user_id, idempotency_key, operation, request_hash)
                VALUES (?, ?, ?, ?) ON CONFLICT (user_id, idempotency_key) DO NOTHING""",
                userId, key, operation, requestHash);
        if (inserted == 1) return Optional.empty();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT operation, request_hash, transaction_id FROM idempotency_records
                WHERE user_id = ? AND idempotency_key = ?""", userId, key);
        if (!operation.equals(row.get("operation")) || !requestHash.equals(row.get("request_hash"))) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used for a different request");
        }
        Object txId = row.get("transaction_id");
        if (txId == null) {
            throw ApiException.conflict("REQUEST_IN_PROGRESS", "An identical request is still being processed");
        }
        return Optional.of(((Number) txId).longValue());
    }

    public void complete(Long userId, String key, Long transactionId) {
        jdbc.update("UPDATE idempotency_records SET transaction_id = ? WHERE user_id = ? AND idempotency_key = ?",
                transactionId, userId, key);
    }

    public static String hash(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update((p == null ? "\u0000" : p).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
