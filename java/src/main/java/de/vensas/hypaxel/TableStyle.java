package de.vensas.hypaxel;

import java.util.Objects;

/** A built-in table style, as shown in Excel's table style gallery. Immutable. */
public final class TableStyle {
    /** No table formatting; filter buttons and structured references still work. */
    public static final TableStyle NONE = new TableStyle(null);
    /** {@code TableStyleMedium2}, Excel's default for new tables. */
    public static final TableStyle DEFAULT = medium(2);

    private final String name;

    private TableStyle(String name) {
        this.name = name;
    }

    /** {@code TableStyleLight1} to {@code TableStyleLight21}. */
    public static TableStyle light(int number) {
        return create("Light", number, 21);
    }

    /** {@code TableStyleMedium1} to {@code TableStyleMedium28}. */
    public static TableStyle medium(int number) {
        return create("Medium", number, 28);
    }

    /** {@code TableStyleDark1} to {@code TableStyleDark11}. */
    public static TableStyle dark(int number) {
        return create("Dark", number, 11);
    }

    private static TableStyle create(String family, int number, int max) {
        if (number < 1 || number > max) {
            throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT,
                    family + " table styles are numbered 1 to " + max + "; got " + number);
        }
        return new TableStyle("TableStyle" + family + number);
    }

    /** Returns the style name used in the file, e.g. {@code TableStyleMedium2}, or null for {@link #NONE}. */
    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TableStyle style && Objects.equals(name, style.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        return name == null ? "None" : name;
    }
}
