package de.vensas.hypaxel;

/** A1-style references from 1-based row and column numbers, e.g. for building formulas. */
public final class CellReference {
    private CellReference() {
    }

    /** Returns the column letters for a 1-based column index: 1 is {@code "A"}, 27 is {@code "AA"}. */
    public static String columnName(int column) {
        if (column < 1 || column > XlsxWriter.MAX_COLUMNS) {
            throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "column must be within 1..16384");
        }
        StringBuilder name = new StringBuilder(3);
        for (int c = column; c > 0; c = (c - 1) / 26) {
            name.append((char) ('A' + (c - 1) % 26));
        }
        return name.reverse().toString();
    }

    /** Returns the reference of a cell, e.g. {@code "B7"} for row 7, column 2. */
    public static String of(int row, int column) {
        if (row < 1 || row > XlsxWriter.MAX_ROWS) {
            throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "row must be within 1..1048576");
        }
        return columnName(column) + row;
    }

    /** Returns the reference of a range, e.g. {@code "A1:C9"}. */
    public static String range(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        return of(firstRow, firstColumn) + ":" + of(lastRow, lastColumn);
    }
}
