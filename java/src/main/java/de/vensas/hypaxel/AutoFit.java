package de.vensas.hypaxel;

/**
 * Column auto-fit settings for {@link SheetOptions.Builder#autoFit(AutoFit)}. Immutable; create
 * instances with {@link #builder()}.
 *
 * <p>Column widths precede the cell data in the file, so the writer holds back the worksheet's
 * XML until {@link #sampleRows()} rows have been written (or the worksheet ends), estimates the
 * widths from those rows and then streams as usual. Memory is bounded by the sample: at most
 * {@code sampleRows} rows or 8 MiB of XML, whichever comes first. Widths are estimated from the
 * text, number format and font size of each cell; formulas and merged cells are not measured.
 */
public final class AutoFit {
    /** Measures the first 1,000 rows and allows widths up to 255 characters. */
    public static final AutoFit DEFAULT = builder().build();

    private final int sampleRows;
    private final double maxWidth;

    private AutoFit(Builder builder) {
        sampleRows = builder.sampleRows;
        maxWidth = builder.maxWidth;
    }

    /** Returns a builder with the default settings. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the number of rows measured before the widths are fixed. */
    public int sampleRows() {
        return sampleRows;
    }

    /** Returns the upper bound for a fitted width, in characters. */
    public double maxWidth() {
        return maxWidth;
    }

    /** Builder for {@link AutoFit}. */
    public static final class Builder {
        private int sampleRows = 1000;
        private double maxWidth = 255;

        private Builder() {
        }

        /** Sets the number of rows measured before the widths are fixed; at least 1. Default: 1,000. */
        public Builder sampleRows(int rows) {
            if (rows < 1) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "the auto-fit sample needs at least one row");
            }
            sampleRows = rows;
            return this;
        }

        /** Sets the upper bound for a fitted width in characters; positive. Default: 255, Excel's maximum. */
        public Builder maxWidth(double width) {
            if (!(width > 0)) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "the auto-fit maximum width must be positive");
            }
            maxWidth = width;
            return this;
        }

        /** Creates the settings. */
        public AutoFit build() {
            return new AutoFit(this);
        }
    }
}
