package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.XlsxException;
import java.io.IOException;
import java.util.Arrays;

/**
 * Minimal pull parser for the XML that spreadsheet packages contain, working on UTF-8 bytes.
 *
 * <p>It handles elements, attributes, text, CDATA, comments and processing instructions, and
 * ignores namespaces by matching local names. Document type declarations are rejected, so entity
 * expansion attacks are impossible. Every token must fit into a bounded buffer.
 *
 * <p>Token positions ({@link #nameStart}, {@link #textStart}, ...) index into {@link #buffer()}
 * and are valid until the next call that reads input.
 */
public final class XmlReader {
    public static final int EOF = 0;
    public static final int START = 1;
    public static final int EMPTY = 2;
    public static final int END = 3;
    public static final int TEXT = 4;
    public static final int CDATA = 5;

    /** Largest single token (a tag or a run of text). Excel limits cell text to 32,767 characters. */
    public static final int MAX_TOKEN = 16 * 1024 * 1024;
    static final int INITIAL_BUFFER = 64 * 1024;

    private static final byte[] COMMENT_END = {'-', '-', '>'};
    private static final byte[] PI_END = {'?', '>'};
    private static final byte[] CDATA_END = {']', ']', '>'};
    private static final byte[] XMLNS = {'x', 'm', 'l', 'n', 's'};

    private final ByteInput input;
    private final String part;
    private byte[] buf;
    private int pos;
    private int end;
    private boolean eof;
    private int depth;
    private boolean started;

    /** Local name of the current start, empty or end tag. */
    public int nameStart;
    public int nameEnd;
    /** Text or CDATA content of the current token, or the raw text found by {@link #tryRawText()}. */
    public int textStart;
    public int textEnd;
    /** The attribute found by {@link #nextAttribute()}: local name and raw value. */
    public int attrNameStart;
    public int attrNameEnd;
    public int valueStart;
    public int valueEnd;
    private int attrsStart;
    private int attrsEnd;
    private int attrPos;

    public XmlReader(ByteInput input, String part) {
        this(input, part, INITIAL_BUFFER);
    }

    XmlReader(ByteInput input, String part, int bufferSize) {
        this.input = input;
        this.part = part;
        this.buf = new byte[bufferSize];
    }

    public String part() {
        return part;
    }

    public byte[] buffer() {
        return buf;
    }

    public int depth() {
        return depth;
    }

    /** Releases the underlying input. */
    public void close() {
        input.close();
        eof = true;
        pos = end;
    }

    public XlsxException malformed(String message) {
        return new XlsxException(XlsxException.Kind.MALFORMED, part, message);
    }

    private XlsxException unexpectedEof() {
        return malformed("unexpected end of XML");
    }

    /** Reads more input, keeping {@code pos..end}. Returns false at the end of input. */
    private boolean fill() throws IOException {
        if (eof) {
            return false;
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, end - pos);
            end -= pos;
            pos = 0;
        }
        if (end == buf.length) {
            if (buf.length >= MAX_TOKEN) {
                throw new XlsxException(XlsxException.Kind.LIMIT_EXCEEDED, part, "an XML token is larger than " + MAX_TOKEN + " bytes");
            }
            buf = Arrays.copyOf(buf, Math.min(buf.length * 2, MAX_TOKEN));
        }
        int n = input.read(buf, end, buf.length - end);
        if (n <= 0) {
            eof = true;
            return false;
        }
        end += n;
        return true;
    }

    /** Ensures that at least {@code n} bytes are buffered after {@code pos}. */
    private boolean need(int n) throws IOException {
        while (end - pos < n) {
            if (!fill()) {
                return false;
            }
        }
        return true;
    }

    /** Finds {@code b} at or after {@code pos + from}, reading more input as needed; returns the offset from {@code pos} or -1. */
    private int find(byte b, int from) throws IOException {
        while (true) {
            byte[] buf = this.buf;
            for (int i = pos + from, end = this.end; i < end; i++) {
                if (buf[i] == b) {
                    return i - pos;
                }
            }
            from = Math.max(from, end - pos);
            if (!fill()) {
                return -1;
            }
        }
    }

    private int find(byte[] needle, int from) throws IOException {
        while (true) {
            int last = end - needle.length;
            outer:
            for (int i = pos + from; i <= last; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (buf[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i - pos;
            }
            from = Math.max(from, end - pos - needle.length + 1);
            if (!fill()) {
                return -1;
            }
        }
    }

    /** Finds the {@code >} that closes a start tag, skipping {@code >} inside quoted attribute values. */
    private int findTagEnd() throws IOException {
        int at = 1;
        byte quote = 0;
        while (true) {
            byte[] buf = this.buf;
            int i = pos + at;
            int end = this.end;
            while (i < end) {
                byte b = buf[i];
                if (quote != 0) {
                    if (b == quote) {
                        quote = 0;
                    }
                } else if (b == '>') {
                    return i - pos;
                } else if (b == '"' || b == '\'') {
                    quote = b;
                }
                i++;
            }
            at = i - pos;
            if (!fill()) {
                return -1;
            }
        }
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f';
    }

    private boolean startsWith(String prefix) {
        if (end - pos < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (buf[pos + i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Skips a UTF-8 byte order mark and rejects UTF-16, which spreadsheet producers do not use. */
    private void checkEncoding() throws IOException {
        started = true;
        need(3);
        int n = end - pos;
        if (n >= 3 && buf[pos] == (byte) 0xEF && buf[pos + 1] == (byte) 0xBB && buf[pos + 2] == (byte) 0xBF) {
            pos += 3;
        } else if (n >= 2 && ((buf[pos] == (byte) 0xFF && buf[pos + 1] == (byte) 0xFE) || (buf[pos] == (byte) 0xFE && buf[pos + 1] == (byte) 0xFF))) {
            throw new XlsxException(XlsxException.Kind.UNSUPPORTED, part, "the part is encoded as UTF-16");
        }
    }

    private void setLocalName(int start, int end) {
        int i = end - 1;
        while (i >= start && buf[i] != ':') {
            i--;
        }
        nameStart = i + 1;
        nameEnd = end;
    }

    /** Reads the next token and returns its type. */
    public int next() throws IOException {
        if (!started) {
            checkEncoding();
        }
        while (true) {
            if (pos == end && !fill()) {
                if (depth > 0) {
                    throw unexpectedEof();
                }
                return EOF;
            }

            if (buf[pos] != '<') {
                int length = find((byte) '<', 0);
                if (length < 0) {
                    length = end - pos;
                }
                textStart = pos;
                textEnd = pos + length;
                pos += length;
                return TEXT;
            }

            if (!need(2)) {
                throw unexpectedEof();
            }
            byte second = buf[pos + 1];
            if (second == '/') {
                int close = find((byte) '>', 2);
                if (close < 0) {
                    throw unexpectedEof();
                }
                int begin = pos;
                pos += close + 1;
                if (depth == 0) {
                    throw malformed("unbalanced end tag");
                }
                depth--;
                int nameEnd = begin + close;
                while (nameEnd > begin + 2 && isWhitespace(buf[nameEnd - 1])) {
                    nameEnd--;
                }
                setLocalName(begin + 2, nameEnd);
                return END;
            } else if (second == '?') {
                int close = find(PI_END, 2);
                if (close < 0) {
                    throw unexpectedEof();
                }
                pos += close + 2;
            } else if (second == '!') {
                need(9);
                if (startsWith("<!--")) {
                    int close = find(COMMENT_END, 4);
                    if (close < 0) {
                        throw unexpectedEof();
                    }
                    pos += close + 3;
                } else if (startsWith("<![CDATA[")) {
                    int close = find(CDATA_END, 9);
                    if (close < 0) {
                        throw unexpectedEof();
                    }
                    int begin = pos;
                    pos += close + 3;
                    textStart = begin + 9;
                    textEnd = begin + close;
                    return CDATA;
                } else if (startsWith("<!DOCTYPE")) {
                    throw malformed("document type declarations are not allowed");
                } else {
                    throw malformed("unexpected markup declaration");
                }
            } else {
                int close = findTagEnd();
                if (close < 0) {
                    throw unexpectedEof();
                }
                int begin = pos;
                pos += close + 1;
                int innerStart = begin + 1;
                int innerEnd = begin + close;
                boolean empty = innerEnd > innerStart && buf[innerEnd - 1] == '/';
                if (empty) {
                    innerEnd--;
                }
                int nameEnd = innerStart;
                while (nameEnd < innerEnd && !isWhitespace(buf[nameEnd])) {
                    nameEnd++;
                }
                if (nameEnd == innerStart) {
                    throw malformed("an element has no name");
                }
                setLocalName(innerStart, nameEnd);
                attrsStart = nameEnd;
                attrPos = nameEnd;
                attrsEnd = innerEnd;
                if (empty) {
                    return EMPTY;
                }
                depth++;
                return START;
            }
        }
    }

    /** True if the current tag's local name equals {@code name}. */
    public boolean nameIs(byte[] name) {
        return equal(nameStart, nameEnd, name);
    }

    /** True if the current attribute's local name equals {@code name}. */
    public boolean attributeIs(byte[] name) {
        return equal(attrNameStart, attrNameEnd, name);
    }

    private boolean equal(int start, int end, byte[] expected) {
        if (end - start != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (buf[start + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Advances to the next attribute of the current start or empty tag, skipping namespace
     * declarations. Returns false when there are no more.
     */
    public boolean nextAttribute() {
        while (true) {
            int i = attrPos;
            int end = attrsEnd;
            while (i < end && isWhitespace(buf[i])) {
                i++;
            }
            if (i >= end) {
                attrPos = end;
                return false;
            }
            int nameStart = i;
            while (i < end && buf[i] != '=') {
                i++;
            }
            if (i >= end) {
                attrPos = end;
                return false;
            }
            int nameEnd = i;
            while (nameEnd > nameStart && isWhitespace(buf[nameEnd - 1])) {
                nameEnd--;
            }
            i++;
            while (i < end && isWhitespace(buf[i])) {
                i++;
            }
            if (i >= end || (buf[i] != '"' && buf[i] != '\'')) {
                attrPos = end;
                return false;
            }
            byte quote = buf[i];
            int valueStart = ++i;
            while (i < end && buf[i] != quote) {
                i++;
            }
            if (i >= end) {
                attrPos = end;
                return false;
            }
            attrPos = i + 1;
            boolean namespace = (nameEnd - nameStart == 5 && equal(nameStart, nameEnd, XMLNS))
                    || (nameEnd - nameStart >= 6 && equal(nameStart, nameStart + 5, XMLNS) && buf[nameStart + 5] == ':');
            if (namespace) {
                continue;
            }
            int local = nameEnd - 1;
            while (local >= nameStart && buf[local] != ':') {
                local--;
            }
            attrNameStart = local + 1;
            attrNameEnd = nameEnd;
            this.valueStart = valueStart;
            this.valueEnd = i;
            return true;
        }
    }

    /**
     * Finds an attribute of the current tag by local name and returns its decoded value, or null.
     * Resets the attribute cursor of {@link #nextAttribute()}.
     */
    public String attribute(byte[] name) {
        int saved = attrPos;
        attrPos = attrsStart;
        try {
            while (nextAttribute()) {
                if (attributeIs(name)) {
                    ByteBuilder out = new ByteBuilder(valueEnd - valueStart);
                    if (XmlText.decode(out, buf, valueStart, valueEnd, true, false) != null) {
                        return null;
                    }
                    return out.toString();
                }
            }
            return null;
        } finally {
            attrPos = saved;
        }
    }

    /**
     * For an element whose start tag was just read: if its content is plain text followed by an
     * end tag, consumes both, sets {@link #textStart} and {@link #textEnd} to the raw (undecoded)
     * text and returns true. Otherwise consumes nothing and returns false.
     */
    public boolean tryRawText() throws IOException {
        int lt = find((byte) '<', 0);
        if (lt < 0) {
            return false;
        }
        if (!need(lt + 2) || buf[pos + lt + 1] != '/') {
            return false;
        }
        int gt = find((byte) '>', lt + 2);
        if (gt < 0) {
            return false;
        }
        textStart = pos;
        textEnd = pos + lt;
        pos += gt + 1;
        depth--;
        return true;
    }

    /** {@link #scanCell()}: no cell of a common shape at the current position; nothing was consumed. */
    public static final int CELL_NONE = 0;
    /** {@link #scanCell()}: a cell without content, {@code <c .../>} or {@code <c ...></c>}. */
    public static final int CELL_EMPTY = 1;
    /** {@link #scanCell()}: {@code <c ...><v>raw</v></c>}. */
    public static final int CELL_VALUE = 2;
    /** {@link #scanCell()}: {@code <c ...><is><t>raw</t></is></c>}. */
    public static final int CELL_INLINE = 3;

    private static final int CELL_LOOKAHEAD = 1024;
    private static final byte[] END_V_C = Ascii.bytes("</v></c>");
    private static final byte[] END_T_IS_C = Ascii.bytes("</t></is></c>");

    /** Attribute values of the cell found by {@link #scanCell()}; a start of -1 means the attribute is absent. */
    public int cellRefStart;
    public int cellRefEnd;
    public int cellTypeStart;
    public int cellTypeEnd;
    public int cellStyleStart;
    public int cellStyleEnd;

    private boolean matches(int at, byte[] expected) {
        if (at + expected.length > end) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (buf[at + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fast path for the cell shapes that nearly all producers write. If a complete, unprefixed
     * cell of one of these shapes lies at the current position, consumes it, sets the
     * {@code cell*} attribute ranges and {@link #textStart}/{@link #textEnd} to the raw value, and
     * returns its shape. Otherwise consumes nothing and returns {@link #CELL_NONE}, and the caller
     * falls back to {@link #next()}. Positions are valid until the next call that reads input.
     */
    public int scanCell() throws IOException {
        while (end - pos < CELL_LOOKAHEAD && fill()) {
            // Keep a whole cell in the buffer so that it can be matched in one pass.
        }
        byte[] b = buf;
        int e = end;
        int i = pos + 2;
        if (i >= e || b[pos] != '<' || b[pos + 1] != 'c') {
            return CELL_NONE;
        }
        byte c = b[i];
        if (c != ' ' && c != '>' && c != '/') {
            return CELL_NONE;
        }
        cellRefStart = -1;
        cellTypeStart = -1;
        cellStyleStart = -1;
        while (true) {
            if (i >= e) {
                return CELL_NONE;
            }
            c = b[i];
            if (c == ' ') {
                i++;
                continue;
            }
            if (c == '>') {
                i++;
                break;
            }
            if (c == '/') {
                if (i + 1 < e && b[i + 1] == '>') {
                    pos = i + 2;
                    return CELL_EMPTY;
                }
                return CELL_NONE;
            }
            int nameStart = i;
            while (i < e && (c = b[i]) != '=') {
                if (c == ':' || c == '>' || c == '/' || isWhitespace(c)) {
                    return CELL_NONE;
                }
                i++;
            }
            int nameLength = i - nameStart;
            if (++i >= e) {
                return CELL_NONE;
            }
            byte quote = b[i];
            if (quote != '"' && quote != '\'') {
                return CELL_NONE;
            }
            int valueStart = ++i;
            while (i < e && b[i] != quote) {
                i++;
            }
            if (i >= e) {
                return CELL_NONE;
            }
            if (nameLength == 1) {
                byte name = b[nameStart];
                if (name == 'r') {
                    cellRefStart = valueStart;
                    cellRefEnd = i;
                } else if (name == 't') {
                    cellTypeStart = valueStart;
                    cellTypeEnd = i;
                } else if (name == 's') {
                    cellStyleStart = valueStart;
                    cellStyleEnd = i;
                }
            }
            i++;
        }
        if (i + 4 > e || b[i] != '<') {
            return CELL_NONE;
        }
        byte second = b[i + 1];
        if (second == 'v' && b[i + 2] == '>') {
            int start = i + 3;
            int j = start;
            while (j < e && b[j] != '<') {
                j++;
            }
            if (!matches(j, END_V_C)) {
                return CELL_NONE;
            }
            textStart = start;
            textEnd = j;
            pos = j + END_V_C.length;
            return CELL_VALUE;
        }
        if (second == '/' && b[i + 2] == 'c' && b[i + 3] == '>') {
            pos = i + 4;
            return CELL_EMPTY;
        }
        if (second == 'i' && i + 6 < e && b[i + 2] == 's' && b[i + 3] == '>') {
            int j = scanText(i + 4, END_T_IS_C);
            if (j < 0) {
                return CELL_NONE;
            }
            pos = j;
            return CELL_INLINE;
        }
        return CELL_NONE;
    }

    /**
     * Matches {@code <t>raw} or {@code <t attributes>raw} at {@code at}, followed by
     * {@code close}. On success sets {@link #textStart}/{@link #textEnd} to the raw text and
     * returns the position after {@code close}; otherwise returns -1.
     */
    private int scanText(int at, byte[] close) {
        byte[] b = buf;
        int e = end;
        if (at + 2 >= e || b[at] != '<' || b[at + 1] != 't') {
            return -1;
        }
        int j = at + 2;
        if (b[j] == ' ') {
            byte quote = 0;
            while (j < e && (quote != 0 || b[j] != '>')) {
                byte x = b[j];
                if (quote != 0) {
                    if (x == quote) {
                        quote = 0;
                    }
                } else if (x == '"' || x == '\'') {
                    quote = x;
                }
                j++;
            }
            if (j >= e || b[j - 1] == '/') {
                return -1;
            }
        } else if (b[j] != '>') {
            return -1;
        }
        int start = ++j;
        while (j < e && b[j] != '<') {
            j++;
        }
        if (!matches(j, close)) {
            return -1;
        }
        textStart = start;
        textEnd = j;
        return j + close.length;
    }

    private static final byte[] SI_OPEN = Ascii.bytes("<si>");
    private static final byte[] END_T_SI = Ascii.bytes("</t></si>");

    /**
     * Fast path for shared string items: if {@code <si><t>raw</t></si>} (unprefixed, optionally
     * with attributes on {@code t}) lies at the current position, consumes it, sets
     * {@link #textStart}/{@link #textEnd} to the raw text and returns true. Otherwise consumes
     * nothing and returns false.
     */
    public boolean scanSharedString() throws IOException {
        while (end - pos < CELL_LOOKAHEAD && fill()) {
            // Keep a whole item in the buffer so that it can be matched in one pass.
        }
        if (!matches(pos, SI_OPEN)) {
            return false;
        }
        int j = scanText(pos + SI_OPEN.length, END_T_SI);
        if (j < 0) {
            return false;
        }
        pos = j;
        return true;
    }

    /** Skips the rest of the element whose start tag was just read. */
    public void skipElement() throws IOException {
        int target = depth - 1;
        while (true) {
            if (next() == END && depth == target) {
                return;
            }
        }
    }

    /**
     * Appends the decoded text of the element whose start tag was just read and consumes its end
     * tag. Text inside nested elements is included.
     */
    public void readTextInto(ByteBuilder out, boolean excelEscapes) throws IOException {
        readTextInto(out, excelEscapes, Integer.MAX_VALUE - 8);
    }

    /**
     * Like {@link #readTextInto(ByteBuilder, boolean)}, but stops early, in the middle of the
     * element, once {@code out} holds more than {@code maxLength} bytes. The caller then reports
     * the limit and stops reading.
     */
    public void readTextInto(ByteBuilder out, boolean excelEscapes, int maxLength) throws IOException {
        int target = depth - 1;
        while (out.length <= maxLength) {
            int event = next();
            String error = null;
            if (event == TEXT) {
                error = XmlText.decode(out, buf, textStart, textEnd, true, excelEscapes);
            } else if (event == CDATA) {
                error = XmlText.decode(out, buf, textStart, textEnd, false, excelEscapes);
            } else if (event == END && depth == target) {
                return;
            }
            if (error != null) {
                throw malformed(error);
            }
        }
    }
}
