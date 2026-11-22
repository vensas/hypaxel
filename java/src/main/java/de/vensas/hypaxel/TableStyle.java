package de.vensas.hypaxel;

import java.util.Objects;

/**
 * A table style: one of the built-in styles shown in Excel's table style gallery, or a custom
 * style the workbook defines by name. Immutable.
 */
public final class TableStyle {
    /** No table formatting; filter buttons and structured references still work. */
    public static final TableStyle NONE = new TableStyle(null, false);
    /** {@code TableStyleMedium2}, Excel's default for new tables. */
    public static final TableStyle DEFAULT = medium(2);

    private static final String PREFIX = "TableStyle";

    private final String name;
    private final boolean custom;

    private TableStyle(String name, boolean custom) {
        this.name = name;
        this.custom = custom;
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

    /**
     * A custom table style, referenced by the name the workbook's styles part defines it under.
     * The name is written verbatim; the writer rejects a name that is empty, longer than 255
     * characters or contains control characters ({@link XlsxException.Kind#INVALID_ARGUMENT}). A
     * custom style named like a built-in style is written as that name, but is not equal to the
     * built-in style.
     */
    public static TableStyle custom(String name) {
        return new TableStyle(Objects.requireNonNull(name, "name"), true);
    }

    /**
     * Returns the style a file names: a built-in name such as {@code TableStyleMedium9} (ignoring
     * surrounding whitespace) gives the built-in style, an empty name gives {@link #NONE}, and any
     * other name gives a {@link #custom(String)} style with the name unchanged.
     */
    public static TableStyle fromName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            return NONE;
        }
        TableStyle builtIn = builtIn(name.strip());
        return builtIn != null ? builtIn : custom(name);
    }

    private static TableStyle builtIn(String name) {
        if (!name.startsWith(PREFIX)) {
            return null;
        }
        String rest = name.substring(PREFIX.length());
        int digits = 0;
        while (digits < rest.length() && (rest.charAt(digits) < '0' || rest.charAt(digits) > '9')) {
            digits++;
        }
        String suffix = rest.substring(digits);
        if (suffix.isEmpty() || suffix.length() > 3) {
            return null;
        }
        int number = 0;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            number = number * 10 + (c - '0');
        }
        return switch (rest.substring(0, digits)) {
            case "Light" -> number >= 1 && number <= 21 ? light(number) : null;
            case "Medium" -> number >= 1 && number <= 28 ? medium(number) : null;
            case "Dark" -> number >= 1 && number <= 11 ? dark(number) : null;
            default -> null;
        };
    }

    private static TableStyle create(String family, int number, int max) {
        if (number < 1 || number > max) {
            throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT,
                    family + " table styles are numbered 1 to " + max + "; got " + number);
        }
        return new TableStyle(PREFIX + family + number, false);
    }

    /**
     * Returns the style name used in the file, e.g. {@code TableStyleMedium2} or the name of a
     * custom style, or null for {@link #NONE}.
     */
    public String name() {
        return name;
    }

    /** Returns whether this is a {@link #custom(String)} style. */
    public boolean isCustom() {
        return custom;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TableStyle style && custom == style.custom && Objects.equals(name, style.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name) * 31 + (custom ? 1 : 0);
    }

    @Override
    public String toString() {
        return name == null ? "None" : custom ? "Custom(" + name + ")" : name;
    }
}
