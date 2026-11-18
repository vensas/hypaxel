package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.Alignment;
import de.vensas.hypaxel.Border;
import de.vensas.hypaxel.BorderLine;
import de.vensas.hypaxel.BorderStyle;
import de.vensas.hypaxel.Color;
import de.vensas.hypaxel.Font;
import de.vensas.hypaxel.HorizontalAlignment;
import de.vensas.hypaxel.Style;
import de.vensas.hypaxel.VerticalAlignment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** De-duplicates cell formats and their fonts, fills, borders and number formats; writes styles.xml. */
public final class StyleRegistry {
    private static final int FIRST_CUSTOM_NUMBER_FORMAT = 164;
    private static final int KINDS = ValueKind.values().length;
    private static final Map<String, Integer> BUILTIN_NUMBER_FORMATS = Map.of(
            "General", 0,
            "0", 1,
            "0.00", 2,
            "#,##0", 3,
            "#,##0.00", 4,
            "0%", 9,
            "0.00%", 10,
            "0.00E+00", 11,
            "@", 49);

    private record Xf(int numberFormat, int font, int fill, int border, Alignment alignment) {
    }

    private final List<Font> fonts = new ArrayList<>();
    private final Map<Font, Integer> fontIds = new HashMap<>();
    private final List<Color> fills = new ArrayList<>();
    private final Map<Color, Integer> fillIds = new HashMap<>();
    private final List<Border> borders = new ArrayList<>();
    private final Map<Border, Integer> borderIds = new HashMap<>();
    private final List<String> numberFormats = new ArrayList<>();
    private final Map<String, Integer> numberFormatIds = new HashMap<>();
    private final List<Xf> xfs = new ArrayList<>();
    private final Map<Xf, Integer> xfIds = new HashMap<>();
    private final Map<Style, Integer> styleIds = new HashMap<>();
    /** Format id per (style, kind), 0 while unknown. Variants always have a number format, so never id 0. */
    private int[] variants = new int[16 * KINDS];
    private int size;

    public StyleRegistry() {
        fonts.add(Font.DEFAULT);
        fontIds.put(Font.DEFAULT, 0);
        xfId(new Xf(0, 0, 0, 0, null));
    }

    /** Number of registered cell formats; valid style ids are below it. */
    public int size() {
        return size;
    }

    public int add(Style style) {
        Integer known = styleIds.get(style);
        if (known != null) {
            return known;
        }
        int numberFormat = style.numberFormat() == null ? 0 : numberFormatId(style.numberFormat());
        int font = 0;
        if (style.font() != null) {
            font = fontIds.computeIfAbsent(style.font(), f -> {
                fonts.add(f);
                return fonts.size() - 1;
            });
        }
        int fill = 0;
        if (style.fill() != null) {
            // Ids 0 and 1 are the two fills every workbook must contain.
            fill = fillIds.computeIfAbsent(style.fill(), c -> {
                fills.add(c);
                return fills.size() + 1;
            });
        }
        int border = 0;
        if (style.border() != null) {
            border = borderIds.computeIfAbsent(style.border(), b -> {
                borders.add(b);
                return borders.size();
            });
        }
        int id = xfId(new Xf(numberFormat, font, fill, border, style.alignment()));
        styleIds.put(style, id);
        return id;
    }

    /**
     * Returns a format able to display a temporal value: the style itself when it has a number
     * format, otherwise a variant of it with the kind's built-in format.
     */
    public int valueVariant(int style, ValueKind kind) {
        int slot = style * KINDS + kind.ordinal();
        if (slot < variants.length) {
            int cached = variants[slot];
            if (cached != 0) {
                return cached;
            }
        }
        return resolveVariant(style, kind, slot);
    }

    private int resolveVariant(int style, ValueKind kind, int slot) {
        Xf xf = xfs.get(style);
        int id = xf.numberFormat != 0 ? style : xfId(new Xf(kind.builtinFormat(), xf.font, xf.fill, xf.border, xf.alignment));
        if (id != 0) {
            if (slot >= variants.length) {
                variants = Arrays.copyOf(variants, Math.max(slot + 1, variants.length * 2));
            }
            variants[slot] = id;
        }
        return id;
    }

    private int xfId(Xf xf) {
        Integer known = xfIds.get(xf);
        if (known != null) {
            return known;
        }
        int id = xfs.size();
        xfs.add(xf);
        xfIds.put(xf, id);
        size = xfs.size();
        return id;
    }

    private int numberFormatId(String code) {
        Integer builtin = BUILTIN_NUMBER_FORMATS.get(code);
        if (builtin != null) {
            return builtin;
        }
        return numberFormatIds.computeIfAbsent(code, c -> {
            numberFormats.add(c);
            return FIRST_CUSTOM_NUMBER_FORMAT + numberFormats.size() - 1;
        });
    }

    public void writeXml(XmlBuffer x) {
        x.raw(WorkbookParts.DECLARATION).raw("<styleSheet xmlns=\"").raw(WorkbookParts.NS_MAIN).raw("\">");

        if (!numberFormats.isEmpty()) {
            x.raw("<numFmts count=\"").putLong(numberFormats.size()).raw("\">");
            for (int i = 0; i < numberFormats.size(); i++) {
                x.raw("<numFmt numFmtId=\"").putLong(FIRST_CUSTOM_NUMBER_FORMAT + i).raw("\" formatCode=\"");
                x.putText(numberFormats.get(i)).raw("\"/>");
            }
            x.raw("</numFmts>");
        }

        x.raw("<fonts count=\"").putLong(fonts.size()).raw("\">");
        for (Font font : fonts) {
            writeFont(x, font);
        }

        x.raw("</fonts><fills count=\"").putLong(fills.size() + 2);
        x.raw("\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>");
        for (Color color : fills) {
            x.raw("<fill><patternFill patternType=\"solid\">");
            writeColor(x, "fgColor", color);
            x.raw("<bgColor indexed=\"64\"/></patternFill></fill>");
        }

        x.raw("</fills><borders count=\"").putLong(borders.size() + 1);
        x.raw("\"><border><left/><right/><top/><bottom/><diagonal/></border>");
        for (Border border : borders) {
            x.raw("<border>");
            writeBorderLine(x, "left", border.left());
            writeBorderLine(x, "right", border.right());
            writeBorderLine(x, "top", border.top());
            writeBorderLine(x, "bottom", border.bottom());
            x.raw("<diagonal/></border>");
        }

        x.raw("</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        x.raw("<cellXfs count=\"").putLong(xfs.size()).raw("\">");
        for (Xf xf : xfs) {
            writeXf(x, xf);
        }
        x.raw("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>");
        x.raw("<dxfs count=\"0\"/><tableStyles count=\"0\"/></styleSheet>");
    }

    private static void writeFont(XmlBuffer x, Font font) {
        x.raw("<font>");
        if (font.bold()) {
            x.raw("<b/>");
        }
        if (font.italic()) {
            x.raw("<i/>");
        }
        if (font.strikethrough()) {
            x.raw("<strike/>");
        }
        if (font.underline()) {
            x.raw("<u/>");
        }
        x.raw("<sz val=\"").putDouble(font.size()).raw("\"/>");
        if (font.color() != null) {
            writeColor(x, "color", font.color());
        }
        // No theme part is written, so fonts never reference theme colors or schemes.
        x.raw("<name val=\"").putText(font.name()).raw("\"/><family val=\"2\"/></font>");
    }

    private static void writeXf(XmlBuffer x, Xf xf) {
        x.raw("<xf numFmtId=\"").putLong(xf.numberFormat);
        x.raw("\" fontId=\"").putLong(xf.font);
        x.raw("\" fillId=\"").putLong(xf.fill);
        x.raw("\" borderId=\"").putLong(xf.border).raw("\" xfId=\"0\"");
        if (xf.numberFormat != 0) {
            x.raw(" applyNumberFormat=\"1\"");
        }
        if (xf.font != 0) {
            x.raw(" applyFont=\"1\"");
        }
        if (xf.fill != 0) {
            x.raw(" applyFill=\"1\"");
        }
        if (xf.border != 0) {
            x.raw(" applyBorder=\"1\"");
        }
        Alignment a = xf.alignment;
        if (a == null) {
            x.raw("/>");
            return;
        }
        x.raw(" applyAlignment=\"1\"><alignment");
        if (a.horizontal() != HorizontalAlignment.GENERAL) {
            x.raw(" horizontal=\"").raw(a.horizontal().xmlName()).raw('"');
        }
        if (a.vertical() != VerticalAlignment.BOTTOM) {
            x.raw(" vertical=\"").raw(a.vertical().xmlName()).raw('"');
        }
        if (a.rotation() != 0) {
            x.raw(" textRotation=\"").putLong(a.rotation()).raw('"');
        }
        if (a.wrapText()) {
            x.raw(" wrapText=\"1\"");
        }
        if (a.indent() != 0) {
            x.raw(" indent=\"").putLong(a.indent()).raw('"');
        }
        if (a.shrinkToFit()) {
            x.raw(" shrinkToFit=\"1\"");
        }
        x.raw("/></xf>");
    }

    private static void writeColor(XmlBuffer x, String element, Color color) {
        x.raw('<').raw(element).raw(" rgb=\"").putArgb(color.argb()).raw("\"/>");
    }

    private static void writeBorderLine(XmlBuffer x, String element, BorderLine line) {
        x.raw('<').raw(element);
        if (line.style() == BorderStyle.NONE) {
            x.raw("/>");
            return;
        }
        x.raw(" style=\"").raw(line.style().xmlName()).raw("\">");
        if (line.color() != null) {
            writeColor(x, "color", line.color());
        } else {
            x.raw("<color indexed=\"64\"/>");
        }
        x.raw("</").raw(element).raw('>');
    }
}
