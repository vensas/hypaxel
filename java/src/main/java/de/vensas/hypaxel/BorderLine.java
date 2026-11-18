package de.vensas.hypaxel;

import java.util.Objects;

/**
 * One edge of a border.
 *
 * @param style line style
 * @param color line color, or null for the automatic color
 */
public record BorderLine(BorderStyle style, Color color) {
    /** No line. */
    public static final BorderLine NONE = new BorderLine(BorderStyle.NONE, null);

    /** Validates the line. */
    public BorderLine {
        Objects.requireNonNull(style, "style");
    }

    /** Creates a line in the automatic color. */
    public static BorderLine of(BorderStyle style) {
        return new BorderLine(style, null);
    }

    /** Creates a colored line. */
    public static BorderLine of(BorderStyle style, Color color) {
        return new BorderLine(style, color);
    }
}
