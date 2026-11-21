package de.vensas.hypaxel;

/**
 * A run of adjacent columns that share one width, as read from a worksheet by
 * {@link SheetReader#columnWidths()}.
 *
 * @param firstColumn the 1-based first column of the run
 * @param lastColumn the 1-based last column of the run
 * @param width the width in characters of the default font, as in {@link Column#width()}
 */
public record ColumnWidth(int firstColumn, int lastColumn, double width) {
}
