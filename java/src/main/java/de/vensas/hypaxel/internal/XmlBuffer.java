package de.vensas.hypaxel.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Growable UTF-8 byte buffer with the encoders the writer needs: ASCII literals, integers,
 * doubles, date serials and escaped text. Nothing here allocates once the buffer has grown.
 */
public final class XmlBuffer {
    private static final byte[] HEX = ascii("0123456789ABCDEF");
    private static final byte[] AMP = ascii("&amp;");
    private static final byte[] LT = ascii("&lt;");
    private static final byte[] GT = ascii("&gt;");
    private static final byte[] QUOT = ascii("&quot;");
    private static final byte[] ESCAPED_UNDERSCORE = ascii("_x005F_");
    private static final byte[] NONCHARACTER_FFFE = ascii("_xFFFE_");
    private static final byte[] NONCHARACTER_FFFF = ascii("_xFFFF_");
    private static final byte[] T_OPEN = ascii("<t>");
    private static final byte[] T_OPEN_PRESERVE = ascii("<t xml:space=\"preserve\">");
    private static final byte[] T_CLOSE = ascii("</t>");
    private static final byte[] MIN_LONG = ascii("-9223372036854775808");

    private static final byte[] DIGIT_TENS = new byte[100];
    private static final byte[] DIGIT_ONES = new byte[100];

    /**
     * ASCII characters that cannot be copied verbatim: XML specials, '_' (Excel's _xHHHH_ escape)
     * and control characters. A carriage return is included because XML parsers turn a literal
     * one into a line feed.
     */
    private static final boolean[] SPECIAL = new boolean[128];

    private static final long SERIAL_FRACTION_UNITS = 10_000_000_000L;
    private static final double MAX_EXACT_INTEGER = 9_007_199_254_740_992.0;

    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_TENS[i] = (byte) ('0' + i / 10);
            DIGIT_ONES[i] = (byte) ('0' + i % 10);
        }
        for (int i = 0; i < 0x20; i++) {
            SPECIAL[i] = i != '\t' && i != '\n';
        }
        SPECIAL['&'] = true;
        SPECIAL['<'] = true;
        SPECIAL['>'] = true;
        SPECIAL['"'] = true;
        SPECIAL['_'] = true;
    }

    private byte[] buf;
    private int pos;

    public XmlBuffer(int capacity) {
        buf = new byte[Math.max(capacity, 256)];
    }

    /** Encodes a literal that contains only ASCII characters. */
    public static byte[] ascii(String literal) {
        return literal.getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] array() {
        return buf;
    }

    public int length() {
        return pos;
    }

    public boolean isEmpty() {
        return pos == 0;
    }

    public void clear() {
        pos = 0;
    }

    public void ensure(int extra) {
        if (buf.length - pos < extra) {
            grow(extra);
        }
    }

    private void grow(long extra) {
        long required = pos + extra;
        if (required > Integer.MAX_VALUE - 16) {
            throw new OutOfMemoryError("XML buffer would exceed 2 GiB");
        }
        long doubled = Math.min(buf.length * 2L, Integer.MAX_VALUE - 16);
        buf = Arrays.copyOf(buf, (int) Math.max(required, doubled));
    }

    public XmlBuffer raw(byte[] literal) {
        ensure(literal.length);
        System.arraycopy(literal, 0, buf, pos, literal.length);
        pos += literal.length;
        return this;
    }

    /** Appends ASCII text without escaping. For cold paths; hot paths use precomputed arrays. */
    public XmlBuffer raw(String asciiText) {
        int length = asciiText.length();
        ensure(length);
        for (int i = 0; i < length; i++) {
            buf[pos++] = (byte) asciiText.charAt(i);
        }
        return this;
    }

    public XmlBuffer raw(char asciiChar) {
        ensure(1);
        buf[pos++] = (byte) asciiChar;
        return this;
    }

    public XmlBuffer putLong(long value) {
        ensure(20);
        if (value < 0) {
            if (value == Long.MIN_VALUE) {
                return raw(MIN_LONG);
            }
            buf[pos++] = '-';
            value = -value;
        }
        int end = pos + digitCount(value);
        int p = end;
        byte[] b = buf;
        while (value > Integer.MAX_VALUE) {
            long q = value / 100;
            int r = (int) (value - q * 100);
            value = q;
            b[--p] = DIGIT_ONES[r];
            b[--p] = DIGIT_TENS[r];
        }
        int v = (int) value;
        while (v >= 100) {
            int q = v / 100;
            int r = v - q * 100;
            v = q;
            b[--p] = DIGIT_ONES[r];
            b[--p] = DIGIT_TENS[r];
        }
        if (v >= 10) {
            b[--p] = DIGIT_ONES[v];
            b[--p] = DIGIT_TENS[v];
        } else {
            b[--p] = (byte) ('0' + v);
        }
        pos = end;
        return this;
    }

    private static int digitCount(long value) {
        int count = 1;
        long limit = 10;
        while (count < 19 && value >= limit) {
            limit *= 10;
            count++;
        }
        return count;
    }

    /** Appends a finite double in its shortest form that reads back as the same value. */
    public XmlBuffer putDouble(double value) {
        if (value == Math.rint(value) && Math.abs(value) < MAX_EXACT_INTEGER) {
            return putLong((long) value);
        }
        ensure(DoubleFormat.MAX_LENGTH);
        pos = DoubleFormat.write(value, buf, pos);
        return this;
    }

    /**
     * Appends an Excel date serial from whole days and nanoseconds of the day using integer
     * arithmetic only. Ten fractional digits resolve 8.64 microseconds, finer than Excel's
     * millisecond resolution.
     */
    public XmlBuffer putSerial(boolean negative, long day, long nanosOfDay) {
        long fraction = (nanosOfDay + 4320) / 8640;
        if (fraction >= SERIAL_FRACTION_UNITS) {
            day++;
            fraction -= SERIAL_FRACTION_UNITS;
        }
        if (negative && (day != 0 || fraction != 0)) {
            raw('-');
        }
        putLong(day);
        if (fraction == 0) {
            return this;
        }
        ensure(11);
        byte[] b = buf;
        int start = pos;
        b[start] = '.';
        int last = start;
        for (int i = 10; i >= 1; i--) {
            int digit = (int) (fraction % 10);
            fraction /= 10;
            b[start + i] = (byte) ('0' + digit);
            if (digit != 0 && last == start) {
                last = start + i;
            }
        }
        pos = last + 1;
        return this;
    }

    public XmlBuffer putColumnName(int column) {
        ensure(3);
        if (column > 702) {
            int c = column - 703;
            buf[pos++] = (byte) ('A' + c / 676);
            buf[pos++] = (byte) ('A' + c / 26 % 26);
            buf[pos++] = (byte) ('A' + c % 26);
        } else if (column > 26) {
            int c = column - 27;
            buf[pos++] = (byte) ('A' + c / 26);
            buf[pos++] = (byte) ('A' + c % 26);
        } else {
            buf[pos++] = (byte) ('A' + column - 1);
        }
        return this;
    }

    public XmlBuffer putCellReference(int row, int column) {
        return putColumnName(column).putLong(row);
    }

    public XmlBuffer putRange(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        return putCellReference(firstRow, firstColumn).raw(':').putCellReference(lastRow, lastColumn);
    }

    public XmlBuffer putArgb(int argb) {
        ensure(8);
        for (int shift = 28; shift >= 0; shift -= 4) {
            buf[pos++] = HEX[(argb >>> shift) & 0xF];
        }
        return this;
    }

    /** Appends a {@code <t>} element, preserving leading and trailing whitespace. */
    public XmlBuffer putTextElement(String text) {
        int length = text.length();
        boolean preserve = length > 0 && (isXmlSpace(text.charAt(0)) || isXmlSpace(text.charAt(length - 1)));
        raw(preserve ? T_OPEN_PRESERVE : T_OPEN);
        putText(text);
        return raw(T_CLOSE);
    }

    private static boolean isXmlSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /**
     * Appends text escaped for element content or a double-quoted attribute value, transcoding
     * UTF-16 to UTF-8. Characters XML 1.0 cannot carry become Excel's {@code _xHHHH_} escape,
     * literal text that looks like such an escape is protected with {@code _x005F_}, and unpaired
     * surrogates become U+FFFD.
     */
    public XmlBuffer putText(String text) {
        int length = text.length();
        reserveText(length);
        byte[] b = buf;
        int p = pos;
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c >= 0x80 || SPECIAL[c]) {
                break;
            }
            b[p++] = (byte) c;
            i++;
        }
        pos = p;
        if (i < length) {
            putTextSlow(text, i);
        }
        return this;
    }

    /** Reserves three bytes per remaining UTF-16 unit, the most plain text can need, plus one escape. */
    private void reserveText(int units) {
        long required = units * 3L + 8;
        if (buf.length - pos < required) {
            grow(required);
        }
    }

    private void putTextSlow(String text, int start) {
        int length = text.length();
        int i = start;
        while (i < length) {
            char c = text.charAt(i);
            if (c < 0x80) {
                if (SPECIAL[c]) {
                    reserveText(length - i);
                    putSpecial(text, i, c);
                } else {
                    buf[pos++] = (byte) c;
                }
                i++;
            } else if (c < 0x800) {
                buf[pos++] = (byte) (0xC0 | c >> 6);
                buf[pos++] = (byte) (0x80 | c & 0x3F);
                i++;
            } else if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                    int codePoint = Character.toCodePoint(c, text.charAt(i + 1));
                    buf[pos++] = (byte) (0xF0 | codePoint >> 18);
                    buf[pos++] = (byte) (0x80 | codePoint >> 12 & 0x3F);
                    buf[pos++] = (byte) (0x80 | codePoint >> 6 & 0x3F);
                    buf[pos++] = (byte) (0x80 | codePoint & 0x3F);
                    i += 2;
                } else {
                    buf[pos++] = (byte) 0xEF;
                    buf[pos++] = (byte) 0xBF;
                    buf[pos++] = (byte) 0xBD;
                    i++;
                }
            } else if (c >= 0xFFFE) {
                reserveText(length - i);
                raw(c == 0xFFFE ? NONCHARACTER_FFFE : NONCHARACTER_FFFF);
                i++;
            } else {
                buf[pos++] = (byte) (0xE0 | c >> 12);
                buf[pos++] = (byte) (0x80 | c >> 6 & 0x3F);
                buf[pos++] = (byte) (0x80 | c & 0x3F);
                i++;
            }
        }
    }

    private void putSpecial(String text, int index, char c) {
        switch (c) {
            case '&' -> raw(AMP);
            case '<' -> raw(LT);
            case '>' -> raw(GT);
            case '"' -> raw(QUOT);
            case '_' -> {
                if (looksLikeExcelEscape(text, index)) {
                    raw(ESCAPED_UNDERSCORE);
                } else {
                    buf[pos++] = '_';
                }
            }
            default -> {
                byte[] b = buf;
                int p = pos;
                b[p] = '_';
                b[p + 1] = 'x';
                b[p + 2] = '0';
                b[p + 3] = '0';
                b[p + 4] = HEX[c >> 4];
                b[p + 5] = HEX[c & 0xF];
                b[p + 6] = '_';
                pos = p + 7;
            }
        }
    }

    private static boolean looksLikeExcelEscape(String text, int index) {
        if (text.length() - index < 7 || text.charAt(index + 1) != 'x' || text.charAt(index + 6) != '_') {
            return false;
        }
        for (int i = index + 2; i < index + 6; i++) {
            if (Character.digit(text.charAt(i), 16) < 0 || text.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }
}
