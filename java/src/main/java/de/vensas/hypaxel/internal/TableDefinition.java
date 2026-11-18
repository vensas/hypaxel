package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.ExcelTable;
import de.vensas.hypaxel.XlsxException;
import de.vensas.hypaxel.XlsxException.Kind;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** A validated table, kept until its worksheet completes. */
final class TableDefinition {
    final int id;
    final String name;
    /** {firstRow, firstColumn, lastRow, lastColumn}. */
    final int[] range;
    private final ExcelTable table;
    private final String[] columns;

    private TableDefinition(ExcelTable table, int id, String name, String[] columns) {
        this.table = table;
        this.id = id;
        this.name = name;
        this.columns = columns;
        this.range = new int[] {table.firstRow(), table.firstColumn(), table.lastRow(), table.lastColumn()};
    }

    /** Validates {@code table}; {@code taken} reports whether a name is already used in the workbook. */
    static TableDefinition create(ExcelTable table, int id, Predicate<String> taken) {
        int firstRow = table.firstRow();
        int lastRow = table.lastRow();
        int width = table.lastColumn() - table.firstColumn() + 1;
        WorkbookEngine.validateRange(firstRow, table.firstColumn(), lastRow, table.lastColumn());
        if (table.headerRow() && firstRow == lastRow) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "a table with a header row needs at least one data row");
        }

        String name = table.name();
        if (name == null) {
            name = "Table" + id;
        } else {
            validateName(name);
        }
        if (taken.test(name)) {
            throw new XlsxException(Kind.DUPLICATE_TABLE_NAME,
                    "a table named \"" + name + "\" already exists; table names are unique in the workbook, ignoring case");
        }

        List<String> requested = table.columns();
        String[] columns;
        if (requested.isEmpty()) {
            if (table.headerRow()) {
                throw new XlsxException(Kind.INVALID_ARGUMENT, "a table with a header row needs its column names (ExcelTable.Builder.columns)");
            }
            columns = new String[width];
            for (int i = 0; i < width; i++) {
                columns[i] = "Column" + (i + 1);
            }
        } else {
            columns = requested.toArray(new String[0]);
        }
        if (columns.length != width) {
            throw new XlsxException(Kind.INVALID_ARGUMENT, "the number of table column names (" + columns.length
                    + ") must equal the width of the range (" + width + ")");
        }
        Set<String> seen = new HashSet<>();
        for (String column : columns) {
            if (column == null || column.isEmpty() || column.length() > 255) {
                throw new XlsxException(Kind.INVALID_ARGUMENT, "table column names must be 1..255 characters");
            }
            if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                throw new XlsxException(Kind.INVALID_ARGUMENT,
                        "table column names must be unique, ignoring case; \"" + column + "\" is repeated");
            }
        }
        return new TableDefinition(table, id, name, columns);
    }

    void writeXml(XmlBuffer x) {
        x.raw(WorkbookParts.DECLARATION).raw("<table xmlns=\"").raw(WorkbookParts.NS_MAIN);
        x.raw("\" id=\"").putLong(id).raw("\" name=\"").putText(name).raw("\" displayName=\"").putText(name);
        x.raw("\" ref=\"").putRange(range[0], range[1], range[2], range[3]).raw('"');
        if (!table.headerRow()) {
            x.raw(" headerRowCount=\"0\"");
        }
        x.raw(" totalsRowShown=\"0\">");
        if (table.autoFilter() && table.headerRow()) {
            x.raw("<autoFilter ref=\"").putRange(range[0], range[1], range[2], range[3]).raw("\"/>");
        }
        x.raw("<tableColumns count=\"").putLong(columns.length).raw("\">");
        for (int i = 0; i < columns.length; i++) {
            x.raw("<tableColumn id=\"").putLong(i + 1).raw("\" name=\"");
            putColumnName(x, columns[i]);
            x.raw("\"/>");
        }
        x.raw("</tableColumns><tableStyleInfo");
        if (table.style().name() != null) {
            x.raw(" name=\"").raw(table.style().name()).raw('"');
        }
        x.raw(table.highlightFirstColumn() ? " showFirstColumn=\"1\"" : " showFirstColumn=\"0\"");
        x.raw(table.highlightLastColumn() ? " showLastColumn=\"1\"" : " showLastColumn=\"0\"");
        x.raw(table.rowStripes() ? " showRowStripes=\"1\"" : " showRowStripes=\"0\"");
        x.raw(table.columnStripes() ? " showColumnStripes=\"1\"" : " showColumnStripes=\"0\"");
        x.raw("/></table>");
    }

    /**
     * Writes line breaks and tabs, which attribute normalization would turn into spaces, as
     * {@code _xHHHH_} escapes, as Excel does for table column names.
     */
    private static void putColumnName(XmlBuffer x, String name) {
        int start = 0;
        for (int i = 0; i < name.length(); i++) {
            String escape = switch (name.charAt(i)) {
                case '\n' -> "_x000A_";
                case '\r' -> "_x000D_";
                case '\t' -> "_x0009_";
                default -> null;
            };
            if (escape != null) {
                x.putText(name.substring(start, i)).raw(escape);
                start = i + 1;
            }
        }
        x.putText(name.substring(start));
    }

    /**
     * Table names follow Excel's defined-name rules: they start with a letter, {@code _} or
     * {@code \}, contain only letters, digits, {@code _}, {@code .} and {@code \}, are at most 255
     * characters and cannot look like a cell reference.
     */
    static void validateName(String name) {
        boolean valid = !name.isEmpty() && name.length() <= 255 && !looksLikeReference(name);
        for (int i = 0; valid && i < name.length(); ) {
            int c = name.codePointAt(i);
            valid = i == 0
                    ? Character.isLetter(c) || c == '_' || c == '\\'
                    : Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '\\';
            i += Character.charCount(c);
        }
        if (!valid) {
            throw new XlsxException(Kind.INVALID_TABLE_NAME, "invalid table name \"" + name
                    + "\": names start with a letter, _ or \\, contain only letters, digits, _ and ., and cannot look like a cell reference");
        }
    }

    /** A1-style ({@code XFD1048576}) and R1C1-style ({@code R}, {@code C}, {@code R2}, {@code RC3}, ...) references are not valid names. */
    static boolean looksLikeReference(String name) {
        char[] chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'a' && chars[i] <= 'z') {
                chars[i] -= 'a' - 'A';
            }
        }
        String upper = new String(chars);
        int length = upper.length();
        int letters = 0;
        while (letters < length && upper.charAt(letters) >= 'A' && upper.charAt(letters) <= 'Z') {
            letters++;
        }
        if (letters >= 1 && letters <= 3 && letters < length && allDigits(upper, letters)) {
            int column = 0;
            for (int i = 0; i < letters; i++) {
                column = column * 26 + upper.charAt(i) - 'A' + 1;
            }
            String digits = upper.substring(letters);
            long row = digits.length() > 9 ? Long.MAX_VALUE : Long.parseLong(digits);
            if (column <= WorkbookEngine.MAX_COLUMNS && row >= 1 && row <= WorkbookEngine.MAX_ROWS) {
                return true;
            }
        }

        int i = 0;
        if (i < length && upper.charAt(i) == 'R') {
            i++;
            while (i < length && isDigit(upper.charAt(i))) {
                i++;
            }
        }
        if (i < length && upper.charAt(i) == 'C') {
            i++;
            while (i < length && isDigit(upper.charAt(i))) {
                i++;
            }
        }
        return i > 0 && i == length;
    }

    private static boolean allDigits(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (!isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** True when two inclusive ranges share at least one cell. */
    static boolean overlaps(int[] a, int[] b) {
        return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }
}
