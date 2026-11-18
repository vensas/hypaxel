using Hypaxel.Styling;

namespace Hypaxel;

/// <summary>Kind of value held by a <see cref="CellValue"/>.</summary>
public enum CellValueKind : byte
{
    /// <summary>No value (an empty cell; written only when styled).</summary>
    Empty,
#pragma warning disable CS1591
    String,
    Number,
    Integer,
    Boolean,
    DateTime,
    Date,
    Time,
    Duration,
    Formula,
#pragma warning restore CS1591
}

/// <summary>
/// A cell value plus optional style, without boxing. Implicit conversions exist from the common CLR
/// types, so rows can be written as <c>sheet.AddRow("Name", 42, 3.14, DateTime.Now)</c>.
/// </summary>
public readonly struct CellValue
{
    internal readonly string? Text;
    internal readonly double Number;
    internal readonly CellValueKind KindValue;
    internal readonly StyleId StyleValue;

    private CellValue(CellValueKind kind, string? text, double number, StyleId style)
    {
        KindValue = kind;
        Text = text;
        Number = number;
        StyleValue = style;
    }

    /// <summary>The kind of value.</summary>
    public CellValueKind Kind => KindValue;

    /// <summary>The cell style.</summary>
    public StyleId Style => StyleValue;

    /// <summary>An empty cell.</summary>
    public static CellValue Empty => default;

    /// <summary>Returns a copy of this value with the given style.</summary>
    public CellValue WithStyle(StyleId style) => new(KindValue, Text, Number, style);

    /// <summary>Creates a formula cell (without the leading '=').</summary>
    public static CellValue Formula(string formula, StyleId style = default)
    {
        ArgumentNullException.ThrowIfNull(formula);
        return new(CellValueKind.Formula, formula.StartsWith('=') ? formula[1..] : formula, 0, style);
    }

    /// <summary>Creates a styled empty cell.</summary>
    public static CellValue Blank(StyleId style) => new(CellValueKind.Empty, null, 0, style);

#pragma warning disable CS1591
    public static CellValue From(string? value, StyleId style = default) =>
        value is null ? new(CellValueKind.Empty, null, 0, style) : new(CellValueKind.String, value, 0, style);

    public static CellValue From(double value, StyleId style = default) => new(CellValueKind.Number, null, value, style);
    public static CellValue From(long value, StyleId style = default) => new(CellValueKind.Integer, null, BitConverter.Int64BitsToDouble(value), style);
    public static CellValue From(int value, StyleId style = default) => From((long)value, style);
    public static CellValue From(decimal value, StyleId style = default) => new(CellValueKind.Number, null, (double)value, style);
    public static CellValue From(bool value, StyleId style = default) => new(CellValueKind.Boolean, null, value ? 1 : 0, style);
    public static CellValue From(DateTime value, StyleId style = default) => new(CellValueKind.DateTime, null, BitConverter.Int64BitsToDouble(value.Ticks), style);
    public static CellValue From(DateOnly value, StyleId style = default) => From(value.ToDateTime(TimeOnly.MinValue), style).WithKind(CellValueKind.Date);
    public static CellValue From(TimeOnly value, StyleId style = default) => new(CellValueKind.Time, null, BitConverter.Int64BitsToDouble(value.Ticks), style);
    public static CellValue From(TimeSpan value, StyleId style = default) => new(CellValueKind.Duration, null, BitConverter.Int64BitsToDouble(value.Ticks), style);

    public static implicit operator CellValue(string? value) => From(value);
    public static implicit operator CellValue(int value) => From((long)value);
    public static implicit operator CellValue(long value) => From(value);
    public static implicit operator CellValue(float value) => From((double)value);
    public static implicit operator CellValue(double value) => From(value);
    public static implicit operator CellValue(decimal value) => From(value);
    public static implicit operator CellValue(bool value) => From(value);
    public static implicit operator CellValue(DateTime value) => From(value);
    public static implicit operator CellValue(DateTimeOffset value) => From(value.DateTime);
    public static implicit operator CellValue(DateOnly value) => From(value);
    public static implicit operator CellValue(TimeOnly value) => From(value);
    public static implicit operator CellValue(TimeSpan value) => From(value);
#pragma warning restore CS1591

    private CellValue WithKind(CellValueKind kind) => new(kind, Text, Number, StyleValue);

    internal long Bits => BitConverter.DoubleToInt64Bits(Number);
}
