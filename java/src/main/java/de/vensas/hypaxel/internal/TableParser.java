package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.ExcelTable;
import de.vensas.hypaxel.TableStyle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads a table part ({@code xl/tables/tableN.xml}) into the {@link ExcelTable} the writer accepts. */
final class TableParser {
    private static final int MAX_ROWS = WorkbookEngine.MAX_ROWS;
    private static final int MAX_COLUMNS = WorkbookEngine.MAX_COLUMNS;

    private static final byte[] TABLE = Ascii.bytes("table");
    private static final byte[] REF = Ascii.bytes("ref");
    private static final byte[] DISPLAY_NAME = Ascii.bytes("displayName");
    private static final byte[] NAME = Ascii.bytes("name");
    private static final byte[] HEADER_ROW_COUNT = Ascii.bytes("headerRowCount");
    private static final byte[] TOTALS_ROW_COUNT = Ascii.bytes("totalsRowCount");
    private static final byte[] AUTO_FILTER = Ascii.bytes("autoFilter");
    private static final byte[] TABLE_COLUMNS = Ascii.bytes("tableColumns");
    private static final byte[] TABLE_COLUMN = Ascii.bytes("tableColumn");
    private static final byte[] TABLE_STYLE_INFO = Ascii.bytes("tableStyleInfo");
    private static final byte[] SHOW_ROW_STRIPES = Ascii.bytes("showRowStripes");
    private static final byte[] SHOW_COLUMN_STRIPES = Ascii.bytes("showColumnStripes");
    private static final byte[] SHOW_FIRST_COLUMN = Ascii.bytes("showFirstColumn");
    private static final byte[] SHOW_LAST_COLUMN = Ascii.bytes("showLastColumn");

    private TableParser() {
    }

    static ExcelTable parse(XmlReader xml) throws IOException {
        int[] range = null;
        String name = null;
        boolean headerRow = true;
        boolean totalsRow = false;
        boolean autoFilter = false;
        List<String> columns = new ArrayList<>();
        TableStyle style = TableStyle.NONE;
        boolean rowStripes = false;
        boolean columnStripes = false;
        boolean firstColumn = false;
        boolean lastColumn = false;
        while (true) {
            int event = xml.next();
            if (event == XmlReader.EOF) {
                break;
            }
            if (event != XmlReader.START && event != XmlReader.EMPTY) {
                continue;
            }
            boolean descend = false;
            if (range == null) {
                if (!xml.nameIs(TABLE)) {
                    throw xml.malformed("the part is not a table");
                }
                String ref = xml.attribute(REF);
                range = ref == null ? null : parseRange(ref.strip());
                if (range == null) {
                    throw xml.malformed("the table has no valid cell range");
                }
                name = xml.attribute(DISPLAY_NAME);
                if (name == null) {
                    name = xml.attribute(NAME);
                }
                String headerRows = xml.attribute(HEADER_ROW_COUNT);
                headerRow = headerRows == null || !headerRows.strip().equals("0");
                totalsRow = Ascii.parseUnsigned(xml.attribute(TOTALS_ROW_COUNT)) > 0;
                descend = true;
            } else if (xml.nameIs(AUTO_FILTER)) {
                autoFilter = true;
            } else if (xml.nameIs(TABLE_COLUMNS)) {
                descend = true;
            } else if (xml.nameIs(TABLE_COLUMN)) {
                if (columns.size() >= MAX_COLUMNS) {
                    throw xml.malformed("the table has more than 16384 columns");
                }
                String column = xml.attribute(NAME, true);
                columns.add(column == null ? "" : column);
            } else if (xml.nameIs(TABLE_STYLE_INFO)) {
                style = parseStyle(xml.attribute(NAME));
                rowStripes = isTrue(xml.attribute(SHOW_ROW_STRIPES));
                columnStripes = isTrue(xml.attribute(SHOW_COLUMN_STRIPES));
                firstColumn = isTrue(xml.attribute(SHOW_FIRST_COLUMN));
                lastColumn = isTrue(xml.attribute(SHOW_LAST_COLUMN));
            }
            if (event == XmlReader.START && !descend) {
                xml.skipElement();
            }
        }
        if (range == null) {
            throw xml.malformed("the part is not a table");
        }
        ExcelTable.Builder builder = ExcelTable.builder(range[0], range[1], range[2], range[3])
                .columns(columns)
                .style(style)
                .headerRow(headerRow)
                .autoFilter(autoFilter)
                .rowStripes(rowStripes)
                .columnStripes(columnStripes)
                .highlightFirstColumn(firstColumn)
                .highlightLastColumn(lastColumn)
                .totalsRow(totalsRow);
        if (name != null) {
            builder.name(name);
        }
        return builder.build();
    }

    private static boolean isTrue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.strip();
        return trimmed.equals("1") || trimmed.equals("true");
    }

    /** Maps a built-in style name such as {@code TableStyleMedium9}; custom styles have no counterpart. */
    static TableStyle parseStyle(String value) {
        if (value == null) {
            return TableStyle.NONE;
        }
        String name = value.strip();
        if (!name.startsWith("TableStyle")) {
            return TableStyle.NONE;
        }
        name = name.substring("TableStyle".length());
        int digits = 0;
        while (digits < name.length() && (name.charAt(digits) < '0' || name.charAt(digits) > '9')) {
            digits++;
        }
        int number = digits == name.length() || name.length() - digits > 3 ? -1 : Ascii.parseUnsigned(name.substring(digits));
        switch (name.substring(0, digits)) {
            case "Light":
                return number >= 1 && number <= 21 ? TableStyle.light(number) : TableStyle.NONE;
            case "Medium":
                return number >= 1 && number <= 28 ? TableStyle.medium(number) : TableStyle.NONE;
            case "Dark":
                return number >= 1 && number <= 11 ? TableStyle.dark(number) : TableStyle.NONE;
            default:
                return TableStyle.NONE;
        }
    }

    /**
     * Parses {@code A1:C10} (or a single cell) into first row, first column, last row and last
     * column; null if invalid.
     */
    static int[] parseRange(String ref) {
        int colon = ref.indexOf(':');
        long first = parseCell(colon < 0 ? ref : ref.substring(0, colon));
        long last = colon < 0 ? first : parseCell(ref.substring(colon + 1));
        if (first < 0 || last < 0) {
            return null;
        }
        int[] range = {(int) (first >>> 32), (int) first, (int) (last >>> 32), (int) last};
        return range[0] <= range[2] && range[1] <= range[3] ? range : null;
    }

    /** Returns the row in the high and the column in the low 32 bits; -1 if invalid. */
    private static long parseCell(String cell) {
        int column = 0;
        int row = 0;
        int letters = 0;
        int digits = 0;
        for (int i = 0; i < cell.length(); i++) {
            int c = cell.charAt(i);
            int lower = c | 0x20;
            if (c == '$') {
                continue;
            }
            if (lower >= 'a' && lower <= 'z' && c < 0x80 && digits == 0 && letters < 3) {
                letters++;
                column = column * 26 + (lower - 'a' + 1);
            } else if (c >= '0' && c <= '9' && letters > 0 && digits < 7) {
                digits++;
                row = row * 10 + (c - '0');
            } else {
                return -1;
            }
        }
        if (row < 1 || row > MAX_ROWS || column < 1 || column > MAX_COLUMNS) {
            return -1;
        }
        return (long) row << 32 | column;
    }
}
