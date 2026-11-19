package de.vensas.hypaxel;

/** Whether a sheet is shown in Excel's tab bar. */
public enum SheetVisibility {
    /** Shown. */
    VISIBLE,
    /** Hidden; the user can unhide it. */
    HIDDEN,
    /** Hidden; only VBA can unhide it. */
    VERY_HIDDEN
}
