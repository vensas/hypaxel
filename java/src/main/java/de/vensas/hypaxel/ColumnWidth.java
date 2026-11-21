package de.vensas.hypaxel;

/**
 * A run of adjacent columns that share one width and visibility, as read from a worksheet by
 * {@link SheetReader#columnWidths()}.
 *
 * @param firstColumn the 1-based first column of the run
 * @param lastColumn the 1-based last column of the run
 * @param width the width in characters of the default font, as in {@link Column#width()}
 * @param hidden whether the columns are hidden
 */
public record ColumnWidth(int firstColumn, int lastColumn, double width, boolean hidden) {
}
