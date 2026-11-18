package de.vensas.hypaxel;

import java.util.Objects;

/** Text alignment within a cell. Immutable; create instances with {@link #builder()}. */
public final class Alignment {
    /** Rotation value for text stacked vertically. */
    public static final int VERTICAL_TEXT = 255;

    private final HorizontalAlignment horizontal;
    private final VerticalAlignment vertical;
    private final boolean wrapText;
    private final boolean shrinkToFit;
    private final int indent;
    private final int rotation;

    private Alignment(Builder builder) {
        horizontal = builder.horizontal;
        vertical = builder.vertical;
        wrapText = builder.wrapText;
        shrinkToFit = builder.shrinkToFit;
        indent = builder.indent;
        rotation = builder.rotation;
    }

    /** Returns a builder with the default alignment. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder initialized with this alignment. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.horizontal = horizontal;
        builder.vertical = vertical;
        builder.wrapText = wrapText;
        builder.shrinkToFit = shrinkToFit;
        builder.indent = indent;
        builder.rotation = rotation;
        return builder;
    }

    /** Returns the horizontal alignment. */
    public HorizontalAlignment horizontal() {
        return horizontal;
    }

    /** Returns the vertical alignment. */
    public VerticalAlignment vertical() {
        return vertical;
    }

    /** Returns whether text wraps onto several lines. */
    public boolean wrapText() {
        return wrapText;
    }

    /** Returns whether text shrinks to fit the cell. */
    public boolean shrinkToFit() {
        return shrinkToFit;
    }

    /** Returns the indentation level. */
    public int indent() {
        return indent;
    }

    /** Returns the rotation in degrees, or {@link #VERTICAL_TEXT}. */
    public int rotation() {
        return rotation;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Alignment a
                && horizontal == a.horizontal
                && vertical == a.vertical
                && wrapText == a.wrapText
                && shrinkToFit == a.shrinkToFit
                && indent == a.indent
                && rotation == a.rotation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(horizontal, vertical, wrapText, shrinkToFit, indent, rotation);
    }

    @Override
    public String toString() {
        return "Alignment[horizontal=" + horizontal + ", vertical=" + vertical + ", wrapText=" + wrapText
                + ", shrinkToFit=" + shrinkToFit + ", indent=" + indent + ", rotation=" + rotation + "]";
    }

    /** Builder for {@link Alignment}. */
    public static final class Builder {
        private HorizontalAlignment horizontal = HorizontalAlignment.GENERAL;
        private VerticalAlignment vertical = VerticalAlignment.BOTTOM;
        private boolean wrapText;
        private boolean shrinkToFit;
        private int indent;
        private int rotation;

        private Builder() {
        }

        /** Sets the horizontal alignment. */
        public Builder horizontal(HorizontalAlignment value) {
            horizontal = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the vertical alignment. */
        public Builder vertical(VerticalAlignment value) {
            vertical = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets whether text wraps onto several lines. */
        public Builder wrapText(boolean value) {
            wrapText = value;
            return this;
        }

        /** Sets whether text shrinks to fit the cell. */
        public Builder shrinkToFit(boolean value) {
            shrinkToFit = value;
            return this;
        }

        /** Sets the indentation level, 0 to 250. */
        public Builder indent(int level) {
            if (level < 0 || level > 250) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "indent must be within 0..250");
            }
            indent = level;
            return this;
        }

        /** Sets the rotation: 0 to 180 degrees, or {@link Alignment#VERTICAL_TEXT}. */
        public Builder rotation(int degrees) {
            if ((degrees < 0 || degrees > 180) && degrees != VERTICAL_TEXT) {
                throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "rotation must be within 0..180 or 255");
            }
            rotation = degrees;
            return this;
        }

        /** Creates the alignment. */
        public Alignment build() {
            return new Alignment(this);
        }
    }
}
