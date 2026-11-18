package de.vensas.hypaxel;

/** Options for writing objects as rows. Immutable; create instances with {@link #builder()}. */
public final class ObjectOptions {
    /** A header row in the default header style and no auto filter. */
    public static final ObjectOptions DEFAULT = builder().build();

    private final boolean header;
    private final StyleId headerStyle;
    private final boolean autoFilter;

    private ObjectOptions(Builder builder) {
        header = builder.header;
        headerStyle = builder.headerStyle;
        autoFilter = builder.autoFilter;
    }

    /** Returns a builder with the default options. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns whether a header row with the column names is written. */
    public boolean header() {
        return header;
    }

    /** Returns the header style, or null for bold text on a light gray fill. */
    public StyleId headerStyle() {
        return headerStyle;
    }

    /** Returns whether an auto filter is added over the written range. */
    public boolean autoFilter() {
        return autoFilter;
    }

    /** Builder for {@link ObjectOptions}. */
    public static final class Builder {
        private boolean header = true;
        private StyleId headerStyle;
        private boolean autoFilter;

        private Builder() {
        }

        /** Sets whether a header row with the column names is written. Default: true. */
        public Builder header(boolean write) {
            header = write;
            return this;
        }

        /** Sets the style of the header row; null selects bold text on a light gray fill. */
        public Builder headerStyle(StyleId style) {
            headerStyle = style;
            return this;
        }

        /** Adds an auto filter over the written range; needs the header row. Default: false. */
        public Builder autoFilter(boolean add) {
            autoFilter = add;
            return this;
        }

        /** Creates the options. */
        public ObjectOptions build() {
            return new ObjectOptions(this);
        }
    }
}
