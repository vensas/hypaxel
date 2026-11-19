package de.vensas.hypaxel;

/** What a sheet contains. */
public enum SheetKind {
    /** A grid of cells. */
    WORKSHEET,
    /** A sheet that only holds a chart; it has no rows. */
    CHARTSHEET,
    /** A dialog sheet or Excel 4 macro sheet. */
    OTHER
}
