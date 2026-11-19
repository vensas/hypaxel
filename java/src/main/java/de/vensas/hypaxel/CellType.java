package de.vensas.hypaxel;

/** The type of a cell value read from a workbook. */
public enum CellType {
    /** No value: a missing cell, or one that only carries formatting. */
    EMPTY,
    /** Text: a shared string, an inline string or the cached result of a text formula. */
    STRING,
    /** A number that is not formatted as a date or time. */
    NUMBER,
    /** {@code TRUE} or {@code FALSE}. */
    BOOLEAN,
    /** A number formatted as a date, time or duration; see {@link ExcelDateTime}. */
    DATE_TIME,
    /** An error value such as {@code #DIV/0!} or {@code #N/A}. */
    ERROR
}
