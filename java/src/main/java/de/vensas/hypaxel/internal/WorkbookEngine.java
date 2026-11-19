package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.AutoFit;
import de.vensas.hypaxel.Column;
import de.vensas.hypaxel.ExcelTable;
import de.vensas.hypaxel.SheetOptions;
import de.vensas.hypaxel.StringStorage;
import de.vensas.hypaxel.Style;
import de.vensas.hypaxel.XlsxException;
import de.vensas.hypaxel.XlsxException.Kind;
import de.vensas.hypaxel.XlsxOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private final Set<String> tableNames = new HashSet<>();
    private List<TableDefinition> tables = new ArrayList<>();
    private int tableCount;
    private final int flushThreshold;
    private final int xmlBufferSize;
    private final boolean cellReferences;
    private final boolean date1904;

    private boolean sheetOpen;
    private boolean rowOpen;
    /**
     * The open row was started explicitly; until it has a cell, the next whole-row write fills it
     * instead of starting another row.
     */
    private boolean rowExplicit;
    private int rowOutlineLevels;
    /** Present while the current worksheet's auto-fit sample is held back. */
    private ColumnFit fit;
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
        if (xml.length() >= xmlBufferSize && fit == null) {
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
        rowExplicit = false;
        rowOutlineLevels = options.rowOutlineLevels();
        merges.clear();
        writeSheetHeader(index, options);
    }

    private void writeSheetHeader(int index, SheetOptions o) {
        XmlBuffer x = xml;
        x.raw(WorkbookParts.DECLARATION).raw("<worksheet xmlns=\"").raw(WorkbookParts.NS_MAIN);
        x.raw("\" xmlns:r=\"").raw(WorkbookParts.NS_REL).raw("\">");
        if (o.tabColor() != null || o.outlineSummaryAbove()) {
            x.raw("<sheetPr>");
            if (o.tabColor() != null) {
                x.raw("<tabColor rgb=\"").putArgb(o.tabColor().argb()).raw("\"/>");
            }
            if (o.outlineSummaryAbove()) {
                x.raw("<outlinePr summaryBelow=\"0\"/>");
            }
            x.raw("</sheetPr>");
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
        x.raw("<sheetFormatPr defaultRowHeight=\"15\"");
        if (o.rowOutlineLevels() > 0) {
            x.raw(" outlineLevelRow=\"").putLong(o.rowOutlineLevels()).raw('"');
        }
        x.raw("/>");
        AutoFit autoFit = o.autoFit();
        if (autoFit != null) {
            // <cols> is inserted here once the sample has been measured.
            fit = new ColumnFit(styles, autoFit, o.columns(), x.length());
        } else if (!o.columns().isEmpty()) {
            x.raw("<cols>");
            for (Map.Entry<Integer, Column> entry : o.columns().entrySet()) {
                ColumnFit.writeColumn(x, entry.getKey(), entry.getValue(), entry.getValue().width());
            }
            x.raw("</cols>");
        }
        x.raw("<sheetData>");
    }

    /** Inserts the fitted {@code <cols>} into the held-back XML and resumes streaming. */
    private void endFit() {
        ColumnFit sample = fit;
        fit = null;
        XmlBuffer cols = new XmlBuffer(1024);
        sample.writeColumns(cols);
        xml.insert(sample.colsAt(), cols);
        compressIfFull();
        xml.shrink(xmlBufferSize + 4096);
    }

    public void completeSheet() {
        if (!sheetOpen) {
            return;
        }
        if (rowOpen) {
            closeRow();
        }
        if (fit != null) {
            endFit();
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
        x.raw("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>");
        if (!tables.isEmpty()) {
            x.raw("<tableParts count=\"").putLong(tables.size()).raw("\">");
            for (int i = 1; i <= tables.size(); i++) {
                x.raw("<tablePart r:id=\"rId").putLong(i).raw("\"/>");
            }
            x.raw("</tableParts>");
        }
        x.raw("</worksheet>");
        finishPart();
        sheetOpen = false;

        if (!tables.isEmpty()) {
            for (TableDefinition table : tables) {
                zip.startEntry("xl/tables/table" + table.id + ".xml");
                table.writeXml(xml);
                finishPart();
            }
            zip.startEntry("xl/worksheets/_rels/sheet" + sheets.size() + ".xml.rels");
            WorkbookParts.sheetRels(xml, tables);
            finishPart();
            tables = new ArrayList<>();
        }
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
        WorkbookParts.contentTypes(xml, sheets.size(), tableCount, shared);
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

    /**
     * Starts a row explicitly; a NaN height is the default height. Until it has a cell, the next
     * whole-row write ({@link #beginNextRow()}) fills it.
     */
    public void beginRowAt(int rowNumber, double height, int style, int outlineLevel, boolean hidden, boolean collapsed) {
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
        if (outlineLevel < 0 || outlineLevel > 7) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "row outline level must be within 0..7");
        }
        if (outlineLevel > rowOutlineLevels) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "row outline level " + outlineLevel
                    + " exceeds the worksheet's declared depth of " + rowOutlineLevels
                    + "; declare the deepest level with SheetOptions.Builder.rowOutlineLevels when adding the worksheet");
        }
        if (rowOpen) {
            closeRow();
        }
        openRow(rowNumber);
        XmlBuffer x = xml;
        if (!Double.isNaN(height)) {
            x.raw(" ht=\"").putDouble(height).raw("\" customHeight=\"1\"");
        }
        if (style != 0) {
            x.raw(" s=\"").putLong(style).raw("\" customFormat=\"1\"");
        }
        if (hidden) {
            x.raw(" hidden=\"1\"");
        }
        if (outlineLevel > 0) {
            x.raw(" outlineLevel=\"").putLong(outlineLevel).raw('"');
        }
        if (collapsed) {
            x.raw(" collapsed=\"1\"");
        }
        x.raw('>');
        rowExplicit = true;
    }

    /**
     * Starts the row of a whole-row write: an explicitly started row that has no cells yet,
     * otherwise the next row.
     */
    public void beginNextRow() {
        if (rowOpen) {
            if (rowExplicit && column == 0) {
                return;
            }
            closeRow();
        }
        openNextRow();
    }

    /** The 1-based row that the next whole-row write lands in. */
    public int nextRow() {
        return rowOpen && rowExplicit && column == 0 ? row : row + 1;
    }

    /** Writes {@code <row r="N"} and leaves the element open for attributes. */
    private void openRow(int rowNumber) {
        row = rowNumber;
        column = 0;
        lastColumn = 0;
        rowOpen = true;
        xml.raw(ROW_OPEN).putLong(rowNumber).raw('"');
    }

    private void openNextRow() {
        if (row >= MAX_ROWS) {
            throw rowOutOfRange((long) row + 1);
        }
        openRow(row + 1);
        xml.raw('>');
    }

    private void closeRow() {
        xml.raw(ROW_CLOSE);
        rowOpen = false;
        rowExplicit = false;
        if (fit != null && fit.rowClosed(xml.length())) {
            endFit();
        }
        compressIfFull();
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
        int[] range = {firstRow, firstColumn, lastRow, lastColumn};
        if (overlapsTable(range)) {
            throw new XlsxException(Kind.OVERLAPPING_RANGE, "merged cells cannot overlap a table");
        }
        merges.add(range);
    }

    public void autoFilter(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        validateRange(firstRow, firstColumn, lastRow, lastColumn);
        int[] range = {firstRow, firstColumn, lastRow, lastColumn};
        if (overlapsTable(range)) {
            throw new XlsxException(Kind.OVERLAPPING_RANGE,
                    "a worksheet auto filter cannot overlap a table (tables have their own filter)");
        }
        sheets.get(sheets.size() - 1).autoFilter = range;
    }

    /** Records a table of the current worksheet; it is written when the worksheet completes. */
    public void addTable(ExcelTable table) {
        TableDefinition definition = TableDefinition.create(table, tableCount + 1,
                name -> tableNames.contains(name.toLowerCase(Locale.ROOT)));
        int[] range = definition.range;
        if (overlapsTable(range)) {
            throw new XlsxException(Kind.OVERLAPPING_RANGE, "tables cannot overlap each other");
        }
        for (int[] merge : merges) {
            if (TableDefinition.overlaps(merge, range)) {
                throw new XlsxException(Kind.OVERLAPPING_RANGE, "a table cannot overlap merged cells");
            }
        }
        int[] filter = sheets.get(sheets.size() - 1).autoFilter;
        if (filter != null && TableDefinition.overlaps(filter, range)) {
            throw new XlsxException(Kind.OVERLAPPING_RANGE,
                    "a table cannot overlap the worksheet auto filter (tables have their own filter)");
        }
        tableCount++;
        tableNames.add(definition.name.toLowerCase(Locale.ROOT));
        tables.add(definition);
    }

    private boolean overlapsTable(int[] range) {
        for (TableDefinition table : tables) {
            if (TableDefinition.overlaps(table.range, range)) {
                return true;
            }
        }
        return false;
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
        XmlBuffer x = beginCell(style);
        if (fit != null) {
            fit.number(column, style, value);
        }
        x.raw(VALUE_OPEN).putLong(value).raw(VALUE_CLOSE);
    }

    /** Writes a number cell; NaN and infinities become the {@code #NUM!} error. */
    public void writeDouble(double value, int style) {
        XmlBuffer x = beginCell(style);
        if (fit != null) {
            fit.number(column, style, value);
        }
        if (Double.isFinite(value)) {
            x.raw(VALUE_OPEN).putDouble(value).raw(VALUE_CLOSE);
        } else {
            x.raw(ERROR_NUM);
        }
    }

    public void writeBoolean(boolean value, int style) {
        XmlBuffer x = beginCell(style);
        if (fit != null) {
            fit.text(column, style, value ? "TRUE" : "FALSE");
        }
        x.raw(value ? BOOL_TRUE : BOOL_FALSE);
    }

    public void writeString(String value, int style) {
        int length = value.length();
        if (length > MAX_TEXT_LENGTH) {
            throw new XlsxException(Kind.TEXT_TOO_LONG, "cell at row " + (rowOpen ? row : row + 1) + ", column "
                    + ((rowOpen ? column : 0) + 1) + " has " + length + " characters; the limit is " + MAX_TEXT_LENGTH);
        }
        XmlBuffer x = beginCell(style);
        if (fit != null) {
            fit.text(column, style, value);
        }
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
        int variant = styles.valueVariant(style, kind);
        XmlBuffer x = beginCell(variant);
        if (fit != null) {
            fit.number(column, variant, serialDay);
        }
        x.raw(VALUE_OPEN).putSerial(false, serialDay, nanosOfDay).raw(VALUE_CLOSE);
    }

    /** Writes a time of day or a duration as a signed number of days. */
    public void writeDays(boolean negative, long days, long nanosOfDay, ValueKind kind, int style) {
        checkStyle(style);
        int variant = styles.valueVariant(style, kind);
        XmlBuffer x = beginCell(variant);
        if (fit != null) {
            fit.number(column, variant, negative ? -days : days);
        }
        x.raw(VALUE_OPEN).putSerial(negative, days, nanosOfDay).raw(VALUE_CLOSE);
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

    static void validateRange(int firstRow, int firstColumn, int lastRow, int lastColumn) {
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
