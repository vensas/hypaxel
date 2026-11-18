package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.AutoFit;
import de.vensas.hypaxel.Column;
import de.vensas.hypaxel.Font;
import java.util.Arrays;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeSet;

/**
 * Column auto-fit: width estimates for cell content in pixels of Calibri 11 at 96 DPI, the metric
 * Excel's column widths are defined in (a width of {@code w} characters is {@code 7w + 5} pixels).
 */
final class ColumnFit {
    /** The sample stops early once the held-back XML reaches this size. */
    static final int MAX_SAMPLE_BYTES = 8 * 1024 * 1024;

    private static final float DIGIT_PX = 7;

    /** Calibri 11 advance widths in pixels for ASCII 0x20..0x7E. */
    private static final byte[] ASCII_PX = {
        3, 3, 4, 7, 7, 10, 10, 2, 4, 4, 7, 7, 3, 4, 3, 5, // space ! " # $ % & ' ( ) * + , - . /
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, // 0-9
        3, 3, 7, 7, 7, 6, 13, // : ; < = > ? @
        9, 8, 8, 9, 7, 7, 9, 9, 4, 5, 8, 6, 12, 10, 10, 8, 10, 8, 7, 7, 9, 9, 13, 8, 7, 7, // A-Z
        4, 5, 4, 7, 7, 4, // [ \ ] ^ _ `
        7, 8, 6, 8, 8, 5, 7, 8, 4, 4, 7, 4, 12, 8, 8, 8, 8, 5, 6, 5, 8, 7, 11, 7, 7, 6, // a-z
        5, 7, 5, 7, // { | } ~
    };

    private final StyleRegistry styles;
    private final AutoFit settings;
    private final SortedMap<Integer, Column> columns;
    private final int colsAt;
    private final XmlBuffer scratch = new XmlBuffer(32);
    private float[] widths = new float[16];
    private StyleFit[] styleFits = new StyleFit[16];
    private int rows;

    ColumnFit(StyleRegistry styles, AutoFit settings, SortedMap<Integer, Column> columns, int colsAt) {
        this.styles = styles;
        this.settings = settings;
        this.columns = columns;
        this.colsAt = colsAt;
    }

    /** Where {@code <cols>} belongs in the held-back XML. */
    int colsAt() {
        return colsAt;
    }

    /** Counts a closed row; true once the sample is complete. */
    boolean rowClosed(int xmlLength) {
        return ++rows >= settings.sampleRows() || xmlLength >= MAX_SAMPLE_BYTES;
    }

    void text(int column, int style, String text) {
        observe(column, textPx(text) * style(style).scale);
    }

    void number(int column, int style, double value) {
        StyleFit fit = style(style);
        observe(column, (Double.isFinite(value) ? numberPx(fit.shape, value, scratch) : textPx("#NUM!")) * fit.scale);
    }

    /** Writes {@code <cols>} for the explicit columns and the fitted ones. */
    void writeColumns(XmlBuffer x) {
        TreeSet<Integer> indexes = new TreeSet<>(columns.keySet());
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] > 0) {
                indexes.add(i + 1);
            }
        }
        if (indexes.isEmpty()) {
            return;
        }
        x.raw("<cols>");
        for (int index : indexes) {
            Column column = columns.getOrDefault(index, Column.DEFAULT);
            double width = column.width();
            if (!column.hasWidth() && index <= widths.length && widths[index - 1] > 0) {
                width = widthFor(widths[index - 1]);
            }
            writeColumn(x, index, column, width);
        }
        x.raw("</cols>");
    }

    /** Writes a {@code <col>} element; a NaN width is the default width. */
    static void writeColumn(XmlBuffer x, int index, Column column, double width) {
        boolean custom = !Double.isNaN(width);
        x.raw("<col min=\"").putLong(index).raw("\" max=\"").putLong(index);
        x.raw("\" width=\"").putDouble(custom ? Math.max(0, Math.min(width, 255)) : 9.140625).raw('"');
        if (custom) {
            x.raw(" customWidth=\"1\"");
        }
        if (column.style().index() != 0) {
            x.raw(" style=\"").putLong(column.style().index()).raw('"');
        }
        if (column.hidden()) {
            x.raw(" hidden=\"1\"");
        }
        x.raw("/>");
    }

    /** Column width in characters for content {@code px} wide, with Excel's cell padding. */
    private double widthFor(float px) {
        double chars = ((double) px + 2) / 7;
        return Math.max(0, Math.min(Math.min(Math.ceil(chars * 100) / 100, settings.maxWidth()), 255));
    }

    private void observe(int column, float px) {
        int i = column - 1;
        if (i >= widths.length) {
            widths = Arrays.copyOf(widths, Math.max(i + 1, widths.length * 2));
        }
        if (px > widths[i]) {
            widths[i] = px;
        }
    }

    private StyleFit style(int style) {
        if (style >= styleFits.length) {
            styleFits = Arrays.copyOf(styleFits, Math.max(style + 1, styleFits.length * 2));
        }
        StyleFit fit = styleFits[style];
        if (fit == null) {
            fit = createStyleFit(style);
            styleFits[style] = fit;
        }
        return fit;
    }

    private StyleFit createStyleFit(int style) {
        Font font = styles.font(style);
        float scale = Math.round(font.size() * 100) / 1100f;
        if (font.bold()) {
            scale *= 1.1f;
        }
        String custom = styles.customNumberFormat(style);
        return new StyleFit(scale, custom == null ? builtinShape(styles.numberFormat(style)) : customShape(custom));
    }

    static float charPx(int code) {
        if (code >= 0x20 && code < 0x7F) {
            return ASCII_PX[code - 0x20];
        }
        return isWide(code) ? 15 : 8;
    }

    /** East Asian wide and fullwidth characters, plus emoji, which Excel renders about two digits wide. */
    private static boolean isWide(int c) {
        return c >= 0x1100 && c <= 0x115F || c >= 0x2E80 && c <= 0x303E || c >= 0x3041 && c <= 0x33FF
                || c >= 0x3400 && c <= 0x4DBF || c >= 0x4E00 && c <= 0x9FFF || c >= 0xA000 && c <= 0xA4CF
                || c >= 0xAC00 && c <= 0xD7A3 || c >= 0xF900 && c <= 0xFAFF || c >= 0xFE30 && c <= 0xFE4F
                || c >= 0xFF00 && c <= 0xFF60 || c >= 0xFFE0 && c <= 0xFFE6 || c >= 0x1F300 && c <= 0x1F64F
                || c >= 0x1F900 && c <= 0x1F9FF || c >= 0x20000 && c <= 0x3FFFD;
    }

    /** Width of the longest line of {@code text}. */
    static float textPx(String text) {
        float widest = 0;
        float line = 0;
        int length = text.length();
        for (int i = 0; i < length; ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\n') {
                widest = Math.max(widest, line);
                line = 0;
            } else if (c != '\r') {
                line += charPx(c);
            }
        }
        return Math.max(widest, line);
    }

    static float numberPx(Shape shape, double value, XmlBuffer scratch) {
        return switch (shape.kind) {
            case GENERAL -> generalPx(value, scratch);
            case CONSTANT -> shape.literalPx;
            case FIXED -> fixedPx(shape, value);
        };
    }

    static float generalPx(double value, XmlBuffer scratch) {
        if (!Double.isFinite(value)) {
            return textPx("#NUM!");
        }
        double magnitude = Math.abs(value);
        if (magnitude != 0 && !(magnitude >= 1e-9 && magnitude < 1e11)) {
            return textPx(value < 0 ? "-1.23457E+11" : "1.23457E+11");
        }
        scratch.clear();
        scratch.putDouble(value);
        // General shows at most 11 characters, rounding the fraction away.
        int length = Math.min(scratch.length(), 11);
        byte[] digits = scratch.array();
        float px = 0;
        for (int i = 0; i < length; i++) {
            px += charPx(digits[i]);
        }
        return px;
    }

    private static float fixedPx(Shape shape, double value) {
        double scaled = Math.abs(value) * Math.pow(100, shape.percent);
        double factor = Math.pow(10, shape.decimals);
        double rounded = Math.floor(scaled * factor + 0.5) / factor;
        double whole = Math.floor(rounded);
        int digits = whole >= 1 ? (int) Math.floor(Math.log10(whole)) + 1 : 0;
        digits = Math.max(digits, shape.minIntegerDigits);
        float px = digits * DIGIT_PX + shape.literalPx + shape.percent * charPx('%');
        if (shape.thousands && digits > 3) {
            px += (digits - 1) / 3 * charPx(',');
        }
        if (shape.decimals > 0) {
            px += charPx('.') + shape.decimals * DIGIT_PX;
        }
        if (value < 0) {
            px += charPx('-');
        }
        return px;
    }

    static Shape builtinShape(int id) {
        return switch (id) {
            case 1 -> Shape.fixed(0, 1, false, 0, 0);
            case 2 -> Shape.fixed(2, 1, false, 0, 0);
            case 3 -> Shape.fixed(0, 1, true, 0, 0);
            case 4 -> Shape.fixed(2, 1, true, 0, 0);
            case 9 -> Shape.fixed(0, 1, false, 1, 0);
            case 10 -> Shape.fixed(2, 1, false, 1, 0);
            case 11 -> Shape.constant(textPx("1.00E+00"));
            case 14 -> Shape.constant(textPx("12/31/2026"));
            case 21 -> Shape.constant(textPx("23:59:59"));
            case 22 -> Shape.constant(textPx("12/31/2026 23:59"));
            case 46 -> Shape.constant(textPx("100:00:00"));
            default -> Shape.GENERAL;
        };
    }

    /** Estimates the rendering of a custom format code from its first section. */
    static Shape customShape(String code) {
        int semicolon = code.indexOf(';');
        String section = semicolon < 0 ? code : code.substring(0, semicolon);
        if (section.isEmpty() || section.equals("@") || section.equalsIgnoreCase("general")) {
            return Shape.GENERAL;
        }

        StringBuilder tokens = new StringBuilder();
        float literalPx = 0;
        for (int i = 0; i < section.length(); i++) {
            char c = section.charAt(i);
            switch (c) {
                case '"' -> {
                    while (++i < section.length() && section.charAt(i) != '"') {
                        literalPx += charPx(section.charAt(i));
                    }
                }
                case '\\', '_' -> {
                    if (++i < section.length()) {
                        literalPx += charPx(section.charAt(i));
                    }
                }
                case '*' -> i++;
                case '[' -> {
                    int end = section.indexOf(']', i + 1);
                    String inner = end < 0 ? section.substring(i + 1) : section.substring(i + 1, end);
                    i = end < 0 ? section.length() : end;
                    if (inner.startsWith("$")) {
                        int dash = inner.indexOf('-');
                        literalPx += textPx(dash < 0 ? inner.substring(1) : inner.substring(1, dash));
                    } else if (inner.toLowerCase(Locale.ROOT).chars().allMatch(ch -> ch == 'h' || ch == 'm' || ch == 's')) {
                        tokens.append("hhh"); // elapsed time: allow three digits
                    }
                }
                default -> tokens.append(c);
            }
        }

        String t = tokens.toString();
        boolean numeric = count(t, "0#?") > 0;
        String lower = t.toLowerCase(Locale.ROOT);
        if (!numeric && count(lower, "ymdhs") > 0) {
            return Shape.constant(literalPx + datePx(lower));
        }
        if (!numeric) {
            return Shape.GENERAL;
        }
        if (lower.contains("e+") || lower.contains("e-")) {
            return Shape.constant(literalPx + count(t, "0#?") * DIGIT_PX + textPx(".E+"));
        }

        int dot = t.indexOf('.');
        String integer = dot < 0 ? t : t.substring(0, dot);
        String fraction = dot < 0 ? "" : t.substring(dot + 1);
        float others = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("0#?,.%".indexOf(c) < 0) {
                others += charPx(c);
            }
        }
        return Shape.fixed(Math.min(count(fraction, "0#?"), 30), Math.min(count(integer, "0?"), 30), integer.indexOf(',') >= 0,
                Math.min(count(t, "%"), 2), literalPx + others);
    }

    private static int count(String text, String characters) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                count++;
            }
        }
        return count;
    }

    /** Width of a date/time format, with each token at its widest rendering. */
    private static float datePx(String tokens) {
        boolean amPm = tokens.contains("am/pm");
        float px = 0;
        for (int i = 0; i < tokens.length(); ) {
            char c = tokens.charAt(i);
            if (amPm && c == 'a') {
                // "am/pm" renders as "PM"
                i += 5;
                px += textPx("PM");
                continue;
            }
            int run = 1;
            while (i + run < tokens.length() && tokens.charAt(i + run) == c) {
                run++;
            }
            i += run;
            px += switch (c) {
                case 'y' -> (run <= 2 ? 2 : 4) * DIGIT_PX;
                case 'm' -> run == 3 ? textPx("Sep") : run >= 4 ? textPx("September") : 2 * DIGIT_PX;
                case 'd' -> run == 3 ? textPx("Wed") : run >= 4 ? textPx("Wednesday") : 2 * DIGIT_PX;
                case 's' -> 2 * DIGIT_PX;
                case 'h' -> Math.max(run, 2) * DIGIT_PX;
                case '0' -> run * DIGIT_PX;
                default -> run * charPx(c);
            };
        }
        return px;
    }

    enum ShapeKind {
        /** General (or text) format: up to 11 characters of the shortest representation. */
        GENERAL,
        /** A fixed-width rendering such as a date or scientific notation, {@link Shape#literalPx} wide. */
        CONSTANT,
        FIXED
    }

    /** How a style renders numbers, reduced to what matters for width. */
    record Shape(ShapeKind kind, int decimals, int minIntegerDigits, boolean thousands, int percent, float literalPx) {
        static final Shape GENERAL = new Shape(ShapeKind.GENERAL, 0, 0, false, 0, 0);

        static Shape constant(float px) {
            return new Shape(ShapeKind.CONSTANT, 0, 0, false, 0, px);
        }

        static Shape fixed(int decimals, int minIntegerDigits, boolean thousands, int percent, float literalPx) {
            return new Shape(ShapeKind.FIXED, decimals, minIntegerDigits, thousands, percent, literalPx);
        }
    }

    private record StyleFit(float scale, Shape shape) {
    }
}
