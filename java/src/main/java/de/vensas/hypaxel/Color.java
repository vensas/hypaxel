package de.vensas.hypaxel;

/** An ARGB color. */
public final class Color {
    /** Opaque black. */
    public static final Color BLACK = rgb(0, 0, 0);
    /** Opaque white. */
    public static final Color WHITE = rgb(255, 255, 255);
    /** Opaque red. */
    public static final Color RED = rgb(255, 0, 0);
    /** Opaque green. */
    public static final Color GREEN = rgb(0, 128, 0);
    /** Opaque blue. */
    public static final Color BLUE = rgb(0, 0, 255);
    /** Opaque yellow. */
    public static final Color YELLOW = rgb(255, 255, 0);
    /** Opaque light gray. */
    public static final Color LIGHT_GRAY = rgb(217, 217, 217);
    /** Opaque dark gray. */
    public static final Color DARK_GRAY = rgb(89, 89, 89);

    private final int argb;

    private Color(int argb) {
        this.argb = argb;
    }

    /** Creates an opaque color from components in the range 0 to 255. */
    public static Color rgb(int red, int green, int blue) {
        if ((red | green | blue) < 0 || red > 255 || green > 255 || blue > 255) {
            throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "color components must be within 0..255");
        }
        return new Color(0xFF00_0000 | red << 16 | green << 8 | blue);
    }

    /** Creates a color from a packed {@code 0xAARRGGBB} value. */
    public static Color argb(int argb) {
        return new Color(argb);
    }

    /** Parses {@code #RRGGBB}, {@code RRGGBB}, {@code #AARRGGBB} or {@code AARRGGBB}. */
    public static Color hex(String text) {
        String digits = text.startsWith("#") ? text.substring(1) : text;
        try {
            if (digits.length() == 6) {
                return new Color(0xFF00_0000 | Integer.parseUnsignedInt(digits, 16));
            }
            if (digits.length() == 8) {
                return new Color(Integer.parseUnsignedInt(digits, 16));
            }
        } catch (NumberFormatException e) {
            throw invalid(text, e);
        }
        throw invalid(text, null);
    }

    private static XlsxException invalid(String text, Throwable cause) {
        return new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "not a hex color: \"" + text + "\"", cause);
    }

    /** Returns the packed {@code 0xAARRGGBB} value. */
    public int argb() {
        return argb;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Color color && color.argb == argb;
    }

    @Override
    public int hashCode() {
        return argb;
    }

    @Override
    public String toString() {
        return String.format("#%08X", argb);
    }
}
