package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.Column;
import de.vensas.hypaxel.SheetOptions;
import de.vensas.hypaxel.StringStorage;
import de.vensas.hypaxel.Style;
import de.vensas.hypaxel.XlsxException;
import de.vensas.hypaxel.XlsxException.Kind;
import de.vensas.hypaxel.XlsxOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns cell calls into compressed XLSX bytes held in memory. It performs no I/O of its own: the
 * public writer decides when the bytes go to the destination through {@link #drainTo}.
 */
public final class WorkbookEngine {
    public static final int MAX_ROWS = 1_048_576;
    public static final int MAX_COLUMNS = 16_384;
    public static final int MAX_TEXT_LENGTH = 32_767;

    /** Room for {@code <c r="XFD1048576" s="2147483647" t="inlineStr">} and a number in {@code <v>}. */
    private static final int CELL_RESERVE = 128;
    private static final int LONG_TEXT = 4096;

    private static final long EPOCH_DAY_1900 = -25_569; // 1899-12-30
    private static final long EPOCH_DAY_1904 = -24_107; // 1904-01-01
    private static final long MAX_SERIAL = 2_958_465; // 9999-12-31 in the 1900 system
    private static final long SERIAL_OFFSET_1904 = 1462;

    private static final byte[] ROW_OPEN = XmlBuffer.ascii("<row r=\"");
    private static final byte[] ROW_CLOSE = XmlBuffer.ascii("</row>");
    private static final byte[] CELL = XmlBuffer.ascii("<c");
    private static final byte[] CELL_REF = XmlBuffer.ascii("<c r=\"");
    private static final byte[] STYLE_ATTR = XmlBuffer.ascii(" s=\"");
    private static final byte[] VALUE_OPEN = XmlBuffer.ascii("><v>");
    private static final byte[] VALUE_CLOSE = XmlBuffer.ascii("</v></c>");
    private static final byte[] ERROR_NUM = XmlBuffer.ascii(" t=\"e\"><v>#NUM!</v></c>");
    private static final byte[] BOOL_TRUE = XmlBuffer.ascii(" t=\"b\"><v>1</v></c>");
    private static final byte[] BOOL_FALSE = XmlBuffer.ascii(" t=\"b\"><v>0</v></c>");
    private static final byte[] SHARED_OPEN = XmlBuffer.ascii(" t=\"s\"><v>");
    private static final byte[] INLINE_OPEN = XmlBuffer.ascii(" t=\"inlineStr\"><is>");
    private static final byte[] INLINE_CLOSE = XmlBuffer.ascii("</is></c>");
    private static final byte[] FORMULA_OPEN = XmlBuffer.ascii("><f>");
    private static final byte[] FORMULA_CLOSE = XmlBuffer.ascii("</f></c>");
    private static final byte[] BLANK_CLOSE = XmlBuffer.ascii("/>");

    private final ZipStream zip;
    private final XmlBuffer xml;
    private final StyleRegistry styles = new StyleRegistry();
    private final SharedStrings sharedStrings;
    private final List<SheetInfo> sheets = new ArrayList<>();
    private final List<int[]> merges = new ArrayList<>();
    private final int flushThreshold;
    private final int xmlBufferSize;
    private final boolean cellReferences;
    private final boolean date1904;

    private boolean sheetOpen;
    private boolean rowOpen;
    private boolean usesFormulas;
    private int row;
    private int column;
    private int lastColumn;

    public WorkbookEngine(XlsxOptions options) {
        flushThreshold = options.flushThreshold();
        xmlBufferSize = options.xmlBufferSize();
        cellReferences = options.cellReferences();
        date1904 = options.date1904();
        zip = new ZipStream(options.compressionLevel(), flushThreshold + flushThreshold / 2);
        xml = new XmlBuffer(xmlBufferSize + 4096);
        sharedStrings = options.stringStorage() == StringStorage.SHARED ? new SharedStrings() : null;
    }

    // ------------------------------------------------------------------ output

    /** Whether enough compressed bytes are buffered to be worth a write to the destination. */
    public boolean outputReady() {
        return zip.outputLength() >= flushThreshold;
    }

    public void drainTo(OutputStream destination) throws IOException {
        int length = zip.outputLength();
        if (length > 0) {
            zip.clearOutput();
            destination.write(zip.output(), 0, length);
        }
    }

    /** Releases the native compressor. */
    public void release() {
        zip.end();
    }

    private void compressXml() {
        if (!xml.isEmpty()) {
            zip.write(xml.array(), 0, xml.length());
            xml.clear();
        }
    }

    private void compressIfFull() {
        if (xml.length() >= xmlBufferSize) {
            compressXml();
        }
    }

    private void finishPart() {
        compressXml();
        zip.finishEntry();
    }

    // ------------------------------------------------------------------ styles

    public int addStyle(Style style) {
        return styles.add(style);
    }

    private void checkStyle(int style) {
        if (style < 0 || style >= styles.size()) {
            throw new XlsxException(Kind.INVALID_STYLE, "style id " + style + " does not belong to this writer");
        }
    }

    // ------------------------------------------------------------------ worksheets

    public int sheetCount() {
        return sheets.size();
    }

    public void startSheet(String name, SheetOptions options) {
        validateSheetName(name);
        String lower = name.toLowerCase(Locale.ROOT);
        for (SheetInfo sheet : sheets) {
            if (sheet.name.toLowerCase(Locale.ROOT).equals(lower)) {
                throw new XlsxException(Kind.DUPLICATE_SHEET_NAME, "a worksheet named \"" + name + "\" already exists");
            }
        }
        for (Column definition : options.columns().values()) {
            checkStyle(definition.style().index());
        }

        completeSheet();
        int index = sheets.size();
        sheets.add(new SheetInfo(name, options.hidden()));
        zip.startEntry("xl/worksheets/sheet" + (index + 1) + ".xml");
        sheetOpen = true;
        row = 0;
        column = 0;
        lastColumn = 0;
        rowOpen = false;
        merges.clear();
        writeSheetHeader(index, options);
    }

    private void writeSheetHeader(int index, SheetOptions o) {
        XmlBuffer x = xml;
        x.raw(WorkbookParts.DECLARATION).raw("<worksheet xmlns=\"").raw(WorkbookParts.NS_MAIN);
        x.raw("\" xmlns:r=\"").raw(WorkbookParts.NS_REL).raw("\">");
        if (o.tabColor() != null) {
            x.raw("<sheetPr><tabColor rgb=\"").putArgb(o.tabColor().argb()).raw("\"/></sheetPr>");
        }
        x.raw("<sheetViews><sheetView workbookViewId=\"0\"");
        if (index == 0) {
            x.raw(" tabSelected=\"1\"");
        }
        if (!o.showGridLines()) {
            x.raw(" showGridLines=\"0\"");
        }
        int frozenRows = o.frozenRows();
        int frozenColumns = o.frozenColumns();
        if (frozenRows > 0 || frozenColumns > 0) {
            String pane = frozenRows > 0 ? (frozenColumns > 0 ? "bottomRight" : "bottomLeft") : "topRight";
            x.raw("><pane");
            if (frozenColumns > 0) {
                x.raw(" xSplit=\"").putLong(frozenColumns).raw('"');
            }
            if (frozenRows > 0) {
                x.raw(" ySplit=\"").putLong(frozenRows).raw('"');
            }
            x.raw(" topLeftCell=\"").putCellReference(frozenRows + 1, frozenColumns + 1);
            x.raw("\" activePane=\"").raw(pane).raw("\" state=\"frozen\"/><selection pane=\"").raw(pane);
            x.raw("\"/></sheetView></sheetViews>");
        } else {
            x.raw("/></sheetViews>");
        }
        x.raw("<sheetFormatPr defaultRowHeight=\"15\"/>");
        if (!o.columns().isEmpty()) {
            x.raw("<cols>");
            for (Map.Entry<Integer, Column> entry : o.columns().entrySet()) {
                Column definition = entry.getValue();
                x.raw("<col min=\"").putLong(entry.getKey()).raw("\" max=\"").putLong(entry.getKey());
                x.raw("\" width=\"").putDouble(definition.hasWidth() ? definition.width() : 9.140625).raw('"');
                if (definition.hasWidth()) {
                    x.raw(" customWidth=\"1\"");
                }
                if (definition.style().index() != 0) {
                    x.raw(" style=\"").putLong(definition.style().index()).raw('"');
                }
                if (definition.hidden()) {
                    x.raw(" hidden=\"1\"");
                }
                x.raw("/>");
            }
            x.raw("</cols>");
        }
        x.raw("<sheetData>");
    }

    public void completeSheet() {
        if (!sheetOpen) {
            return;
        }
        if (rowOpen) {
            closeRow();
        }
        XmlBuffer x = xml;
        x.raw("</sheetData>");
        int[] filter = sheets.get(sheets.size() - 1).autoFilter;
        if (filter != null) {
            x.raw("<autoFilter ref=\"").putRange(filter[0], filter[1], filter[2], filter[3]).raw("\"/>");
        }
        if (!merges.isEmpty()) {
            x.raw("<mergeCells count=\"").putLong(merges.size()).raw("\">");
            for (int[] range : merges) {
                x.raw("<mergeCell ref=\"").putRange(range[0], range[1], range[2], range[3]).raw("\"/>");
                compressIfFull();
            }
            x.raw("</mergeCells>");
        }
        x.raw("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>");
        finishPart();
        sheetOpen = false;
    }

    /** Completes the workbook: open worksheet, shared strings, styles, workbook, relationships, central directory. */
    public void finish() {
        if (sheets.isEmpty()) {
            startSheet("Sheet1", SheetOptions.DEFAULT);
        }
        boolean anyVisible = false;
        for (SheetInfo sheet : sheets) {
            anyVisible |= !sheet.hidden;
        }
        if (!anyVisible) {
            throw new XlsxException(Kind.NO_VISIBLE_WORKSHEET, "a workbook needs at least one visible worksheet");
        }
        completeSheet();

        boolean shared = sharedStrings != null && sharedStrings.size() > 0;
        if (shared) {
            writeSharedStrings();
        }
        zip.startEntry("xl/styles.xml");
        styles.writeXml(xml);
        finishPart();
        zip.startEntry("xl/workbook.xml");
        WorkbookParts.workbook(xml, sheets, usesFormulas, date1904);
        finishPart();
        zip.startEntry("xl/_rels/workbook.xml.rels");
        WorkbookParts.workbookRels(xml, sheets.size(), shared);
        finishPart();
        zip.startEntry("[Content_Types].xml");
        WorkbookParts.contentTypes(xml, sheets.size(), shared);
        finishPart();
        zip.startEntry("_rels/.rels");
        WorkbookParts.rootRels(xml);
        finishPart();
        zip.finish();
    }

    private void writeSharedStrings() {
        zip.startEntry("xl/sharedStrings.xml");
        XmlBuffer x = xml;
        x.raw(WorkbookParts.DECLARATION).raw("<sst xmlns=\"").raw(WorkbookParts.NS_MAIN);
        x.raw("\" count=\"").putLong(sharedStrings.references());
        x.raw("\" uniqueCount=\"").putLong(sharedStrings.size()).raw("\">");
        for (int i = 0; i < sharedStrings.size(); i++) {
            x.raw("<si>").putTextElement(sharedStrings.get(i)).raw("</si>");
            compressIfFull();
        }
        x.raw("</sst>");
        finishPart();
    }

    // ------------------------------------------------------------------ rows

    /** Current (or last written) 1-based row number; 0 before the first row. */
    public int row() {
        return row;
    }

    /** 1-based column of the last written cell in the current row; 0 at the start of a row. */
    public int column() {
        return column;
    }

    public void beginRowAt(int rowNumber, double height, int style) {
        if (rowNumber < 1 || rowNumber > MAX_ROWS) {
            throw rowOutOfRange(rowNumber);
        }
        if (rowNumber <= row) {
            throw new XlsxException(Kind.ROW_ORDER, "rows must ascend: row " + rowNumber + " follows row " + row);
        }
        checkStyle(style);
        if (!Double.isNaN(height) && !(height >= 0 && height <= 409)) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "row height must be within 0..409 points");
        }
        if (rowOpen) {
            closeRow();
        }
        openRow(rowNumber, height, style);
    }

    public void beginNextRow(double height, int style) {
        beginRowAt(row + 1, height, style);
    }

    private void openRow(int rowNumber, double height, int style) {
        row = rowNumber;
        column = 0;
        lastColumn = 0;
        rowOpen = true;
        XmlBuffer x = xml;
        x.raw(ROW_OPEN).putLong(rowNumber).raw('"');
        if (!Double.isNaN(height)) {
            x.raw(" ht=\"").putDouble(height).raw("\" customHeight=\"1\"");
        }
        if (style != 0) {
            x.raw(" s=\"").putLong(style).raw("\" customFormat=\"1\"");
        }
        x.raw('>');
    }

    private void openNextRow() {
        if (row >= MAX_ROWS) {
            throw rowOutOfRange((long) row + 1);
        }
        openRow(row + 1, Double.NaN, 0);
    }

    private void closeRow() {
        xml.raw(ROW_CLOSE);
        rowOpen = false;
        compressIfFull();
    }

    public void closeOpenRow() {
        if (rowOpen) {
            closeRow();
        }
    }

    /** Ends the current row; with no open row, leaves an empty row. */
    public void endRow() {
        if (rowOpen) {
            closeRow();
        } else if (row >= MAX_ROWS) {
            throw rowOutOfRange((long) row + 1);
        } else {
            row++;
        }
    }

    public void skipRows(int count) {
        if (count < 0) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "count must not be negative");
        }
        if (rowOpen) {
            closeRow();
        }
        if ((long) row + count > MAX_ROWS) {
            throw rowOutOfRange((long) row + count);
        }
        row += count;
    }

    public void skipCells(int count) {
        if (count < 0) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "count must not be negative");
        }
        if ((long) (rowOpen ? column : 0) + count > MAX_COLUMNS) {
            throw tooManyColumns();
        }
        if (!rowOpen) {
            openNextRow();
        }
        column += count;
    }

    public void merge(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        validateRange(firstRow, firstColumn, lastRow, lastColumn);
        if (firstRow == lastRow && firstColumn == lastColumn) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "a merged range must span more than one cell");
        }
        merges.add(new int[] {firstRow, firstColumn, lastRow, lastColumn});
    }

    public void autoFilter(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        validateRange(firstRow, firstColumn, lastRow, lastColumn);
        sheets.get(sheets.size() - 1).autoFilter = new int[] {firstRow, firstColumn, lastRow, lastColumn};
    }

    // ------------------------------------------------------------------ cells

    /**
     * Opens a {@code <c} element with reference and style attributes as needed. Validation happens
     * before any byte is written, so a rejected cell leaves the worksheet well-formed.
     */
    private XmlBuffer beginCell(int style) {
        if (style != 0) {
            checkStyle(style);
        }
        if (!rowOpen) {
            openNextRow();
        }
        int next = column + 1;
        if (next > MAX_COLUMNS) {
            throw tooManyColumns();
        }
        column = next;

        XmlBuffer x = xml;
        x.ensure(CELL_RESERVE);
        if (next == lastColumn + 1 && !cellReferences) {
            // References are optional; readers infer consecutive cells. Saves about 20% of the XML.
            x.raw(CELL);
        } else {
            x.raw(CELL_REF).putColumnName(next).putLong(row).raw('"');
        }
        lastColumn = next;
        if (style != 0) {
            x.raw(STYLE_ATTR).putLong(style).raw('"');
        }
        return x;
    }

    /** Writes an integer cell. Excel stores numbers as doubles: beyond 2^53 precision is lost. */
    public void writeLong(long value, int style) {
        beginCell(style).raw(VALUE_OPEN).putLong(value).raw(VALUE_CLOSE);
    }

    /** Writes a number cell; NaN and infinities become the {@code #NUM!} error. */
    public void writeDouble(double value, int style) {
        XmlBuffer x = beginCell(style);
        if (Double.isFinite(value)) {
            x.raw(VALUE_OPEN).putDouble(value).raw(VALUE_CLOSE);
        } else {
            x.raw(ERROR_NUM);
        }
    }

    public void writeBoolean(boolean value, int style) {
        beginCell(style).raw(value ? BOOL_TRUE : BOOL_FALSE);
    }

    public void writeString(String value, int style) {
        int length = value.length();
        if (length > MAX_TEXT_LENGTH) {
            throw new XlsxException(Kind.TEXT_TOO_LONG, "cell at row " + (rowOpen ? row : row + 1) + ", column "
                    + ((rowOpen ? column : 0) + 1) + " has " + length + " characters; the limit is " + MAX_TEXT_LENGTH);
        }
        XmlBuffer x = beginCell(style);
        if (sharedStrings != null) {
            x.raw(SHARED_OPEN).putLong(sharedStrings.indexOf(value)).raw(VALUE_CLOSE);
        } else {
            x.raw(INLINE_OPEN).putTextElement(value).raw(INLINE_CLOSE);
            if (length > LONG_TEXT) {
                compressIfFull();
            }
        }
    }

    /** Writes a formula (leading {@code =} optional). Excel computes it when the file is opened. */
    public void writeFormula(String formula, int style) {
        String body = formula.startsWith("=") ? formula.substring(1) : formula;
        if (body.isEmpty()) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "formula must not be empty");
        }
        XmlBuffer x = beginCell(style);
        usesFormulas = true;
        x.raw(FORMULA_OPEN).putText(body).raw(FORMULA_CLOSE);
        compressIfFull();
    }

    /** Writes an empty cell carrying a format, or skips the cell for the default style. */
    public void writeBlank(int style) {
        if (style == 0) {
            skipCells(1);
        } else {
            beginCell(style).raw(BLANK_CLOSE);
        }
    }

    /**
     * Converts a day count since 1970-01-01 to this workbook's date serial, or returns -1 when
     * the date system cannot represent the day.
     */
    public long serialDay(long epochDay) {
        if (date1904) {
            long day = epochDay - EPOCH_DAY_1904;
            return day >= 0 && day <= MAX_SERIAL - SERIAL_OFFSET_1904 ? day : -1;
        }
        long day = epochDay - EPOCH_DAY_1900;
        if (day < 61) {
            // Excel's 1900 system counts a 1900-02-29 that never existed.
            day--;
        }
        return day >= 1 && day <= MAX_SERIAL ? day : -1;
    }

    /** Writes a date serial from a value of {@link #serialDay} and the nanoseconds of the day. */
    public void writeSerial(long serialDay, long nanosOfDay, ValueKind kind, int style) {
        checkStyle(style);
        beginCell(styles.valueVariant(style, kind)).raw(VALUE_OPEN).putSerial(false, serialDay, nanosOfDay).raw(VALUE_CLOSE);
    }

    /** Writes a time of day or a duration as a signed number of days. */
    public void writeDays(boolean negative, long days, long nanosOfDay, ValueKind kind, int style) {
        checkStyle(style);
        beginCell(styles.valueVariant(style, kind)).raw(VALUE_OPEN).putSerial(negative, days, nanosOfDay).raw(VALUE_CLOSE);
    }

    // ------------------------------------------------------------------ validation

    private static void validateSheetName(String name) {
        boolean invalid = name.isEmpty()
                || name.length() > 31
                || name.startsWith("'")
                || name.endsWith("'")
                || name.equalsIgnoreCase("History");
        for (int i = 0; !invalid && i < name.length(); i++) {
            invalid = ":\\/?*[]".indexOf(name.charAt(i)) >= 0;
        }
        if (invalid) {
            throw new XlsxException(Kind.INVALID_SHEET_NAME, "invalid worksheet name \"" + name
                    + "\": names have 1..31 characters, none of : \\ / ? * [ ], and no leading or trailing apostrophe");
        }
    }

    private static void validateRange(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        boolean rowsValid = firstRow >= 1 && lastRow >= firstRow && lastRow <= MAX_ROWS;
        boolean columnsValid = firstColumn >= 1 && lastColumn >= firstColumn && lastColumn <= MAX_COLUMNS;
        if (!rowsValid || !columnsValid) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "invalid cell range");
        }
    }

    private static XlsxException rowOutOfRange(long rowNumber) {
        return new XlsxException(Kind.ROW_OUT_OF_RANGE, "row " + rowNumber + " is outside 1.." + MAX_ROWS);
    }

    private XlsxException tooManyColumns() {
        return new XlsxException(Kind.TOO_MANY_COLUMNS, "row " + Math.max(row, 1) + " exceeds " + MAX_COLUMNS + " columns");
    }
}
