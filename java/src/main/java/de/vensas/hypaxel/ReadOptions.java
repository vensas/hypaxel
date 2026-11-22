package de.vensas.hypaxel;

/**
 * Reader settings: limits that protect against oversized or malicious workbooks, and which rows are
 * returned. Immutable; create instances with {@link #builder()}.
 */
public final class ReadOptions {
    /** The default options. */
    public static final ReadOptions DEFAULT = builder().build();

    private final long maxSharedStringsBytes;
    private final int maxTables;
    private final long maxTableBytes;
    private final int maxTokenBytes;
    private final int maxRowTextBytes;
    private final boolean includeEmptyRows;

    private ReadOptions(Builder builder) {
        maxSharedStringsBytes = builder.maxSharedStringsBytes;
        maxTables = builder.maxTables;
        maxTableBytes = builder.maxTableBytes;
        maxTokenBytes = builder.maxTokenBytes;
        maxRowTextBytes = builder.maxRowTextBytes;
        includeEmptyRows = builder.includeEmptyRows;
    }

    /** Returns a builder with the default options. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the largest amount of text, in UTF-8 bytes, the shared string table may hold. */
    public long maxSharedStringsBytes() {
        return maxSharedStringsBytes;
    }

    /** Returns the most tables read from one worksheet. */
    public int maxTables() {
        return maxTables;
    }

    /** Returns the most bytes of table, column and custom style names held for the tables of one worksheet. */
    public long maxTableBytes() {
        return maxTableBytes;
    }

    /** Returns the largest single XML token, such as a tag or a run of text, in bytes. */
    public int maxTokenBytes() {
        return maxTokenBytes;
    }

    /** Returns the largest amount of decoded text, in UTF-8 bytes, one row may hold. */
    public int maxRowTextBytes() {
        return maxRowTextBytes;
    }

    /** Returns whether rows that the file stores without any value are returned as well. */
    public boolean includeEmptyRows() {
        return includeEmptyRows;
    }

    /** Builder for {@link ReadOptions}. */
    public static final class Builder {
        private long maxSharedStringsBytes = 1L << 30;
        private int maxTables = 4096;
        private long maxTableBytes = 64L << 20;
        private int maxTokenBytes = 16 << 20;
        private int maxRowTextBytes = 256 << 20;
        private boolean includeEmptyRows;

        private Builder() {
        }

        /**
         * Sets the largest amount of text, in UTF-8 bytes, the shared string table may hold.
         * Default: 1 GiB.
         *
         * <p>The shared string table is the one part of a workbook that must be held in memory to
         * read rows; everything else is streamed. Lower this when reading untrusted uploads. A
         * table that exceeds it fails with {@link XlsxException.Kind#LIMIT_EXCEEDED}.
         */
        public Builder maxSharedStringsBytes(long bytes) {
            if (bytes < 0) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "maxSharedStringsBytes must not be negative");
            }
            maxSharedStringsBytes = bytes;
            return this;
        }

        /**
         * Sets the most tables read from one worksheet by {@link XlsxReader#tables(int)}.
         * Default: 4,096. A sheet with more fails with {@link XlsxException.Kind#LIMIT_EXCEEDED}.
         */
        public Builder maxTables(int count) {
            maxTables = (int) atLeastOne("maxTables", count);
            return this;
        }

        /**
         * Sets the most bytes of names held for the tables of one worksheet: table names, column
         * names and custom style names in UTF-8, plus 32 bytes per column. Default: 64 MiB.
         * Tables that exceed it fail with {@link XlsxException.Kind#LIMIT_EXCEEDED}.
         */
        public Builder maxTableBytes(long bytes) {
            maxTableBytes = atLeastOne("maxTableBytes", bytes);
            return this;
        }

        /**
         * Sets the largest single XML token, such as a tag with its attributes or a run of text,
         * in bytes. Default: 16 MiB. Values below 64 KiB are treated as 64 KiB, since Excel's cell
         * text alone can approach 100 KB. A larger token fails with
         * {@link XlsxException.Kind#LIMIT_EXCEEDED}.
         */
        public Builder maxTokenBytes(int bytes) {
            maxTokenBytes = (int) atLeastOne("maxTokenBytes", bytes);
            return this;
        }

        /**
         * Sets the largest amount of decoded text, in UTF-8 bytes, one row may hold. Default:
         * 256 MiB. A row with more fails with {@link XlsxException.Kind#LIMIT_EXCEEDED}.
         */
        public Builder maxRowTextBytes(int bytes) {
            maxRowTextBytes = (int) atLeastOne("maxRowTextBytes", bytes);
            return this;
        }

        private static long atLeastOne(String option, long value) {
            if (value < 1) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, option + " must be at least 1");
            }
            return value;
        }

        /**
         * Also returns the rows that the file stores without any value, for example a spacer row
         * with a height or an empty hidden row. Default: false.
         *
         * <p>Such rows have no cells but carry their number and properties. Rows that the file
         * does not store are never returned.
         */
        public Builder includeEmptyRows(boolean include) {
            includeEmptyRows = include;
            return this;
        }

        /** Creates the options. */
        public ReadOptions build() {
            return new ReadOptions(this);
        }
    }
}
