package com.bms.txn;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Transaction references such as TXN20261029-7KQ2M9XC4D: date for readability plus 50 random bits. */
public final class References {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray(); // Crockford base32
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private References() {}

    public static String newReference(OffsetDateTime at) {
        StringBuilder sb = new StringBuilder("TXN").append(at.withOffsetSameInstant(ZoneOffset.UTC).format(DAY)).append('-');
        for (int i = 0; i < 10; i++) sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return sb.toString();
    }
}
