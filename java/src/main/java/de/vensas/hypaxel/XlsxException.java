package de.vensas.hypaxel;

/**
 * Thrown when a workbook cannot be written or read as requested: a limit of the file format is
 * exceeded, an argument is invalid, the writer is used out of order, or the file being read is not
 * a workbook or is damaged. I/O failures of the destination or source are reported as
 * {@link java.io.IOException} instead.
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
        /**
         * Table names start with a letter, {@code _} or {@code \}, contain only letters, digits,
         * {@code _}, {@code .} and {@code \}, and do not look like a cell reference.
         */
        INVALID_TABLE_NAME,
        /** Table names are unique in the workbook, ignoring case. */
        DUPLICATE_TABLE_NAME,
        /** A range overlaps a table, merged range or auto filter it may not share cells with. */
        OVERLAPPING_RANGE,
        /** The style was not registered with this writer. */
        INVALID_STYLE,
        /** An argument is outside its allowed range. */
        INVALID_ARGUMENT,
        /** A workbook needs at least one visible worksheet. */
        NO_VISIBLE_WORKSHEET,
        /** A type cannot be mapped to columns. */
        UNSUPPORTED_TYPE,
        /** The writer or worksheet is already completed. */
        INVALID_STATE,
        /**
         * The input is not an XLSX workbook: not a ZIP archive, a legacy {@code .xls} file, an
         * encrypted workbook or another Office document.
         */
        INVALID_FILE,
        /** A part of the workbook is damaged or not valid XML; {@link #part()} names it. */
        MALFORMED,
        /** The workbook uses a feature the reader does not support, such as UTF-16 parts. */
        UNSUPPORTED,
        /** A size limit was exceeded, such as {@link ReadOptions#maxSharedStringsBytes()}. */
        LIMIT_EXCEEDED,
        /** No sheet has the requested name or index. */
        SHEET_NOT_FOUND
    }

    private final Kind kind;
    private final String part;

    /** Creates an exception of the given kind. */
    public XlsxException(Kind kind, String message) {
        super(message);
        this.kind = kind;
        this.part = null;
    }

    /** Creates an exception of the given kind with a cause. */
    public XlsxException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.part = null;
    }

    /** Creates an exception about a part of the package, such as {@code xl/worksheets/sheet1.xml}. */
    public XlsxException(Kind kind, String part, String message) {
        super(part + ": " + message);
        this.kind = kind;
        this.part = part;
    }

    /** Returns the category of the failure. */
    public Kind kind() {
        return kind;
    }

    /** Returns the package part the failure was found in, or null if it concerns no single part. */
    public String part() {
        return part;
    }
}
