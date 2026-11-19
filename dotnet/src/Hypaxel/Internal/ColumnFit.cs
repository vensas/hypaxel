using System.Text;
using Hypaxel.Styling;

namespace Hypaxel.Internal;

/// <summary>
/// Column auto-fit: width estimates for cell content in pixels of Calibri 11 at 96 DPI, the metric
/// Excel's column widths are defined in (a width of <c>w</c> characters is <c>7w + 5</c> pixels).
/// </summary>
internal sealed class ColumnFit
{
    /// <summary>The sample stops early once the held-back XML reaches this size.</summary>
    public const int MaxSampleBytes = 8 * 1024 * 1024;

    private const float DigitPx = 7;

    // Calibri 11 advance widths in pixels for ASCII 0x20..0x7E.
    private static ReadOnlySpan<byte> AsciiPx =>
    [
        3, 3, 4, 7, 7, 10, 10, 2, 4, 4, 7, 7, 3, 4, 3, 5, // space ! " # $ % & ' ( ) * + , - . /
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, // 0-9
        3, 3, 7, 7, 7, 6, 13, // : ; < = > ? @
        9, 8, 8, 9, 7, 7, 9, 9, 4, 5, 8, 6, 12, 10, 10, 8, 10, 8, 7, 7, 9, 9, 13, 8, 7, 7, // A-Z
        4, 5, 4, 7, 7, 4, // [ \ ] ^ _ `
        7, 8, 6, 8, 8, 5, 7, 8, 4, 4, 7, 4, 12, 8, 8, 8, 8, 5, 6, 5, 8, 7, 11, 7, 7, 6, // a-z
        5, 7, 5, 7, // { | } ~
    ];

    private readonly StyleRegistry _styles;
    private readonly AutoFitOptions _settings;
    private readonly SortedDictionary<int, ColumnDefinition>? _columns;
    private float[] _widths = [];
    private StyleFit?[] _styleFits = [];
    private int _rows;

    public ColumnFit(StyleRegistry styles, AutoFitOptions settings, SortedDictionary<int, ColumnDefinition>? columns, int colsAt)
    {
        _styles = styles;
        _settings = settings;
        _columns = columns;
        ColsAt = colsAt;
    }

    /// <summary>Where <c>&lt;cols&gt;</c> belongs in the held-back XML.</summary>
    public int ColsAt { get; }

    /// <summary>Counts a closed row; true once the sample is complete.</summary>
    public bool RowClosed(int xmlLength) => ++_rows >= _settings.SampleRows || xmlLength >= MaxSampleBytes;

    public void Text(int column, int style, ReadOnlySpan<char> text) => Observe(column, TextPx(text) * GetStyle(style).Scale);

    public void Number(int column, int style, double value)
    {
        var fit = GetStyle(style);
        if (!double.IsFinite(value))
        {
            Observe(column, TextPx("#NUM!") * fit.Scale);
            return;
        }

        Observe(column, NumberPx(fit.Shape, value) * fit.Scale);
    }

    /// <summary>Writes <c>&lt;cols&gt;</c> for the explicit columns and the fitted ones.</summary>
    public void WriteColumns(Utf8XmlBuffer x)
    {
        var indexes = new SortedSet<int>();
        if (_columns is not null)
        {
            indexes.UnionWith(_columns.Keys);
        }

        for (var i = 0; i < _widths.Length; i++)
        {
            if (_widths[i] > 0)
            {
                indexes.Add(i + 1);
            }
        }

        if (indexes.Count == 0)
        {
            return;
        }

        x.WriteRaw("<cols>"u8);
        foreach (var index in indexes)
        {
            ColumnDefinition? definition = null;
            _columns?.TryGetValue(index, out definition);
            var width = definition?.Width;
            if (width is null && index <= _widths.Length && _widths[index - 1] > 0)
            {
                width = WidthFor(_widths[index - 1]);
            }

            Worksheet.WriteColumn(x, index, width, definition?.Style ?? default, definition?.Hidden ?? false);
        }

        x.WriteRaw("</cols>"u8);
    }

    /// <summary>Column width in characters for content <paramref name="px"/> wide, with Excel's cell padding.</summary>
    private double WidthFor(float px)
    {
        var chars = ((double)px + 2) / 7;
        return Math.Clamp(Math.Min(Math.Ceiling(chars * 100) / 100, _settings.MaxWidth), 0, 255);
    }

    private void Observe(int column, float px)
    {
        var i = column - 1;
        if (i >= _widths.Length)
        {
            Array.Resize(ref _widths, Math.Max(i + 1, _widths.Length * 2));
        }

        if (px > _widths[i])
        {
            _widths[i] = px;
        }
    }

    private StyleFit GetStyle(int style)
    {
        if (style >= _styleFits.Length)
        {
            Array.Resize(ref _styleFits, Math.Max(style + 1, _styleFits.Length * 2));
        }

        return _styleFits[style] ??= CreateStyleFit(style);
    }

    private StyleFit CreateStyleFit(int style)
    {
        var (size, bold, numFmtId, custom) = _styles.GetMeasureInfo(style);
        var scale = (float)(size / 11);
        if (bold)
        {
            scale *= 1.1f;
        }

        return new StyleFit(scale, custom is null ? BuiltInShape(numFmtId) : CustomShape(custom));
    }

    internal static float CharPx(int code)
    {
        if (code is >= 0x20 and < 0x7F)
        {
            return AsciiPx[code - 0x20];
        }

        return IsWide(code) ? 15 : 8;
    }

    /// <summary>East Asian wide and fullwidth characters, plus emoji, which Excel renders about two digits wide.</summary>
    private static bool IsWide(int code) => code is
        (>= 0x1100 and <= 0x115F) or (>= 0x2E80 and <= 0x303E) or (>= 0x3041 and <= 0x33FF)
        or (>= 0x3400 and <= 0x4DBF) or (>= 0x4E00 and <= 0x9FFF) or (>= 0xA000 and <= 0xA4CF)
        or (>= 0xAC00 and <= 0xD7A3) or (>= 0xF900 and <= 0xFAFF) or (>= 0xFE30 and <= 0xFE4F)
        or (>= 0xFF00 and <= 0xFF60) or (>= 0xFFE0 and <= 0xFFE6) or (>= 0x1F300 and <= 0x1F64F)
        or (>= 0x1F900 and <= 0x1F9FF) or (>= 0x20000 and <= 0x3FFFD);

    /// <summary>Width of the longest line of <paramref name="text"/>.</summary>
    internal static float TextPx(ReadOnlySpan<char> text)
    {
        float widest = 0, line = 0;
        foreach (var rune in text.EnumerateRunes())
        {
            if (rune.Value == '\n')
            {
                widest = Math.Max(widest, line);
                line = 0;
            }
            else if (rune.Value != '\r')
            {
                line += CharPx(rune.Value);
            }
        }

        return Math.Max(widest, line);
    }

    private static float GeneralPx(double value)
    {
        var magnitude = Math.Abs(value);
        if (magnitude != 0 && magnitude is not (>= 1e-9 and < 1e11))
        {
            return TextPx(value < 0 ? "-1.23457E+11" : "1.23457E+11");
        }

        // General shows at most 11 characters, rounding the fraction away.
        Span<byte> buffer = stackalloc byte[32];
        var length = Math.Min(Utf8XmlBuffer.FormatDouble(value, buffer), 11);
        float px = 0;
        foreach (var b in buffer[..length])
        {
            px += CharPx(b);
        }

        return px;
    }

    private static float FixedPx(in Shape shape, double value)
    {
        var scaled = Math.Abs(value) * Math.Pow(100, shape.Percent);
        var factor = Math.Pow(10, shape.Decimals);
        var rounded = Math.Round(scaled * factor, MidpointRounding.AwayFromZero) / factor;
        var whole = Math.Truncate(rounded);
        var digits = whole >= 1 ? (int)Math.Floor(Math.Log10(whole)) + 1 : 0;
        digits = Math.Max(digits, shape.MinIntegerDigits);
        var px = (digits * DigitPx) + shape.LiteralPx + (shape.Percent * CharPx('%'));
        if (shape.Thousands && digits > 3)
        {
            px += (digits - 1) / 3 * CharPx(',');
        }

        if (shape.Decimals > 0)
        {
            px += CharPx('.') + (shape.Decimals * DigitPx);
        }

        if (value < 0)
        {
            px += CharPx('-');
        }

        return px;
    }

    internal static Shape BuiltInShape(int id) => id switch
    {
        1 => Shape.Fixed(0, 1, false, 0, 0),
        2 => Shape.Fixed(2, 1, false, 0, 0),
        3 => Shape.Fixed(0, 1, true, 0, 0),
        4 => Shape.Fixed(2, 1, true, 0, 0),
        9 => Shape.Fixed(0, 1, false, 1, 0),
        10 => Shape.Fixed(2, 1, false, 1, 0),
        11 => Shape.Constant(TextPx("1.00E+00")),
        14 => Shape.Constant(TextPx("12/31/2026")),
        21 => Shape.Constant(TextPx("23:59:59")),
        22 => Shape.Constant(TextPx("12/31/2026 23:59")),
        46 => Shape.Constant(TextPx("100:00:00")),
        _ => Shape.General,
    };

    /// <summary>Estimates the rendering of a custom format code from its first section.</summary>
    internal static Shape CustomShape(string code)
    {
        var semicolon = code.IndexOf(';');
        var section = semicolon < 0 ? code : code[..semicolon];
        if (section.Length == 0 || section == "@" || section.Equals("general", StringComparison.OrdinalIgnoreCase))
        {
            return Shape.General;
        }

        // Separate literal text from format tokens.
        var tokens = new StringBuilder();
        float literalPx = 0;
        for (var i = 0; i < section.Length; i++)
        {
            var c = section[i];
            switch (c)
            {
                case '"':
                    while (++i < section.Length && section[i] != '"')
                    {
                        literalPx += CharPx(section[i]);
                    }

                    break;
                case '\\' or '_':
                    if (++i < section.Length)
                    {
                        literalPx += CharPx(section[i]);
                    }

                    break;
                case '*':
                    i++;
                    break;
                case '[':
                    var end = section.IndexOf(']', i + 1);
                    var inner = end < 0 ? section[(i + 1)..] : section[(i + 1)..end];
                    i = end < 0 ? section.Length : end;
                    if (inner.StartsWith('$'))
                    {
                        var dash = inner.IndexOf('-');
                        literalPx += TextPx(dash < 0 ? inner.AsSpan(1) : inner.AsSpan(1, dash - 1));
                    }
                    else if (inner.All(ch => char.ToLowerInvariant(ch) is 'h' or 'm' or 's'))
                    {
                        tokens.Append("hhh"); // elapsed time: allow three digits
                    }

                    break;
                default:
                    tokens.Append(c);
                    break;
            }
        }

        var t = tokens.ToString();
        var numeric = t.AsSpan().IndexOfAny('0', '#', '?') >= 0;
        var lower = t.ToLowerInvariant();
        if (!numeric && lower.AsSpan().IndexOfAny("ymdhs") >= 0)
        {
            return Shape.Constant(literalPx + DatePx(lower));
        }

        if (!numeric)
        {
            return Shape.General;
        }

        if (lower.Contains("e+", StringComparison.Ordinal) || lower.Contains("e-", StringComparison.Ordinal))
        {
            return Shape.Constant(literalPx + (Placeholders(t) * DigitPx) + TextPx(".E+"));
        }

        var dot = t.IndexOf('.');
        var integer = dot < 0 ? t : t[..dot];
        var fraction = dot < 0 ? "" : t[(dot + 1)..];
        float others = 0, spaces = 0;
        foreach (var c in t)
        {
            if (char.IsWhiteSpace(c))
            {
                spaces += CharPx(c);
            }
            else if (c is not ('0' or '#' or '?' or ',' or '.' or '%'))
            {
                others += CharPx(c);
            }
        }

        return Shape.Fixed(
            Math.Min(Placeholders(fraction), 30),
            Math.Min(integer.Count(c => c is '0' or '?'), 30),
            integer.Contains(','),
            Math.Min(t.Count(c => c == '%'), 2),
            literalPx + others + spaces);

        static int Placeholders(string s) => s.Count(c => c is '0' or '#' or '?');
    }

    /// <summary>Width of a date/time format, with each token at its widest rendering.</summary>
    private static float DatePx(string tokens)
    {
        var amPm = tokens.Contains("am/pm", StringComparison.Ordinal);
        float px = 0;
        for (var i = 0; i < tokens.Length;)
        {
            var c = tokens[i];
            if (amPm && c == 'a')
            {
                // "am/pm" renders as "PM"
                i += 5;
                px += TextPx("PM");
                continue;
            }

            var run = 1;
            while (i + run < tokens.Length && tokens[i + run] == c)
            {
                run++;
            }

            i += run;
            px += (c, run) switch
            {
                ('y', <= 2) => 2 * DigitPx,
                ('y', _) => 4 * DigitPx,
                ('m', 3) => TextPx("Sep"),
                ('m', >= 4) => TextPx("September"),
                ('d', 3) => TextPx("Wed"),
                ('d', >= 4) => TextPx("Wednesday"),
                ('m' or 'd' or 's', _) => 2 * DigitPx,
                ('h', _) => Math.Max(run, 2) * DigitPx,
                ('0', _) => run * DigitPx,
                _ => run * CharPx(c),
            };
        }

        return px;
    }

    internal enum ShapeKind : byte
    {
        /// <summary>General (or text) format: up to 11 characters of the shortest representation.</summary>
        General,

        /// <summary>A fixed-width rendering such as a date or scientific notation (width in <see cref="Shape.LiteralPx"/>).</summary>
        Constant,

        Fixed,
    }

    /// <summary>How a style renders numbers, reduced to what matters for width.</summary>
    internal readonly record struct Shape(ShapeKind Kind, int Decimals, int MinIntegerDigits, bool Thousands, int Percent, float LiteralPx)
    {
        public static Shape General => default;

        public static Shape Constant(float px) => new(ShapeKind.Constant, 0, 0, false, 0, px);

        public static Shape Fixed(int decimals, int minIntegerDigits, bool thousands, int percent, float literalPx) =>
            new(ShapeKind.Fixed, decimals, minIntegerDigits, thousands, percent, literalPx);
    }

    private sealed record StyleFit(float Scale, Shape Shape);

    internal static float NumberPx(Shape shape, double value) => shape.Kind switch
    {
        ShapeKind.General => GeneralPx(value),
        ShapeKind.Constant => shape.LiteralPx,
        _ => FixedPx(shape, value),
    };
}
