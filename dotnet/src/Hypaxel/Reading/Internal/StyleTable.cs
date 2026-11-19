using System.Globalization;

namespace Hypaxel.Internal.Reading;

/// <summary>Date classification of every cell format (<c>cellXfs</c> entry) in the workbook.</summary>
internal sealed class StyleTable
{
    public static readonly StyleTable Empty = new([]);

    private readonly DateKind?[] _kinds;

    private StyleTable(DateKind?[] kinds) => _kinds = kinds;

    internal IReadOnlyList<DateKind?> Kinds => _kinds;

    public DateKind? GetDateKind(int style) => (uint)style < (uint)_kinds.Length ? _kinds[style] : null;

    /// <summary>Classifies a built-in number format id.</summary>
    internal static DateKind? BuiltinKind(int id) => id switch
    {
        >= 14 and <= 17 or >= 27 and <= 31 or >= 34 and <= 36 or >= 50 and <= 54 or 57 or 58 => DateKind.Date,
        22 => DateKind.DateTime,
        >= 18 and <= 21 or 32 or 33 or 45 or 47 or 55 or 56 => DateKind.Time,
        46 => DateKind.Duration,
        _ => null,
    };

    /// <summary>
    /// Classifies a format code such as <c>yyyy-mm-dd</c>, <c>[h]:mm</c> or <c>#,##0.00 "days"</c>. Only the first
    /// section (positive numbers) is considered. Quoted text, escaped characters, padding/fill characters and
    /// bracketed colors, conditions and locales are ignored.
    /// </summary>
    internal static DateKind? FormatKind(ReadOnlySpan<char> code)
    {
        bool date = false, time = false, minuteOrMonth = false, elapsed = false;
        var i = 0;
        while (i < code.Length)
        {
            var c = code[i];
            if (c == ';')
            {
                break;
            }

            switch (c)
            {
                case '"':
                    i++;
                    while (i < code.Length && code[i] != '"')
                    {
                        i++;
                    }

                    break;
                case '\\' or '_' or '*':
                    i++;
                    break;
                case '[':
                {
                    var close = code[i..].IndexOf(']');
                    close = close < 0 ? code.Length : i + close;
                    var inner = code[(i + 1)..close];
                    elapsed |= !inner.IsEmpty && inner.IndexOfAnyExcept("hHmMsS") < 0;
                    i = close;
                    break;
                }

                default:
                    if (code.Length - i >= 7 && code.Slice(i, 7).Equals("general", StringComparison.OrdinalIgnoreCase))
                    {
                        i += 6;
                    }
                    else if (c is 'y' or 'Y' or 'd' or 'D' or 'e')
                    {
                        date = true;
                    }
                    else if (c is 'h' or 'H' or 's' or 'S')
                    {
                        time = true;
                    }
                    else if (c is 'm' or 'M')
                    {
                        minuteOrMonth = true;
                    }

                    break;
            }

            i++;
        }

        // 'm' is a month next to dates and a minute next to hours or seconds; alone it is a month.
        date |= minuteOrMonth && !time;
        return elapsed ? DateKind.Duration
            : date && time ? DateKind.DateTime
            : date ? DateKind.Date
            : time ? DateKind.Time
            : null;
    }

    public static StyleTable Parse(Utf8XmlReader xml)
    {
        var custom = new List<(int Id, DateKind? Kind)>();
        var kinds = new List<DateKind?>();
        var inCellXfs = false;
        while (true)
        {
            var token = xml.Next();
            if (token == XmlToken.Eof)
            {
                break;
            }

            if (token == XmlToken.End && xml.Name.SequenceEqual("cellXfs"u8))
            {
                inCellXfs = false;
                continue;
            }

            if (token is not (XmlToken.Start or XmlToken.Empty))
            {
                continue;
            }

            var start = token == XmlToken.Start;
            var name = xml.Name;
            if (name.SequenceEqual("numFmt"u8))
            {
                if (XmlAttributes.TryGet(xml.Attributes, "numFmtId"u8, out var rawId)
                    && TryParseInt(rawId, out var id)
                    && XmlAttributes.TryGet(xml.Attributes, "formatCode"u8, out var rawCode)
                    && XmlText.DecodeAttribute(rawCode) is { } formatCode)
                {
                    custom.Add((id, FormatKind(formatCode)));
                }
            }
            else if (name.SequenceEqual("cellXfs"u8))
            {
                inCellXfs |= start;
            }
            else if (inCellXfs && name.SequenceEqual("xf"u8))
            {
                var id = XmlAttributes.TryGet(xml.Attributes, "numFmtId"u8, out var rawId) && TryParseInt(rawId, out var parsed) ? parsed : 0;
                var match = custom.FindLastIndex(c => c.Id == id);
                kinds.Add(match >= 0 ? custom[match].Kind : BuiltinKind(id));
            }

            if (start && !name.SequenceEqual("cellXfs"u8) && !name.SequenceEqual("styleSheet"u8) && !name.SequenceEqual("numFmts"u8))
            {
                xml.SkipElement();
            }
        }

        return new StyleTable([.. kinds]);
    }

    internal static bool TryParseInt(ReadOnlySpan<byte> raw, out int value) =>
        int.TryParse(raw.Trim(" \t\r\n"u8), NumberStyles.None, CultureInfo.InvariantCulture, out value);
}
