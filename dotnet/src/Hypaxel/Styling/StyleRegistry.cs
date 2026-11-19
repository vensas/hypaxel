using Hypaxel.Internal;

namespace Hypaxel.Styling;

/// <summary>Built-in number format ids that every spreadsheet application knows without a definition.</summary>
internal static class BuiltInNumberFormat
{
    public const int General = 0;
    public const int Date = 14;          // short date, follows the viewer's locale
    public const int Time = 21;          // h:mm:ss
    public const int DateTime = 22;      // short date + time, follows the viewer's locale
    public const int Duration = 46;      // [h]:mm:ss

    public static readonly Dictionary<string, int> ByCode = new(StringComparer.Ordinal)
    {
        ["General"] = 0,
        ["0"] = 1,
        ["0.00"] = 2,
        ["#,##0"] = 3,
        ["#,##0.00"] = 4,
        ["0%"] = 9,
        ["0.00%"] = 10,
        ["0.00E+00"] = 11,
        ["@"] = 49,
    };
}

internal enum ValueFormatKind
{
    Date,
    DateTime,
    Time,
    Duration,
}

/// <summary>
/// Collects fonts, fills, borders, number formats and cell formats (<c>xf</c>) while the workbook is
/// written, and serializes them to <c>xl/styles.xml</c> at the end.
/// </summary>
internal sealed class StyleRegistry
{
    private readonly record struct Xf(int NumFmtId, int FontId, int FillId, int BorderId, Alignment? Alignment);

    private static readonly Font s_defaultFont = new();

    private readonly List<Font> _fonts = [s_defaultFont];
    private readonly Dictionary<Font, int> _fontIds = new() { [s_defaultFont] = 0 };
    private readonly List<Fill?> _fills = [null, null]; // 0 = none, 1 = gray125 (both mandatory)
    private readonly Dictionary<Fill, int> _fillIds = [];
    private readonly List<Border?> _borders = [null];
    private readonly Dictionary<Border, int> _borderIds = [];
    private readonly List<string> _customNumFmts = [];
    private readonly Dictionary<string, int> _numFmtIds = new(StringComparer.Ordinal);
    private readonly List<Xf> _xfs = [new Xf(0, 0, 0, 0, null)];
    private readonly Dictionary<Xf, int> _xfIds = [];
    private readonly Dictionary<CellStyle, StyleId> _styleIds = [];
    private readonly Dictionary<(int Style, ValueFormatKind Kind), StyleId> _variants = [];

    // Fast path for the overwhelmingly common case of unstyled date/time cells.
    private readonly int[] _defaultVariants = new int[4];

    public StyleRegistry()
    {
        _xfIds[_xfs[0]] = 0;
    }

    public int Count => _xfs.Count;

    public StyleId Add(CellStyle style)
    {
        ArgumentNullException.ThrowIfNull(style);
        if (_styleIds.TryGetValue(style, out var existing))
        {
            return existing;
        }

        var xf = new Xf(
            style.NumberFormat is null ? 0 : GetNumFmtId(style.NumberFormat),
            style.Font is null ? 0 : GetId(style.Font, _fonts, _fontIds),
            style.Fill is null ? 0 : GetId(style.Fill, _fills, _fillIds),
            style.Border is null ? 0 : GetId(style.Border, _borders, _borderIds),
            style.Alignment);

        var id = new StyleId(GetXfId(xf));
        _styleIds[style] = id;
        return id;
    }

    /// <summary>
    /// Returns a format that renders a date/time value: the style itself when it already carries a
    /// number format, otherwise a variant of it with the matching built-in date/time format.
    /// </summary>
    public int GetValueFormatVariant(int styleIndex, ValueFormatKind kind)
    {
        if (styleIndex == 0)
        {
            var cached = _defaultVariants[(int)kind];
            if (cached != 0)
            {
                return cached;
            }
        }

        var xf = _xfs[styleIndex];
        if (xf.NumFmtId != 0)
        {
            return styleIndex;
        }

        if (_variants.TryGetValue((styleIndex, kind), out var variant))
        {
            return variant.Index;
        }

        var numFmt = kind switch
        {
            ValueFormatKind.Date => BuiltInNumberFormat.Date,
            ValueFormatKind.DateTime => BuiltInNumberFormat.DateTime,
            ValueFormatKind.Time => BuiltInNumberFormat.Time,
            _ => BuiltInNumberFormat.Duration,
        };

        var index = GetXfId(xf with { NumFmtId = numFmt });
        _variants[(styleIndex, kind)] = new StyleId(index);
        if (styleIndex == 0)
        {
            _defaultVariants[(int)kind] = index;
        }

        return index;
    }

    /// <summary>What column auto-fit needs to know about a format: font size, boldness and number format.</summary>
    public (double FontSize, bool Bold, int NumFmtId, string? CustomFormat) GetMeasureInfo(int styleIndex)
    {
        var xf = _xfs[styleIndex];
        var font = _fonts[xf.FontId];
        var custom = xf.NumFmtId >= 164 ? _customNumFmts[xf.NumFmtId - 164] : null;
        return (font.Size, font.Bold, xf.NumFmtId, custom);
    }

    public void Validate(StyleId style)
    {
        if ((uint)style.Index >= (uint)_xfs.Count)
        {
            throw new ArgumentException("The style id does not belong to this writer.", nameof(style));
        }
    }

    private int GetXfId(Xf xf)
    {
        if (!_xfIds.TryGetValue(xf, out var id))
        {
            id = _xfs.Count;
            _xfs.Add(xf);
            _xfIds[xf] = id;
        }

        return id;
    }

    private int GetNumFmtId(string code)
    {
        if (BuiltInNumberFormat.ByCode.TryGetValue(code, out var builtIn))
        {
            return builtIn;
        }

        if (!_numFmtIds.TryGetValue(code, out var id))
        {
            id = 164 + _customNumFmts.Count; // custom ids start at 164
            _customNumFmts.Add(code);
            _numFmtIds[code] = id;
        }

        return id;
    }

    private static int GetId<T>(T value, List<T?> list, Dictionary<T, int> ids)
        where T : class
    {
        if (!ids.TryGetValue(value, out var id))
        {
            id = list.Count;
            list.Add(value);
            ids[value] = id;
        }

        return id;
    }

    private static int GetId(Font value, List<Font> list, Dictionary<Font, int> ids)
    {
        if (!ids.TryGetValue(value, out var id))
        {
            id = list.Count;
            list.Add(value);
            ids[value] = id;
        }

        return id;
    }

    public void WriteTo(Utf8XmlBuffer x)
    {
        x.WriteRaw(Xml.Declaration);
        x.WriteRaw("<styleSheet xmlns=\""u8);
        x.WriteRaw(Xml.SpreadsheetMlNamespace);
        x.WriteRaw("\">"u8);

        if (_customNumFmts.Count > 0)
        {
            x.WriteRaw("<numFmts count=\""u8);
            x.Write(_customNumFmts.Count);
            x.WriteRaw("\">"u8);
            for (var i = 0; i < _customNumFmts.Count; i++)
            {
                x.WriteRaw("<numFmt numFmtId=\""u8);
                x.Write(164 + i);
                x.WriteRaw("\" formatCode=\""u8);
                x.WriteEscaped(_customNumFmts[i]);
                x.WriteRaw("\"/>"u8);
            }

            x.WriteRaw("</numFmts>"u8);
        }

        x.WriteRaw("<fonts count=\""u8);
        x.Write(_fonts.Count);
        x.WriteRaw("\">"u8);
        foreach (var font in _fonts)
        {
            x.WriteRaw("<font>"u8);
            if (font.Bold)
            {
                x.WriteRaw("<b/>"u8);
            }

            if (font.Italic)
            {
                x.WriteRaw("<i/>"u8);
            }

            if (font.Strikethrough)
            {
                x.WriteRaw("<strike/>"u8);
            }

            if (font.Underline)
            {
                x.WriteRaw("<u/>"u8);
            }

            x.WriteRaw("<sz val=\""u8);
            x.Write(font.Size);
            x.WriteRaw("\"/>"u8);
            if (font.Color is { } color)
            {
                WriteColor(x, "color"u8, color);
            }

            // No theme part is emitted, so fonts never reference theme colors or schemes.
            x.WriteRaw("<name val=\""u8);
            x.WriteEscaped(font.Name);
            x.WriteRaw("\"/><family val=\"2\"/>"u8);

            x.WriteRaw("</font>"u8);
        }

        x.WriteRaw("</fonts><fills count=\""u8);
        x.Write(_fills.Count);
        x.WriteRaw("\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"u8);
        for (var i = 2; i < _fills.Count; i++)
        {
            x.WriteRaw("<fill><patternFill patternType=\"solid\">"u8);
            WriteColor(x, "fgColor"u8, _fills[i]!.Color);
            x.WriteRaw("<bgColor indexed=\"64\"/></patternFill></fill>"u8);
        }

        x.WriteRaw("</fills><borders count=\""u8);
        x.Write(_borders.Count);
        x.WriteRaw("\">"u8);
        foreach (var border in _borders)
        {
            if (border is null)
            {
                x.WriteRaw("<border><left/><right/><top/><bottom/><diagonal/></border>"u8);
                continue;
            }

            x.WriteRaw("<border>"u8);
            WriteBorderLine(x, "left"u8, border.Left);
            WriteBorderLine(x, "right"u8, border.Right);
            WriteBorderLine(x, "top"u8, border.Top);
            WriteBorderLine(x, "bottom"u8, border.Bottom);
            x.WriteRaw("<diagonal/></border>"u8);
        }

        x.WriteRaw("</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\""u8);
        x.Write(_xfs.Count);
        x.WriteRaw("\">"u8);
        foreach (var xf in _xfs)
        {
            x.WriteRaw("<xf numFmtId=\""u8);
            x.Write(xf.NumFmtId);
            x.WriteRaw("\" fontId=\""u8);
            x.Write(xf.FontId);
            x.WriteRaw("\" fillId=\""u8);
            x.Write(xf.FillId);
            x.WriteRaw("\" borderId=\""u8);
            x.Write(xf.BorderId);
            x.WriteRaw("\" xfId=\"0\""u8);
            if (xf.NumFmtId != 0)
            {
                x.WriteRaw(" applyNumberFormat=\"1\""u8);
            }

            if (xf.FontId != 0)
            {
                x.WriteRaw(" applyFont=\"1\""u8);
            }

            if (xf.FillId != 0)
            {
                x.WriteRaw(" applyFill=\"1\""u8);
            }

            if (xf.BorderId != 0)
            {
                x.WriteRaw(" applyBorder=\"1\""u8);
            }

            if (xf.Alignment is { } a)
            {
                x.WriteRaw(" applyAlignment=\"1\"><alignment"u8);
                if (a.Horizontal != HorizontalAlignment.General)
                {
                    x.WriteRaw(" horizontal=\""u8);
                    x.WriteRaw(HorizontalName(a.Horizontal));
                    x.WriteByte((byte)'"');
                }

                if (a.Vertical != VerticalAlignment.Bottom)
                {
                    x.WriteRaw(" vertical=\""u8);
                    x.WriteRaw(VerticalName(a.Vertical));
                    x.WriteByte((byte)'"');
                }

                if (a.TextRotation != 0)
                {
                    x.WriteRaw(" textRotation=\""u8);
                    x.Write(a.TextRotation);
                    x.WriteByte((byte)'"');
                }

                if (a.WrapText)
                {
                    x.WriteRaw(" wrapText=\"1\""u8);
                }

                if (a.Indent != 0)
                {
                    x.WriteRaw(" indent=\""u8);
                    x.Write(a.Indent);
                    x.WriteByte((byte)'"');
                }

                if (a.ShrinkToFit)
                {
                    x.WriteRaw(" shrinkToFit=\"1\""u8);
                }

                x.WriteRaw("/></xf>"u8);
            }
            else
            {
                x.WriteRaw("/>"u8);
            }
        }

        x.WriteRaw("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles><dxfs count=\"0\"/><tableStyles count=\"0\"/></styleSheet>"u8);
    }

    private static void WriteColor(Utf8XmlBuffer x, ReadOnlySpan<byte> element, ExcelColor color)
    {
        x.WriteByte((byte)'<');
        x.WriteRaw(element);
        x.WriteRaw(" rgb=\""u8);
        x.WriteRaw(System.Text.Encoding.ASCII.GetBytes(color.ToArgbHex()));
        x.WriteRaw("\"/>"u8);
    }

    private static void WriteBorderLine(Utf8XmlBuffer x, ReadOnlySpan<byte> element, BorderLine line)
    {
        x.WriteByte((byte)'<');
        x.WriteRaw(element);
        if (line.Style == BorderLineStyle.None)
        {
            x.WriteRaw("/>"u8);
            return;
        }

        x.WriteRaw(" style=\""u8);
        x.WriteRaw(BorderStyleName(line.Style));
        x.WriteRaw("\">"u8);
        if (line.Color is { } color)
        {
            WriteColor(x, "color"u8, color);
        }
        else
        {
            x.WriteRaw("<color indexed=\"64\"/>"u8);
        }

        x.WriteRaw("</"u8);
        x.WriteRaw(element);
        x.WriteByte((byte)'>');
    }

    private static ReadOnlySpan<byte> BorderStyleName(BorderLineStyle style) => style switch
    {
        BorderLineStyle.Thin => "thin"u8,
        BorderLineStyle.Medium => "medium"u8,
        BorderLineStyle.Dashed => "dashed"u8,
        BorderLineStyle.Dotted => "dotted"u8,
        BorderLineStyle.Thick => "thick"u8,
        BorderLineStyle.Double => "double"u8,
        BorderLineStyle.Hair => "hair"u8,
        BorderLineStyle.MediumDashed => "mediumDashed"u8,
        BorderLineStyle.DashDot => "dashDot"u8,
        BorderLineStyle.MediumDashDot => "mediumDashDot"u8,
        BorderLineStyle.DashDotDot => "dashDotDot"u8,
        BorderLineStyle.MediumDashDotDot => "mediumDashDotDot"u8,
        BorderLineStyle.SlantDashDot => "slantDashDot"u8,
        _ => throw new ArgumentOutOfRangeException(nameof(style)),
    };

    private static ReadOnlySpan<byte> HorizontalName(HorizontalAlignment value) => value switch
    {
        HorizontalAlignment.Left => "left"u8,
        HorizontalAlignment.Center => "center"u8,
        HorizontalAlignment.Right => "right"u8,
        HorizontalAlignment.Fill => "fill"u8,
        HorizontalAlignment.Justify => "justify"u8,
        HorizontalAlignment.CenterContinuous => "centerContinuous"u8,
        HorizontalAlignment.Distributed => "distributed"u8,
        _ => "general"u8,
    };

    private static ReadOnlySpan<byte> VerticalName(VerticalAlignment value) => value switch
    {
        VerticalAlignment.Top => "top"u8,
        VerticalAlignment.Center => "center"u8,
        VerticalAlignment.Justify => "justify"u8,
        VerticalAlignment.Distributed => "distributed"u8,
        _ => "bottom"u8,
    };
}
