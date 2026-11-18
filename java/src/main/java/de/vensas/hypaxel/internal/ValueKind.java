package de.vensas.hypaxel.internal;

/** Temporal value categories and the built-in number format each gets when the cell has none. */
public enum ValueKind {
    /** Short date in the viewer's locale. */
    DATE(14),
    /** Short date and time in the viewer's locale. */
    DATE_TIME(22),
    /** {@code h:mm:ss}. */
    TIME(21),
    /** {@code [h]:mm:ss}. */
    DURATION(46);

    private final int builtinFormat;

    ValueKind(int builtinFormat) {
        this.builtinFormat = builtinFormat;
    }

    int builtinFormat() {
        return builtinFormat;
    }
}
