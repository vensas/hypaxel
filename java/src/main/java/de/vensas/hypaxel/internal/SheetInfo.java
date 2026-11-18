package de.vensas.hypaxel.internal;

/** What the workbook part needs to know about a worksheet. */
final class SheetInfo {
    final String name;
    final boolean hidden;
    /** Auto filter range as {firstRow, firstColumn, lastRow, lastColumn}, or null. */
    int[] autoFilter;

    SheetInfo(String name, boolean hidden) {
        this.name = name;
        this.hidden = hidden;
    }
}
