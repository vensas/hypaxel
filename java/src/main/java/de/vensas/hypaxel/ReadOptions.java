package de.vensas.hypaxel;

/** Limits that protect a reader against oversized or malicious workbooks. Immutable; create instances with {@link #builder()}. */
public final class ReadOptions {
    /** The default options. */
    public static final ReadOptions DEFAULT = builder().build();

    private final long maxSharedStringsBytes;

    private ReadOptions(Builder builder) {
        maxSharedStringsBytes = builder.maxSharedStringsBytes;
    }

    /** Returns a builder with the default options. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the largest amount of text, in UTF-8 bytes, the shared string table may hold. */
    public long maxSharedStringsBytes() {
        return maxSharedStringsBytes;
    }

    /** Builder for {@link ReadOptions}. */
    public static final class Builder {
        private long maxSharedStringsBytes = 1L << 30;

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

        /** Creates the options. */
        public ReadOptions build() {
            return new ReadOptions(this);
        }
    }
}
