package de.vensas.hypaxel;

import java.util.Objects;

/**
 * A complete cell format. Immutable; create instances with {@link #builder()} and register them
 * with {@link XlsxWriter#addStyle(Style)}.
 *
 * <pre>{@code
 * Style header = Style.builder().bold().fontColor(Color.WHITE).fill(Color.hex("#305496"))
 *         .align(HorizontalAlignment.CENTER).build();
 * Style money = Style.builder().numberFormat("#,##0.00").build();
 * }</pre>
 */
public final class Style {
    private final String numberFormat;
    private final Font font;
    private final Color fill;
    private final Border border;
    private final Alignment alignment;

    private Style(Builder builder) {
        numberFormat = builder.numberFormat;
        font = builder.font == null ? null : builder.font.build();
        fill = builder.fill;
        border = builder.border;
        alignment = builder.alignment == null ? null : builder.alignment.build();
    }

    /** Returns a builder for the default format. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder initialized with this style. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.numberFormat = numberFormat;
        builder.font = font == null ? null : font.toBuilder();
        builder.fill = fill;
        builder.border = border;
        builder.alignment = alignment == null ? null : alignment.toBuilder();
        return builder;
    }

    /** Returns the number format code, or null for General. */
    public String numberFormat() {
        return numberFormat;
    }

    /** Returns the font, or null for the default font. */
    public Font font() {
        return font;
    }

    /** Returns the solid fill color, or null for no fill. */
    public Color fill() {
        return fill;
    }

    /** Returns the border, or null for no border. */
    public Border border() {
        return border;
    }

    /** Returns the alignment, or null for the default alignment. */
    public Alignment alignment() {
        return alignment;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Style s
                && Objects.equals(numberFormat, s.numberFormat)
                && Objects.equals(font, s.font)
                && Objects.equals(fill, s.fill)
                && Objects.equals(border, s.border)
                && Objects.equals(alignment, s.alignment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(numberFormat, font, fill, border, alignment);
    }

    @Override
    public String toString() {
        return "Style[numberFormat=" + numberFormat + ", font=" + font + ", fill=" + fill + ", border=" + border
                + ", alignment=" + alignment + "]";
    }

    /** Builder for {@link Style}. */
    public static final class Builder {
        private String numberFormat;
        private Font.Builder font;
        private Color fill;
        private Border border;
        private Alignment.Builder alignment;

        private Builder() {
        }

        private Font.Builder font() {
            if (font == null) {
                font = Font.builder();
            }
            return font;
        }

        private Alignment.Builder alignment() {
            if (alignment == null) {
                alignment = Alignment.builder();
            }
            return alignment;
        }

        /** Sets the Excel number format code, e.g. {@code "0.00"}, {@code "#,##0"} or {@code "yyyy-mm-dd"}. */
        public Builder numberFormat(String code) {
            if (code != null && (code.isEmpty() || code.length() > 255)) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "number format must have 1..255 characters");
            }
            numberFormat = code;
            return this;
        }

        /** Replaces the font. */
        public Builder font(Font value) {
            font = value == null ? null : value.toBuilder();
            return this;
        }

        /** Makes the font bold. */
        public Builder bold() {
            font().bold(true);
            return this;
        }

        /** Makes the font italic. */
        public Builder italic() {
            font().italic(true);
            return this;
        }

        /** Underlines the text. */
        public Builder underline() {
            font().underline(true);
            return this;
        }

        /** Strikes the text through. */
        public Builder strikethrough() {
            font().strikethrough(true);
            return this;
        }

        /** Sets the font color. */
        public Builder fontColor(Color color) {
            font().color(color);
            return this;
        }

        /** Sets the font size in points. */
        public Builder fontSize(double points) {
            font().size(points);
            return this;
        }

        /** Sets the font name. */
        public Builder fontName(String name) {
            font().name(name);
            return this;
        }

        /** Sets a solid background fill; null removes it. */
        public Builder fill(Color color) {
            fill = color;
            return this;
        }

        /** Sets the border; null removes it. */
        public Builder border(Border value) {
            border = value;
            return this;
        }

        /** Replaces the alignment. */
        public Builder alignment(Alignment value) {
            alignment = value == null ? null : value.toBuilder();
            return this;
        }

        /** Sets the horizontal alignment. */
        public Builder align(HorizontalAlignment horizontal) {
            alignment().horizontal(horizontal);
            return this;
        }

        /** Sets the vertical alignment. */
        public Builder verticalAlign(VerticalAlignment vertical) {
            alignment().vertical(vertical);
            return this;
        }

        /** Wraps text onto several lines. */
        public Builder wrapText() {
            alignment().wrapText(true);
            return this;
        }

        /** Sets the indentation level. */
        public Builder indent(int level) {
            alignment().indent(level);
            return this;
        }

        /** Sets the text rotation in degrees. */
        public Builder rotation(int degrees) {
            alignment().rotation(degrees);
            return this;
        }

        /** Creates the style. */
        public Style build() {
            return new Style(this);
        }
    }
}
