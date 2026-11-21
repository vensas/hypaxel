package de.vensas.hypaxel;

/**
 * Reader settings: limits that protect against oversized or malicious workbooks, and which rows are
 * returned. Immutable; create instances with {@link #builder()}.
 */
public final class ReadOptions {
    /** The default options. */
    public static final ReadOptions DEFAULT = builder().build();

    private final long maxSharedStringsBytes;
    private final boolean includeEmptyRows;

    private ReadOptions(Builder builder) {
        maxSharedStringsBytes = builder.maxSharedStringsBytes;
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

    /** Returns whether rows that the file stores without any value are returned as well. */
    public boolean includeEmptyRows() {
        return includeEmptyRows;
    }

    /** Builder for {@link ReadOptions}. */
    public static final class Builder {
        private long maxSharedStringsBytes = 1L << 30;
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
