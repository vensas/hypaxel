package de.vensas.hypaxel;

import java.util.Objects;

/** Definition of one worksheet column. Immutable. */
public final class Column {
    /** A column with the default width and format. */
    public static final Column DEFAULT = new Column(Double.NaN, StyleId.DEFAULT, false);

    private final double width;
    private final StyleId style;
    private final boolean hidden;

    private Column(double width, StyleId style, boolean hidden) {
        this.width = width;
        this.style = style;
        this.hidden = hidden;
    }

    /** Creates a column with a width in characters of the default font, 0 to 255. */
    public static Column ofWidth(double width) {
        return DEFAULT.withWidth(width);
    }

    /** Returns a copy with the given width in characters of the default font, 0 to 255. */
    public Column withWidth(double value) {
        if (!(value >= 0 && value <= 255)) {
            throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "column width must be within 0..255");
        }
        return new Column(value, style, hidden);
    }

    /** Returns a copy with the format applied to the column's empty cells. */
    public Column withStyle(StyleId value) {
        return new Column(width, Objects.requireNonNull(value, "value"), hidden);
    }

    /** Returns a copy that is hidden or visible. */
    public Column withHidden(boolean value) {
        return new Column(width, style, value);
    }

    /** Returns whether a width is set. */
    public boolean hasWidth() {
        return !Double.isNaN(width);
    }

    /** Returns the width in characters, or NaN for the default width. */
    public double width() {
        return width;
    }

    /** Returns the format of the column's empty cells. */
    public StyleId style() {
        return style;
    }

    /** Returns whether the column is hidden. */
    public boolean hidden() {
        return hidden;
    }
}
