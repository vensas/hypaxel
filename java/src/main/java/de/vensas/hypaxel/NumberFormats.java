package de.vensas.hypaxel;

/** Commonly used Excel number format codes. */
public final class NumberFormats {
    /** General. */
    public static final String GENERAL = "General";
    /** Integer. */
    public static final String INTEGER = "0";
    /** Two decimals. */
    public static final String DECIMAL2 = "0.00";
    /** Thousands separator. */
    public static final String THOUSANDS = "#,##0";
    /** Thousands separator and two decimals. */
    public static final String THOUSANDS2 = "#,##0.00";
    /** Percentage. */
    public static final String PERCENT = "0%";
    /** Percentage with two decimals. */
    public static final String PERCENT2 = "0.00%";
    /** Text. */
    public static final String TEXT = "@";
    /** ISO date. */
    public static final String DATE = "yyyy-mm-dd";
    /** ISO date and time. */
    public static final String DATETIME = "yyyy-mm-dd hh:mm:ss";
    /** Time of day. */
    public static final String TIME = "hh:mm:ss";
    /** Elapsed time that may exceed 24 hours. */
    public static final String DURATION = "[h]:mm:ss";

    private NumberFormats() {
    }
}
