package de.vensas.hypaxel;

/**
 * Thrown when a workbook cannot be written as requested: a limit of the file format is exceeded,
 * an argument is invalid or the writer is used out of order. I/O failures of the destination are
 * reported as {@link java.io.IOException} instead.
 */
public final class XlsxException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The category of a failure. */
    public enum Kind {
        /** Worksheet names have 1 to 31 characters, none of {@code : \ / ? * [ ]}, and no leading or trailing apostrophe. */
        INVALID_SHEET_NAME,
        /** Worksheet names are unique, ignoring case. */
        DUPLICATE_SHEET_NAME,
        /** Rows are written in ascending order. */
        ROW_ORDER,
        /** Row numbers range from 1 to 1,048,576. */
        ROW_OUT_OF_RANGE,
        /** A row has at most 16,384 columns. */
        TOO_MANY_COLUMNS,
        /** A cell holds at most 32,767 characters. */
        TEXT_TOO_LONG,
        /** The style was not registered with this writer. */
        INVALID_STYLE,
        /** An argument is outside its allowed range. */
        INVALID_ARGUMENT,
        /** A workbook needs at least one visible worksheet. */
        NO_VISIBLE_WORKSHEET,
        /** A type cannot be mapped to columns. */
        UNSUPPORTED_TYPE,
        /** The writer or worksheet is already completed. */
        INVALID_STATE
    }

    private final Kind kind;

    /** Creates an exception of the given kind. */
    public XlsxException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /** Creates an exception of the given kind with a cause. */
    public XlsxException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /** Returns the category of the failure. */
    public Kind kind() {
        return kind;
    }
}
