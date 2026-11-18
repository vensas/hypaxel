package de.vensas.hypaxel;

import java.util.Objects;

/** Font settings. Immutable; create instances with {@link #builder()}. Defaults to Calibri 11. */
public final class Font {
    /** Calibri 11, the workbook's default font. */
    public static final Font DEFAULT = builder().build();

    private final String name;
    private final int sizeCentipoints;
    private final boolean bold;
    private final boolean italic;
    private final boolean underline;
    private final boolean strikethrough;
    private final Color color;

    private Font(Builder builder) {
        name = builder.name;
        sizeCentipoints = builder.sizeCentipoints;
        bold = builder.bold;
        italic = builder.italic;
        underline = builder.underline;
        strikethrough = builder.strikethrough;
        color = builder.color;
    }

    /** Returns a builder for Calibri 11. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder initialized with this font. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.name = name;
        builder.sizeCentipoints = sizeCentipoints;
        builder.bold = bold;
        builder.italic = italic;
        builder.underline = underline;
        builder.strikethrough = strikethrough;
        builder.color = color;
        return builder;
    }

    /** Returns the font name. */
    public String name() {
        return name;
    }

    /** Returns the size in points. */
    public double size() {
        return sizeCentipoints / 100.0;
    }

    /** Returns whether the font is bold. */
    public boolean bold() {
        return bold;
    }

    /** Returns whether the font is italic. */
    public boolean italic() {
        return italic;
    }

    /** Returns whether the text is underlined. */
    public boolean underline() {
        return underline;
    }

    /** Returns whether the text is struck through. */
    public boolean strikethrough() {
        return strikethrough;
    }

    /** Returns the color, or null for the automatic color. */
    public Color color() {
        return color;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Font f
                && name.equals(f.name)
                && sizeCentipoints == f.sizeCentipoints
                && bold == f.bold
                && italic == f.italic
                && underline == f.underline
                && strikethrough == f.strikethrough
                && Objects.equals(color, f.color);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, sizeCentipoints, bold, italic, underline, strikethrough, color);
    }

    @Override
    public String toString() {
        return "Font[name=" + name + ", size=" + size() + ", bold=" + bold + ", italic=" + italic
                + ", underline=" + underline + ", strikethrough=" + strikethrough + ", color=" + color + "]";
    }

    /** Builder for {@link Font}. */
    public static final class Builder {
        private String name = "Calibri";
        private int sizeCentipoints = 1100;
        private boolean bold;
        private boolean italic;
        private boolean underline;
        private boolean strikethrough;
        private Color color;

        private Builder() {
        }

        /** Sets the font name. */
        public Builder name(String value) {
            if (value.isEmpty() || value.length() > 31) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "font name must have 1..31 characters");
            }
            name = value;
            return this;
        }

        /** Sets the size in points, 1 to 409. */
        public Builder size(double points) {
            if (!(points >= 1 && points <= 409)) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "font size must be within 1..409 points");
            }
            sizeCentipoints = (int) Math.round(points * 100);
            return this;
        }

        /** Sets whether the font is bold. */
        public Builder bold(boolean value) {
            bold = value;
            return this;
        }

        /** Sets whether the font is italic. */
        public Builder italic(boolean value) {
            italic = value;
            return this;
        }

        /** Sets whether the text is underlined. */
        public Builder underline(boolean value) {
            underline = value;
            return this;
        }

        /** Sets whether the text is struck through. */
        public Builder strikethrough(boolean value) {
            strikethrough = value;
            return this;
        }

        /** Sets the color; null selects the automatic color. */
        public Builder color(Color value) {
            color = value;
            return this;
        }

        /** Creates the font. */
        public Font build() {
            return new Font(this);
        }
    }
}
