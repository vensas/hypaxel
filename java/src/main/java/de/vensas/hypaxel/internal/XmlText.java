package de.vensas.hypaxel.internal;

/** Decoding of XML character data into UTF-8 bytes. */
final class XmlText {
    private XmlText() {
    }

    /**
     * Appends {@code raw[from..to)} to {@code out}. With {@code entities}, entity and character
     * references are resolved and line endings normalized (text content); without, the bytes are
     * taken as they are (CDATA). With {@code excelEscapes}, Excel's {@code _xHHHH_} escapes are
     * decoded as well. Returns an error message, or null on success; on failure {@code out} is
     * left as it was.
     */
    static String decode(ByteBuilder out, byte[] raw, int from, int to, boolean entities, boolean excelEscapes) {
        int start = out.length;
        boolean special = false;
        boolean underscore = false;
        for (int i = from; i < to; i++) {
            byte b = raw[i];
            if (b == '&' || b == '\r') {
                special = true;
            } else if (b == '_') {
                underscore = true;
            }
        }
        if (special && entities) {
            String error = decodeEntities(out, raw, from, to);
            if (error != null) {
                out.length = start;
                return error;
            }
        } else {
            out.append(raw, from, to - from);
        }
        if (underscore && excelEscapes) {
            decodeExcelEscapes(out, start);
        }
        return null;
    }

    private static String decodeEntities(ByteBuilder out, byte[] raw, int from, int to) {
        out.ensure(to - from);
        int i = from;
        while (i < to) {
            byte b = raw[i];
            if (b == '\r') {
                // XML end-of-line handling: CR LF and lone CR become LF.
                out.append((byte) '\n');
                i += i + 1 < to && raw[i + 1] == '\n' ? 2 : 1;
                continue;
            }
            if (b != '&') {
                out.append(b);
                i++;
                continue;
            }
            int semi = -1;
            for (int j = i + 1; j < to && j <= i + 12; j++) {
                if (raw[j] == ';') {
                    semi = j;
                    break;
                }
            }
            if (semi < 0) {
                return "unterminated entity reference";
            }
            int length = semi - i - 1;
            int code;
            if (is(raw, i + 1, length, "lt")) {
                code = '<';
            } else if (is(raw, i + 1, length, "gt")) {
                code = '>';
            } else if (is(raw, i + 1, length, "amp")) {
                code = '&';
            } else if (is(raw, i + 1, length, "quot")) {
                code = '"';
            } else if (is(raw, i + 1, length, "apos")) {
                code = '\'';
            } else if (length >= 2 && raw[i + 1] == '#' && (raw[i + 2] == 'x' || raw[i + 2] == 'X')) {
                code = parseRadix(raw, i + 3, semi, 16);
            } else if (length >= 1 && raw[i + 1] == '#') {
                code = parseRadix(raw, i + 2, semi, 10);
            } else {
                code = -1;
            }
            if (code < 0 || code > Character.MAX_CODE_POINT || (code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE)) {
                return "unknown entity reference";
            }
            appendCodePoint(out, code);
            i = semi + 1;
        }
        return null;
    }

    private static boolean is(byte[] raw, int at, int length, String name) {
        if (length != name.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (raw[at + i] != name.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Parses 1 to 8 digits; returns -1 if invalid. */
    static int parseRadix(byte[] raw, int from, int to, int radix) {
        int length = to - from;
        if (length < 1 || length > 8) {
            return -1;
        }
        long value = 0;
        for (int i = from; i < to; i++) {
            int digit = Character.digit(raw[i], radix);
            if (digit < 0) {
                return -1;
            }
            value = value * radix + digit;
        }
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    static void appendCodePoint(ByteBuilder out, int code) {
        if (code < 0x80) {
            out.append((byte) code);
        } else if (code < 0x800) {
            out.append((byte) (0xC0 | code >> 6));
            out.append((byte) (0x80 | code & 0x3F));
        } else if (code < 0x10000) {
            out.append((byte) (0xE0 | code >> 12));
            out.append((byte) (0x80 | code >> 6 & 0x3F));
            out.append((byte) (0x80 | code & 0x3F));
        } else {
            out.append((byte) (0xF0 | code >> 18));
            out.append((byte) (0x80 | code >> 12 & 0x3F));
            out.append((byte) (0x80 | code >> 6 & 0x3F));
            out.append((byte) (0x80 | code & 0x3F));
        }
    }

    private static boolean isEscape(byte[] b, int i, int end) {
        return i + 7 <= end && b[i] == '_' && b[i + 1] == 'x' && b[i + 6] == '_';
    }

    /**
     * Decodes {@code _xHHHH_} escapes in {@code out[start..]}. Surrogate pairs written as two
     * escapes are combined; unpaired surrogates are left as written.
     */
    static void decodeExcelEscapes(ByteBuilder out, int start) {
        byte[] b = out.data;
        int end = out.length;
        int first = -1;
        for (int i = start; i + 1 < end; i++) {
            if (b[i] == '_' && b[i + 1] == 'x') {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return;
        }
        // Decoding only shrinks the text, so it can be done in place.
        int w = first;
        int i = first;
        while (i < end) {
            if (isEscape(b, i, end)) {
                int code = parseRadix(b, i + 2, i + 6, 16);
                if (code >= 0) {
                    int next = i + 7;
                    boolean valid = !Character.isSurrogate((char) code);
                    if (Character.isHighSurrogate((char) code) && isEscape(b, next, end)) {
                        int low = parseRadix(b, next + 2, next + 6, 16);
                        if (low >= 0 && Character.isLowSurrogate((char) low)) {
                            code = Character.toCodePoint((char) code, (char) low);
                            next += 7;
                            valid = true;
                        }
                    }
                    if (valid) {
                        int before = out.length;
                        out.length = w;
                        appendCodePoint(out, code);
                        w = out.length;
                        out.length = before;
                        i = next;
                        continue;
                    }
                }
            }
            b[w++] = b[i++];
        }
        out.length = w;
    }
}
