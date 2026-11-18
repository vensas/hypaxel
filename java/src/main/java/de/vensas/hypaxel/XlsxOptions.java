package de.vensas.hypaxel;

import java.time.ZoneId;
import java.util.Objects;

/** Workbook-level options. Immutable; create instances with {@link #builder()}. */
public final class XlsxOptions {
    /** The default options. */
    public static final XlsxOptions DEFAULT = builder().build();

    private static final int MIN_BUFFER = 1024;

    private final int compressionLevel;
    private final StringStorage stringStorage;
    private final int flushThreshold;
    private final int xmlBufferSize;
    private final boolean cellReferences;
    private final boolean date1904;
    private final ZoneId zone;
    private final boolean closeOutput;

    private XlsxOptions(Builder builder) {
        compressionLevel = builder.compressionLevel;
        stringStorage = builder.stringStorage;
        flushThreshold = builder.flushThreshold;
        xmlBufferSize = builder.xmlBufferSize;
        cellReferences = builder.cellReferences;
        date1904 = builder.date1904;
        zone = builder.zone;
        closeOutput = builder.closeOutput;
    }

    /** Returns a builder with the default options. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the deflate level, 0 to 9. */
    public int compressionLevel() {
        return compressionLevel;
    }

    /** Returns how strings are stored. */
    public StringStorage stringStorage() {
        return stringStorage;
    }

    /** Returns the number of compressed bytes buffered before they are written to the destination. */
    public int flushThreshold() {
        return flushThreshold;
    }

    /** Returns the number of uncompressed XML bytes buffered before they are compressed. */
    public int xmlBufferSize() {
        return xmlBufferSize;
    }

    /** Returns whether every cell carries a reference such as {@code B7}. */
    public boolean cellReferences() {
        return cellReferences;
    }

    /** Returns whether the workbook uses the 1904 date system. */
    public boolean date1904() {
        return date1904;
    }

    /** Returns the time zone used for {@link java.time.Instant} and {@link java.util.Date} values, or null for the system default. */
    public ZoneId zone() {
        return zone;
    }

    /** Returns whether closing the writer closes the destination stream. */
    public boolean closeOutput() {
        return closeOutput;
    }

    /** Builder for {@link XlsxOptions}. */
    public static final class Builder {
        private int compressionLevel = 1;
        private StringStorage stringStorage = StringStorage.INLINE;
        private int flushThreshold = 64 * 1024;
        private int xmlBufferSize = 64 * 1024;
        private boolean cellReferences;
        private boolean date1904;
        private ZoneId zone;
        private boolean closeOutput = true;

        private Builder() {
        }

        /** Sets the deflate level, 0 (stored) to 9 (smallest). Default: 1, the fastest. */
        public Builder compressionLevel(int level) {
            if (level < 0 || level > 9) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "compression level must be within 0..9");
            }
            compressionLevel = level;
            return this;
        }

        /** Sets how strings are stored. Default: {@link StringStorage#INLINE}. */
        public Builder stringStorage(StringStorage storage) {
            stringStorage = Objects.requireNonNull(storage, "storage");
            return this;
        }

        /** Sets the compressed bytes buffered before a write to the destination. Default: 64 KiB; minimum 1 KiB. */
        public Builder flushThreshold(int bytes) {
            flushThreshold = checkBuffer(bytes, "flush threshold");
            return this;
        }

        /** Sets the uncompressed XML bytes buffered before compression. Default: 64 KiB; minimum 1 KiB. */
        public Builder xmlBufferSize(int bytes) {
            xmlBufferSize = checkBuffer(bytes, "XML buffer size");
            return this;
        }

        /**
         * Writes a reference such as {@code B7} on every cell. By default references are only
         * written after a gap, which the format allows; some readers (ExcelJS) need them always.
         */
        public Builder cellReferences(boolean always) {
            cellReferences = always;
            return this;
        }

        /** Uses the 1904 date system instead of the 1900 one. */
        public Builder date1904(boolean enabled) {
            date1904 = enabled;
            return this;
        }

        /**
         * Sets the time zone in which {@link java.time.Instant} and {@link java.util.Date} values
         * are converted to wall-clock time. Default: the system default zone when the writer is created.
         */
        public Builder zone(ZoneId value) {
            zone = value;
            return this;
        }

        /** Sets whether closing the writer also closes the destination stream. Default: true. */
        public Builder closeOutput(boolean close) {
            closeOutput = close;
            return this;
        }

        /** Creates the options. */
        public XlsxOptions build() {
            return new XlsxOptions(this);
        }

        private static int checkBuffer(int bytes, String what) {
            if (bytes < MIN_BUFFER || bytes > (1 << 30)) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, what + " must be within 1024..1073741824 bytes");
            }
            return bytes;
        }
    }
}
