package de.vensas.hypaxel;

/** How a cell's number format presents a serial number. */
public enum DateKind {
    /** A calendar date without a time of day, e.g. {@code yyyy-mm-dd}. */
    DATE,
    /** A date with a time of day, e.g. {@code yyyy-mm-dd hh:mm}. */
    DATE_TIME,
    /** A time of day, e.g. {@code hh:mm:ss}. */
    TIME,
    /** Elapsed time, e.g. {@code [h]:mm:ss}. */
    DURATION
}
