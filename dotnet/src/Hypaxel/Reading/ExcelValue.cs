using System.Globalization;

namespace Hypaxel;

/// <summary>Kind of value held by an <see cref="ExcelValue"/>.</summary>
public enum ExcelValueKind : byte
{
    /// <summary>No value: a missing cell, a cell that only carries formatting, or a formula without a cached result.</summary>
    Empty,

    /// <summary>Text: a shared string, an inline string or the cached result of a text formula.</summary>
    String,

    /// <summary>A number that is not formatted as a date or time.</summary>
    Number,

    /// <summary><c>TRUE</c> or <c>FALSE</c>.</summary>
    Boolean,

    /// <summary>A number formatted as a date, time or duration (see <see cref="ExcelDateTime"/>).</summary>
    DateTime,

    /// <summary>An error value such as <c>#DIV/0!</c> or <c>#N/A</c>.</summary>
    Error,
}

/// <summary>The value of one cell, as read by <see cref="WorksheetReader"/>.</summary>
public readonly struct ExcelValue : IEquatable<ExcelValue>
{
    private readonly string? _text;
    private readonly double _number;
    private readonly ExcelValueKind _kind;
    private readonly DateKind _dateKind;
    private readonly bool _is1904;

    private ExcelValue(ExcelValueKind kind, string? text, double number, DateKind dateKind = default, bool is1904 = false)
    {
        _kind = kind;
        _text = text;
        _number = number;
        _dateKind = dateKind;
        _is1904 = is1904;
    }

    /// <summary>No value.</summary>
    public static ExcelValue Empty => default;

    /// <summary>The kind of value.</summary>
    public ExcelValueKind Kind => _kind;

    /// <summary>True when the cell has no value.</summary>
    public bool IsEmpty => _kind == ExcelValueKind.Empty;

    /// <summary>Creates a text value.</summary>
    public static ExcelValue FromString(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        return new(ExcelValueKind.String, value, 0);
    }

    /// <summary>Creates a numeric value.</summary>
    public static ExcelValue FromNumber(double value) => new(ExcelValueKind.Number, null, value);

    /// <summary>Creates a boolean value.</summary>
    public static ExcelValue FromBoolean(bool value) => new(ExcelValueKind.Boolean, null, value ? 1 : 0);

    /// <summary>Creates a date, time or duration value.</summary>
    public static ExcelValue FromDateTime(ExcelDateTime value) => new(ExcelValueKind.DateTime, null, value.Serial, value.Kind, value.Is1904);

    /// <summary>Creates an error value such as <c>#N/A</c>.</summary>
    public static ExcelValue FromError(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        return new(ExcelValueKind.Error, value, 0);
    }

    /// <summary>Gets the text of a string cell.</summary>
    public bool TryGetString(out string value)
    {
        value = _kind == ExcelValueKind.String ? _text! : string.Empty;
        return _kind == ExcelValueKind.String;
    }

    /// <summary>Gets the number of a numeric cell; for date and time cells, the serial number.</summary>
    public bool TryGetDouble(out double value)
    {
        var ok = _kind is ExcelValueKind.Number or ExcelValueKind.DateTime;
        value = ok ? _number : 0;
        return ok;
    }

    /// <summary>Gets the value of a boolean cell.</summary>
    public bool TryGetBoolean(out bool value)
    {
        value = _kind == ExcelValueKind.Boolean && _number != 0;
        return _kind == ExcelValueKind.Boolean;
    }

    /// <summary>Gets the serial, kind and date system of a date, time or duration cell.</summary>
    public bool TryGetExcelDateTime(out ExcelDateTime value)
    {
        value = _kind == ExcelValueKind.DateTime ? new ExcelDateTime(_number, _dateKind, _is1904) : default;
        return _kind == ExcelValueKind.DateTime;
    }

    /// <summary>
    /// Converts a date, time or duration cell to a <see cref="DateTime"/>. Returns false for other cells and
    /// for serials that are not a representable date (see <see cref="ExcelDateTime.TryGetDateTime"/>).
    /// </summary>
    public bool TryGetDateTime(out DateTime value)
    {
        value = default;
        return _kind == ExcelValueKind.DateTime && new ExcelDateTime(_number, _dateKind, _is1904).TryGetDateTime(out value);
    }

    /// <summary>Gets the text of an error cell, such as <c>#N/A</c>.</summary>
    public bool TryGetError(out string value)
    {
        value = _kind == ExcelValueKind.Error ? _text! : string.Empty;
        return _kind == ExcelValueKind.Error;
    }

    /// <summary>Formats the value roughly as Excel's General format would, with dates in ISO 8601.</summary>
    public override string ToString() => _kind switch
    {
        ExcelValueKind.String or ExcelValueKind.Error => _text!,
        ExcelValueKind.Number => _number.ToString("R", CultureInfo.InvariantCulture),
        ExcelValueKind.Boolean => _number != 0 ? "TRUE" : "FALSE",
        ExcelValueKind.DateTime => new ExcelDateTime(_number, _dateKind, _is1904).ToString(),
        _ => string.Empty,
    };

    /// <inheritdoc />
    public bool Equals(ExcelValue other) =>
        _kind == other._kind
        && _number.Equals(other._number)
        && _dateKind == other._dateKind
        && _is1904 == other._is1904
        && string.Equals(_text, other._text, StringComparison.Ordinal);

    /// <inheritdoc />
    public override bool Equals(object? obj) => obj is ExcelValue other && Equals(other);

    /// <inheritdoc />
    public override int GetHashCode() => HashCode.Combine(_kind, _number, _dateKind, _is1904, _text);

#pragma warning disable CS1591
    public static bool operator ==(ExcelValue left, ExcelValue right) => left.Equals(right);

    public static bool operator !=(ExcelValue left, ExcelValue right) => !left.Equals(right);
#pragma warning restore CS1591

    internal static ExcelValue Text(string value) => new(ExcelValueKind.String, value, 0);

    internal static ExcelValue Error(string value) => new(ExcelValueKind.Error, value, 0);

    internal static ExcelValue Date(double serial, DateKind kind, bool is1904) => new(ExcelValueKind.DateTime, null, serial, kind, is1904);
}
