package de.vensas.hypaxel.internal;

import java.nio.charset.StandardCharsets;

/** Small helpers for ASCII names and numbers. */
final class Ascii {
    private Ascii() {
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Parses a non-negative decimal integer that fits into an int, after trimming ASCII whitespace; -1 if invalid. */
    static int parseUnsigned(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.strip();
        if (t.startsWith("+")) {
            t = t.substring(1);
        }
        if (t.isEmpty() || t.length() > 10) {
            return -1;
        }
        long n = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return n > Integer.MAX_VALUE ? -1 : (int) n;
    }
}
