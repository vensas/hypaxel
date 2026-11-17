using System.Globalization;

namespace Hypaxel.Styling;

/// <summary>
/// Handle to a cell format registered with <see cref="XlsxWriter.AddStyle(CellStyle)"/>.
/// Handles are only valid for the writer that created them.
/// </summary>
public readonly record struct StyleId
{
    internal StyleId(int index) => Index = index;

    /// <summary>The workbook's default ("Normal") format.</summary>
    public static StyleId Default => default;

    /// <summary>Zero-based index into the workbook's <c>cellXfs</c> table.</summary>
    public int Index { get; }

    /// <summary>True when this is the default format.</summary>
    public bool IsDefault => Index == 0;
}

/// <summary>An ARGB color.</summary>
public readonly record struct ExcelColor(byte A, byte R, byte G, byte B)
{
    /// <summary>Creates an opaque color.</summary>
    public static ExcelColor FromRgb(byte r, byte g, byte b) => new(255, r, g, b);

    /// <summary>Parses "#RRGGBB", "RRGGBB", "#AARRGGBB" or "AARRGGBB".</summary>
    public static ExcelColor FromHex(string hex)
    {
        ArgumentNullException.ThrowIfNull(hex);
        var s = hex.AsSpan().TrimStart('#');
        if (s.Length is not (6 or 8) || !uint.TryParse(s, NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out var v))
        {
            throw new FormatException($"'{hex}' is not a valid hex color. Expected #RRGGBB or #AARRGGBB.");
        }

        if (s.Length == 6)
        {
            v |= 0xFF000000;
        }

        return new ExcelColor((byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v);
    }

    /// <summary>Returns the color as an "AARRGGBB" string, the format SpreadsheetML uses.</summary>
    public string ToArgbHex() => string.Create(CultureInfo.InvariantCulture, $"{A:X2}{R:X2}{G:X2}{B:X2}");

    /// <inheritdoc />
    public override string ToString() => "#" + ToArgbHex();

#pragma warning disable CS1591
    public static ExcelColor Black => FromRgb(0, 0, 0);
    public static ExcelColor White => FromRgb(255, 255, 255);
    public static ExcelColor Red => FromRgb(255, 0, 0);
    public static ExcelColor Green => FromRgb(0, 128, 0);
    public static ExcelColor Blue => FromRgb(0, 0, 255);
    public static ExcelColor Yellow => FromRgb(255, 255, 0);
    public static ExcelColor LightGray => FromRgb(217, 217, 217);
    public static ExcelColor DarkGray => FromRgb(89, 89, 89);
#pragma warning restore CS1591
}

/// <summary>Font settings. Unspecified properties use Excel's defaults (Calibri 11).</summary>
public sealed record Font
{
#pragma warning disable CS1591
    public string Name { get; init; } = "Calibri";
    public double Size { get; init; } = 11;
    public bool Bold { get; init; }
    public bool Italic { get; init; }
    public bool Underline { get; init; }
    public bool Strikethrough { get; init; }
    public ExcelColor? Color { get; init; }
#pragma warning restore CS1591
}

/// <summary>A solid background fill.</summary>
public sealed record Fill
{
    /// <summary>Creates a solid fill with the given color.</summary>
    public Fill(ExcelColor color) => Color = color;

    /// <summary>The fill color.</summary>
    public ExcelColor Color { get; init; }
}

/// <summary>Border line styles.</summary>
public enum BorderLineStyle
{
#pragma warning disable CS1591
    None,
    Thin,
    Medium,
    Dashed,
    Dotted,
    Thick,
    Double,
    Hair,
    MediumDashed,
    DashDot,
    MediumDashDot,
    DashDotDot,
    MediumDashDotDot,
    SlantDashDot,
#pragma warning restore CS1591
}

/// <summary>One edge of a cell border.</summary>
public readonly record struct BorderLine(BorderLineStyle Style, ExcelColor? Color = null);

/// <summary>Cell border settings.</summary>
public sealed record Border
{
#pragma warning disable CS1591
    public BorderLine Left { get; init; }
    public BorderLine Right { get; init; }
    public BorderLine Top { get; init; }
    public BorderLine Bottom { get; init; }
#pragma warning restore CS1591

    /// <summary>Creates a border with the same line on all four edges.</summary>
    public static Border All(BorderLineStyle style, ExcelColor? color = null)
    {
        var line = new BorderLine(style, color);
        return new Border { Left = line, Right = line, Top = line, Bottom = line };
    }
}

/// <summary>Horizontal alignment.</summary>
public enum HorizontalAlignment
{
#pragma warning disable CS1591
    General,
    Left,
    Center,
    Right,
    Fill,
    Justify,
    CenterContinuous,
    Distributed,
#pragma warning restore CS1591
}

/// <summary>Vertical alignment.</summary>
public enum VerticalAlignment
{
#pragma warning disable CS1591
    Bottom,
    Top,
    Center,
    Justify,
    Distributed,
#pragma warning restore CS1591
}

/// <summary>Text alignment settings.</summary>
public sealed record Alignment
{
#pragma warning disable CS1591
    public HorizontalAlignment Horizontal { get; init; }
    public VerticalAlignment Vertical { get; init; }
    public bool WrapText { get; init; }
    public bool ShrinkToFit { get; init; }
    public int Indent { get; init; }

    /// <summary>0–180 degrees, or 255 for vertical text.</summary>
    public int TextRotation { get; init; }
#pragma warning restore CS1591
}

/// <summary>
/// A complete cell format. Register it once with <see cref="XlsxWriter.AddStyle(CellStyle)"/> and reuse
/// the returned <see cref="StyleId"/>; identical styles are de-duplicated.
/// </summary>
public sealed record CellStyle
{
    /// <summary>Excel number format code, e.g. "0.00", "#,##0", "yyyy-mm-dd", "@" (text).</summary>
    public string? NumberFormat { get; init; }

    /// <summary>Font, or null for the default font.</summary>
    public Font? Font { get; init; }

    /// <summary>Background fill, or null for none.</summary>
    public Fill? Fill { get; init; }

    /// <summary>Border, or null for none.</summary>
    public Border? Border { get; init; }

    /// <summary>Alignment, or null for the default.</summary>
    public Alignment? Alignment { get; init; }
}

/// <summary>Commonly used number format codes.</summary>
public static class NumberFormats
{
#pragma warning disable CS1591
    public const string General = "General";
    public const string Integer = "0";
    public const string Decimal2 = "0.00";
    public const string Thousands = "#,##0";
    public const string Thousands2 = "#,##0.00";
    public const string Percent = "0%";
    public const string Percent2 = "0.00%";
    public const string Scientific = "0.00E+00";
    public const string Text = "@";
    public const string Date = "yyyy-mm-dd";
    public const string DateTime = "yyyy-mm-dd hh:mm:ss";
    public const string Time = "hh:mm:ss";
    public const string Duration = "[h]:mm:ss";
#pragma warning restore CS1591
}
