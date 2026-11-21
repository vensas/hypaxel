using System.Globalization;

namespace Hypaxel.Internal.Reading;

/// <summary>Reads a table part (<c>xl/tables/tableN.xml</c>) into the <see cref="ExcelTable"/> the writer accepts.</summary>
internal static class TableReader
{
    public static ExcelTable Parse(Utf8XmlReader xml)
    {
        ExcelTable? table = null;
        List<string>? columns = null;
        var autoFilter = false;
        var style = TableStyle.None;
        bool rowStripes = false, columnStripes = false, firstColumn = false, lastColumn = false;
        var buffer = new CharBuffer();
        while (true)
        {
            var token = xml.Next();
            if (token == XmlToken.Eof)
            {
                break;
            }

            if (token is not (XmlToken.Start or XmlToken.Empty))
            {
                continue;
            }

            var name = xml.Name;
            var attributes = xml.Attributes;
            var descend = false;
            if (table is null)
            {
                if (!name.SequenceEqual("table"u8))
                {
                    throw xml.Malformed("The part is not a table.");
                }

                if (!XmlAttributes.TryGet(attributes, "ref"u8, out var reference)
                    || !TryParseRange(reference, out var r1, out var c1, out var r2, out var c2))
                {
                    throw xml.Malformed("The table has no valid cell range.");
                }

                table = new ExcelTable(r1, c1, r2, c2)
                {
                    Name = Text(attributes, "displayName"u8) ?? Text(attributes, "name"u8),
                    ShowHeaderRow = !XmlAttributes.TryGet(attributes, "headerRowCount"u8, out var header) || !Trim(header).SequenceEqual("0"u8),
                };
                descend = true;
            }
            else if (name.SequenceEqual("autoFilter"u8))
            {
                autoFilter = true;
            }
            else if (name.SequenceEqual("tableColumns"u8))
            {
                descend = true;
            }
            else if (name.SequenceEqual("tableColumn"u8))
            {
                columns ??= [];
                if (columns.Count >= CellReference.MaxColumns)
                {
                    throw xml.Malformed("The table has more than 16384 columns.");
                }

                buffer.Clear();
                var decoded = XmlAttributes.TryGet(attributes, "name"u8, out var raw) && XmlText.Decode(buffer, raw, entities: true, excelEscapes: true) is null;
                columns.Add(decoded ? buffer.Span.ToString() : string.Empty);
            }
            else if (name.SequenceEqual("tableStyleInfo"u8))
            {
                style = XmlAttributes.TryGet(attributes, "name"u8, out var styleName) ? ParseStyle(styleName) : TableStyle.None;
                rowStripes = Flag(attributes, "showRowStripes"u8);
                columnStripes = Flag(attributes, "showColumnStripes"u8);
                firstColumn = Flag(attributes, "showFirstColumn"u8);
                lastColumn = Flag(attributes, "showLastColumn"u8);
            }

            if (token == XmlToken.Start && !descend)
            {
                xml.SkipElement();
            }
        }

        if (table is null)
        {
            throw xml.Malformed("The part is not a table.");
        }

        return table with
        {
            Columns = columns is null ? [] : columns.ToArray(),
            Style = style,
            ShowAutoFilter = autoFilter,
            ShowRowStripes = rowStripes,
            ShowColumnStripes = columnStripes,
            HighlightFirstColumn = firstColumn,
            HighlightLastColumn = lastColumn,
        };
    }

    internal static bool IsTrue(ReadOnlySpan<byte> raw)
    {
        raw = Trim(raw);
        return raw.SequenceEqual("1"u8) || raw.SequenceEqual("true"u8);
    }

    internal static bool IsFalse(ReadOnlySpan<byte> raw)
    {
        raw = Trim(raw);
        return raw.SequenceEqual("0"u8) || raw.SequenceEqual("false"u8);
    }

    /// <summary>Parses an outline level; unparsable values give 0 and larger ones clamp to 7.</summary>
    internal static int ParseOutlineLevel(ReadOnlySpan<byte> raw) =>
        uint.TryParse(Trim(raw), NumberStyles.None, CultureInfo.InvariantCulture, out var level) ? (int)Math.Min(level, 7) : 0;

    /// <summary>Parses a finite, non-negative width or height.</summary>
    internal static double? ParseMeasure(ReadOnlySpan<byte> raw) =>
        double.TryParse(Trim(raw), NumberStyles.Float, CultureInfo.InvariantCulture, out var value) && double.IsFinite(value) && value >= 0
            ? value
            : null;

    /// <summary>Maps a built-in style name such as <c>TableStyleMedium9</c>; custom styles have no counterpart.</summary>
    internal static TableStyle ParseStyle(ReadOnlySpan<byte> raw)
    {
        raw = Trim(raw);
        if (!raw.StartsWith("TableStyle"u8))
        {
            return TableStyle.None;
        }

        raw = raw[10..];
        var digits = raw.IndexOfAnyInRange((byte)'0', (byte)'9');
        if (digits < 0 || !byte.TryParse(raw[digits..], NumberStyles.None, CultureInfo.InvariantCulture, out var number))
        {
            return TableStyle.None;
        }

        var family = raw[..digits];
        return family.SequenceEqual("Light"u8) && number is >= 1 and <= 21 ? TableStyle.Light(number)
            : family.SequenceEqual("Medium"u8) && number is >= 1 and <= 28 ? TableStyle.Medium(number)
            : family.SequenceEqual("Dark"u8) && number is >= 1 and <= 11 ? TableStyle.Dark(number)
            : TableStyle.None;
    }

    /// <summary>Parses <c>A1:C10</c> (or a single cell) into a 1-based, inclusive range.</summary>
    internal static bool TryParseRange(ReadOnlySpan<byte> raw, out int firstRow, out int firstColumn, out int lastRow, out int lastColumn)
    {
        raw = Trim(raw);
        var colon = raw.IndexOf((byte)':');
        var first = colon < 0 ? raw : raw[..colon];
        var last = colon < 0 ? raw : raw[(colon + 1)..];
        lastRow = lastColumn = 0;
        return TryParseCell(first, out firstRow, out firstColumn)
            && TryParseCell(last, out lastRow, out lastColumn)
            && firstRow <= lastRow && firstColumn <= lastColumn;
    }

    private static bool TryParseCell(ReadOnlySpan<byte> raw, out int row, out int column)
    {
        row = column = 0;
        var letters = 0;
        var digits = 0;
        foreach (var b in raw)
        {
            if (b == (byte)'$')
            {
                continue;
            }

            if (char.IsAsciiLetter((char)b) && digits == 0 && letters < 3)
            {
                letters++;
                column = (column * 26) + ((b | 0x20) - 'a' + 1);
            }
            else if (char.IsAsciiDigit((char)b) && letters > 0 && digits < 7)
            {
                digits++;
                row = (row * 10) + (b - '0');
            }
            else
            {
                return false;
            }
        }

        return row is >= 1 and <= CellReference.MaxRows && column is >= 1 and <= CellReference.MaxColumns;
    }

    private static bool Flag(ReadOnlySpan<byte> attributes, ReadOnlySpan<byte> name) =>
        XmlAttributes.TryGet(attributes, name, out var raw) && IsTrue(raw);

    private static string? Text(ReadOnlySpan<byte> attributes, ReadOnlySpan<byte> name) =>
        XmlAttributes.TryGet(attributes, name, out var raw) ? XmlText.DecodeAttribute(raw) : null;

    private static ReadOnlySpan<byte> Trim(ReadOnlySpan<byte> raw) => raw.Trim(" \t\r\n\f"u8);
}
