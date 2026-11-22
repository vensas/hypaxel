package de.vensas.hypaxel;

import java.util.OptionalDouble;

/**
 * A run of adjacent columns that share one width and visibility, as read from a worksheet by
 * {@link SheetReader#columnWidths()}. A run is reported when the file gives it a width, hides it,
 * or both.
 *
 * @param firstColumn the 1-based first column of the run
 * @param lastColumn the 1-based last column of the run
 * @param width the width in characters of the default font, as in {@link Column#width()}; empty
 *     when the file stores none, for example for hidden columns without a width
 * @param hidden whether the file sets the hidden flag
 */
public record ColumnWidth(int firstColumn, int lastColumn, OptionalDouble width, boolean hidden) {
    /** Returns false if the columns are hidden or have a width of 0; Excel shows neither. */
    public boolean visible() {
        return !hidden && (width.isEmpty() || width.getAsDouble() > 0);
    }
}
