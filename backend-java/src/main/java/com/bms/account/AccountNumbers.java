package com.bms.account;

/** 12-digit simulated account numbers: "50" + 9-digit sequence + Luhn check digit (catches most typos). */
public final class AccountNumbers {
    private AccountNumbers() {}

    public static String fromSequence(long seq) {
        String body = "50" + String.format("%09d", seq % 1_000_000_000L);
        return body + luhnDigit(body);
    }

    public static boolean isValid(String number) {
        if (number == null || !number.matches("\\d{12}")) return false;
        return luhnDigit(number.substring(0, 11)) == number.charAt(11) - '0';
    }

    static int luhnDigit(String digits) {
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }
}
