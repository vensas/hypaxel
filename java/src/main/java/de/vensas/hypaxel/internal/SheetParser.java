package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.XlsxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Streaming worksheet parser: one row at a time, with memory bounded by the widest row. */
public final class SheetParser {
    /** Largest amount of decoded text one row may hold. */
    static final int MAX_ROW_TEXT = 256 * 1024 * 1024;

    private static final int MAX_ROWS = WorkbookEngine.MAX_ROWS;
    private static final int MAX_COLUMNS = WorkbookEngine.MAX_COLUMNS;

    private static final byte[] SHEET_DATA = Ascii.bytes("sheetData");
    private static final byte[] WORKSHEET = Ascii.bytes("worksheet");
    private static final byte[] DIALOGSHEET = Ascii.bytes("dialogsheet");
    private static final byte[] MACROSHEET = Ascii.bytes("macrosheet");
    private static final byte[] ROW = Ascii.bytes("row");
    private static final byte[] C = Ascii.bytes("c");
    private static final byte[] V = Ascii.bytes("v");
    private static final byte[] IS = Ascii.bytes("is");
    private static final byte[] T = Ascii.bytes("t");
    private static final byte[] R = Ascii.bytes("r");
    private static final byte[] R_PH = Ascii.bytes("rPh");
    private static final byte[] R_PR = Ascii.bytes("rPr");
    private static final byte[] PHONETIC_PR = Ascii.bytes("phoneticPr");

    private static final int TYPE_NUMBER = 0;
    private static final int TYPE_SHARED = 1;
    private static final int TYPE_FORMULA_STRING = 2;
    private static final int TYPE_INLINE_STRING = 3;
    private static final int TYPE_BOOL = 4;
    private static final int TYPE_ERROR = 5;
    private static final int TYPE_ISO_DATE = 6;

    private static final int BEFORE_DATA = 0;
    private static final int IN_DATA = 1;
    private static final int DONE = 2;

    private final XmlReader xml;
    private final int sharedStringCount;
    private final DateStyles styles;
    private final boolean date1904;
    private final RowBuffer row;
    private final ByteBuilder scratch = new ByteBuilder(64);
    private int state = BEFORE_DATA;
    private int previousRow;

    public SheetParser(XmlReader xml, SharedStringTable sharedStrings, DateStyles styles, boolean date1904) {
        this.xml = xml;
        this.sharedStringCount = sharedStrings.size();
        this.styles = styles;
        this.date1904 = date1904;
        this.row = new RowBuffer(sharedStrings.strings(), date1904);
    }

    public RowBuffer row() {
        return row;
    }

    public String part() {
        return xml.part();
    }

    public void close() {
        state = DONE;
        xml.close();
    }

    /**
     * Reads the next row that contains at least one value into {@link #row()}. Rows that are
     * missing from the file or only carry formatting are skipped. Returns false at the end.
     */
    public boolean next() throws IOException {
        while (true) {
            if (state == DONE) {
                return false;
            }
            if (state == BEFORE_DATA) {
                findSheetData();
                continue;
            }
            int event = xml.next();
            boolean start;
            if (event == XmlReader.START) {
                if (!xml.nameIs(ROW)) {
                    xml.skipElement();
                    continue;
                }
                start = true;
            } else if (event == XmlReader.EMPTY) {
                if (!xml.nameIs(ROW)) {
                    continue;
                }
                start = false;
            } else if (event == XmlReader.END || event == XmlReader.EOF) {
                close();
                return false;
            } else {
                continue;
            }
            int number = previousRow + 1;
            while (xml.nextAttribute()) {
                if (xml.attributeIs(R)) {
                    number = parseDigits(xml.buffer(), trimStart(xml.buffer(), xml.valueStart, xml.valueEnd), trimEnd(xml.buffer(), xml.valueStart, xml.valueEnd));
                    if (number < 0) {
                        throw xml.malformed("a row has an invalid row number");
                    }
                }
            }
            if (number < 1 || number > MAX_ROWS) {
                throw xml.malformed("row number " + number + " is outside 1..1048576");
            }
            previousRow = number;
            if (!start) {
                continue;
            }
            row.number = number;
            readCells();
            if (row.count > 0) {
                return true;
            }
        }
    }

    private void findSheetData() throws IOException {
        while (true) {
            int event = xml.next();
            if (event == XmlReader.EOF) {
                close();
                return;
            }
            if (event == XmlReader.EMPTY && xml.nameIs(SHEET_DATA)) {
                close();
                return;
            }
            if (event != XmlReader.START) {
                continue;
            }
            if (xml.nameIs(SHEET_DATA)) {
                state = IN_DATA;
                return;
            }
            if (!xml.nameIs(WORKSHEET) && !xml.nameIs(DIALOGSHEET) && !xml.nameIs(MACROSHEET)) {
                xml.skipElement();
            }
        }
    }

    private void readCells() throws IOException {
        RowBuffer row = this.row;
        row.count = 0;
        row.text.clear();
        int column = 0;
        boolean sorted = true;
        while (true) {
            int shape = xml.scanCell();
            if (shape != XmlReader.CELL_NONE) {
                byte[] buf = xml.buffer();
                int nextColumn = xml.cellRefStart < 0 ? column + 1 : parseColumn(buf, xml.cellRefStart, xml.cellRefEnd);
                int type = xml.cellTypeStart < 0 ? TYPE_NUMBER : cellType(buf, xml.cellTypeStart, xml.cellTypeEnd);
                int style = xml.cellStyleStart < 0 ? 0 : Math.max(0, parseDigits(buf, xml.cellStyleStart, xml.cellStyleEnd));
                checkCell(nextColumn, type);
                if (nextColumn <= column) {
                    sorted = false;
                }
                column = nextColumn;
                if (shape == XmlReader.CELL_VALUE) {
                    addRawValue(type, style, column, buf, xml.textStart, xml.textEnd);
                } else if (shape == XmlReader.CELL_INLINE && type == TYPE_INLINE_STRING) {
                    addText(RowBuffer.TEXT, column, buf, xml.textStart, xml.textEnd);
                }
                continue;
            }

            int event = xml.next();
            boolean start;
            if (event == XmlReader.START) {
                if (!xml.nameIs(C)) {
                    xml.skipElement();
                    continue;
                }
                start = true;
            } else if (event == XmlReader.EMPTY) {
                if (!xml.nameIs(C)) {
                    continue;
                }
                start = false;
            } else if (event == XmlReader.END || event == XmlReader.EOF) {
                break;
            } else {
                continue;
            }

            int nextColumn = column + 1;
            int type = TYPE_NUMBER;
            int style = 0;
            byte[] buf = xml.buffer();
            while (xml.nextAttribute()) {
                if (xml.attrNameEnd - xml.attrNameStart != 1) {
                    continue;
                }
                byte name = buf[xml.attrNameStart];
                if (name == 'r') {
                    nextColumn = parseColumn(buf, xml.valueStart, xml.valueEnd);
                } else if (name == 't') {
                    type = cellType(buf, xml.valueStart, xml.valueEnd);
                } else if (name == 's') {
                    style = Math.max(0, parseDigits(buf, xml.valueStart, xml.valueEnd));
                }
            }
            checkCell(nextColumn, type);
            if (nextColumn <= column) {
                sorted = false;
            }
            column = nextColumn;
            if (!start) {
                continue;
            }
            readCell(type, style, column);
            if (row.text.length() > MAX_ROW_TEXT) {
                throw new XlsxException(XlsxException.Kind.LIMIT_EXCEEDED, xml.part(),
                        "row " + row.number + " holds more than " + MAX_ROW_TEXT + " bytes of text");
            }
        }
        if (!sorted) {
            row.sortByColumn();
        }
    }

    /** Reads the children of a {@code <c>} element up to its end tag and adds its value, if any. */
    private void readCell(int type, int style, int column) throws IOException {
        RowBuffer row = this.row;
        boolean isText = type == TYPE_FORMULA_STRING || type == TYPE_INLINE_STRING || type == TYPE_ERROR;
        int textStart = row.text.length();
        boolean hasValue = false;
        ByteBuilder scratch = this.scratch;
        scratch.clear();
        while (true) {
            int event = xml.next();
            if (event == XmlReader.END || event == XmlReader.EOF) {
                break;
            }
            if (event != XmlReader.START) {
                continue;
            }
            if (xml.nameIs(V)) {
                hasValue = true;
                if (isText) {
                    xml.readTextInto(row.text, true, MAX_ROW_TEXT);
                } else if (xml.tryRawText()) {
                    String error = XmlText.decode(scratch, xml.buffer(), xml.textStart, xml.textEnd, true, false);
                    if (error != null) {
                        throw xml.malformed(error);
                    }
                } else {
                    xml.readTextInto(scratch, false, MAX_ROW_TEXT);
                }
            } else if (type == TYPE_INLINE_STRING && xml.nameIs(IS)) {
                hasValue = true;
                readInlineString();
            } else {
                xml.skipElement();
            }
        }
        if (!hasValue) {
            row.text.truncate(textStart);
            return;
        }
        if (row.text.length() > MAX_ROW_TEXT || scratch.length() > MAX_ROW_TEXT) {
            throw new XlsxException(XlsxException.Kind.LIMIT_EXCEEDED, xml.part(),
                    "row " + row.number + " holds more than " + MAX_ROW_TEXT + " bytes of text");
        }

        if (type == TYPE_FORMULA_STRING || type == TYPE_INLINE_STRING || type == TYPE_ERROR) {
            int i = add(column, type == TYPE_ERROR ? RowBuffer.ERROR : RowBuffer.TEXT);
            row.starts[i] = textStart;
            row.ends[i] = row.text.length();
            return;
        }
        addValue(type, style, column, scratch.data, 0, scratch.length);
    }

    private void checkCell(int column, int type) {
        if (column < 1 || column > MAX_COLUMNS) {
            throw xml.malformed("row " + row.number + " has a cell with an invalid reference");
        }
        if (type < 0) {
            throw xml.malformed("row " + row.number + " has a cell with an unknown type");
        }
    }

    /** Adds text that still contains entity references and Excel escapes. */
    private void addText(byte type, int column, byte[] raw, int from, int to) {
        int start = row.text.length();
        String error = XmlText.decode(row.text, raw, from, to, true, true);
        if (error != null) {
            throw xml.malformed(error);
        }
        if (row.text.length() > MAX_ROW_TEXT) {
            throw new XlsxException(XlsxException.Kind.LIMIT_EXCEEDED, xml.part(),
                    "row " + row.number + " holds more than " + MAX_ROW_TEXT + " bytes of text");
        }
        int i = add(column, type);
        row.starts[i] = start;
        row.ends[i] = row.text.length();
    }

    /** Adds the raw (undecoded) content of a {@code <v>} element found by the fast path. */
    private void addRawValue(int type, int style, int column, byte[] raw, int from, int to) {
        if (type == TYPE_FORMULA_STRING || type == TYPE_INLINE_STRING || type == TYPE_ERROR) {
            addText(type == TYPE_ERROR ? RowBuffer.ERROR : RowBuffer.TEXT, column, raw, from, to);
            return;
        }
        for (int i = from; i < to; i++) {
            if (raw[i] == '&') {
                scratch.clear();
                String error = XmlText.decode(scratch, raw, from, to, true, false);
                if (error != null) {
                    throw xml.malformed(error);
                }
                addValue(type, style, column, scratch.data, 0, scratch.length);
                return;
            }
        }
        addValue(type, style, column, raw, from, to);
    }

    /** Adds a decoded value of a cell that is not text. */
    private void addValue(int type, int style, int column, byte[] raw, int start, int end) {
        RowBuffer row = this.row;
        int from = trimStart(raw, start, end);
        int to = trimEnd(raw, from, end);
        switch (type) {
            case TYPE_NUMBER -> {
                if (from == to) {
                    return;
                }
                double n = Numbers.parse(raw, from, to);
                if (Double.isNaN(n)) {
                    throw badValue("number", raw, from, to);
                }
                int kind = styles.kindCode(style);
                int i = add(column, kind == 0 ? RowBuffer.NUMBER : RowBuffer.DATE);
                row.numbers[i] = n;
                row.kinds[i] = (byte) kind;
            }
            case TYPE_SHARED -> {
                int index = parseDigits(raw, from, to);
                if (index < 0 || index >= sharedStringCount) {
                    throw badValue("shared string index", raw, from, to);
                }
                int i = add(column, RowBuffer.SHARED);
                row.starts[i] = index;
            }
            case TYPE_BOOL -> {
                int length = to - from;
                boolean value;
                if (length == 1 && raw[from] == '1' || length == 4 && "true".equals(latin1(raw, from, to))) {
                    value = true;
                } else if (length == 1 && raw[from] == '0' || length == 5 && "false".equals(latin1(raw, from, to))) {
                    value = false;
                } else {
                    throw badValue("boolean", raw, from, to);
                }
                int i = add(column, RowBuffer.BOOL);
                row.numbers[i] = value ? 1 : 0;
            }
            default -> {
                String iso = new String(raw, from, to - from, StandardCharsets.UTF_8);
                double serial = DateSerials.isoToSerial(iso, date1904);
                if (Double.isNaN(serial)) {
                    int textStart = row.text.length();
                    row.text.append(raw, from, to - from);
                    int i = add(column, RowBuffer.TEXT);
                    row.starts[i] = textStart;
                    row.ends[i] = row.text.length();
                } else {
                    int i = add(column, RowBuffer.DATE);
                    row.numbers[i] = serial;
                    row.kinds[i] = (byte) (DateSerials.isoKind(iso).ordinal() + 1);
                }
            }
        }
    }

    private int add(int column, byte type) {
        if (row.count >= MAX_COLUMNS) {
            throw xml.malformed("row " + row.number + " has more than 16384 cells");
        }
        return row.add(column, type);
    }

    /** Reads {@code <is>} content: plain {@code <t>} or rich-text runs, without phonetic runs. */
    private void readInlineString() throws IOException {
        int target = xml.depth() - 1;
        while (true) {
            int event = xml.next();
            if (event == XmlReader.START) {
                if (xml.nameIs(T)) {
                    xml.readTextInto(row.text, true, MAX_ROW_TEXT);
                    if (row.text.length() > MAX_ROW_TEXT) {
                        return;
                    }
                } else if (xml.nameIs(R_PH) || xml.nameIs(R_PR) || xml.nameIs(PHONETIC_PR)) {
                    xml.skipElement();
                }
            } else if (event == XmlReader.END && xml.depth() == target) {
                return;
            }
        }
    }

    private XlsxException badValue(String what, byte[] raw, int from, int to) {
        String shown = new String(raw, from, to - from, StandardCharsets.UTF_8);
        if (shown.length() > 40) {
            shown = shown.substring(0, 40);
        }
        return xml.malformed("row " + row.number + " has an invalid " + what + " \"" + shown + "\"");
    }

    private static String latin1(byte[] raw, int from, int to) {
        return new String(raw, from, to - from, StandardCharsets.ISO_8859_1);
    }

    private static int cellType(byte[] b, int from, int to) {
        int length = to - from;
        if (length == 1) {
            switch (b[from]) {
                case 's':
                    return TYPE_SHARED;
                case 'n':
                    return TYPE_NUMBER;
                case 'b':
                    return TYPE_BOOL;
                case 'e':
                    return TYPE_ERROR;
                case 'd':
                    return TYPE_ISO_DATE;
                default:
                    return -1;
            }
        }
        if (length == 3 && b[from] == 's' && b[from + 1] == 't' && b[from + 2] == 'r') {
            return TYPE_FORMULA_STRING;
        }
        if (length == 9 && "inlineStr".equals(latin1(b, from, to))) {
            return TYPE_INLINE_STRING;
        }
        return -1;
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f';
    }

    private static int trimStart(byte[] b, int from, int to) {
        while (from < to && isWhitespace(b[from])) {
            from++;
        }
        return from;
    }

    private static int trimEnd(byte[] b, int from, int to) {
        while (to > from && isWhitespace(b[to - 1])) {
            to--;
        }
        return to;
    }

    /** Parses a non-negative decimal integer that fits into an int; -1 if invalid. */
    static int parseDigits(byte[] b, int from, int to) {
        int length = to - from;
        if (length == 0 || length > 10) {
            return -1;
        }
        long n = 0;
        for (int i = from; i < to; i++) {
            int c = b[i] - '0';
            if (c < 0 || c > 9) {
                return -1;
            }
            n = n * 10 + c;
        }
        return n > Integer.MAX_VALUE ? -1 : (int) n;
    }

    /** Parses the column letters of a cell reference such as {@code AB12}; -1 if there are none or more than three. */
    static int parseColumn(byte[] b, int from, int to) {
        int column = 0;
        int letters = 0;
        for (int i = from; i < to; i++) {
            int c = b[i] | 0x20;
            if (c < 'a' || c > 'z') {
                break;
            }
            if (++letters > 3) {
                return -1;
            }
            column = column * 26 + (c - 'a' + 1);
        }
        return letters > 0 ? column : -1;
    }
}
