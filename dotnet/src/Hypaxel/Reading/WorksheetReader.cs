using System.Collections;
using System.Globalization;
using System.Text;
using Hypaxel.Internal.Reading;

namespace Hypaxel;

/// <summary>
/// The rows of one worksheet, read front to back. Created by <see cref="XlsxReader.OpenWorksheet(string)"/>.
/// </summary>
/// <remarks>
/// Rows that are missing from the file or only carry formatting are skipped; <see cref="XlsxRow.Number"/>
/// tells where a row is. The reader is forward-only: enumerating it a second time continues where the
/// previous enumeration stopped.
/// </remarks>
/// <example>
/// <code>
/// using var rows = reader.OpenWorksheet(0);
/// foreach (var row in rows)
/// {
///     foreach (var cell in row.Cells)
///     {
///         Console.WriteLine($"{CellReference.ToA1(row.Number, cell.Column)} = {cell.Value}");
///     }
/// }
/// </code>
/// </example>
public sealed class WorksheetReader : IEnumerable<XlsxRow>, IDisposable
{
    private const int MaxRowText = 128 * 1024 * 1024;

    private readonly Utf8XmlReader _xml;
    private readonly string[] _sharedStrings;
    private readonly StyleTable _styles;
    private readonly bool _is1904;
    private readonly XlsxRow _row;
    private byte[] _scratch = new byte[64];
    private int _scratchLength;
    private State _state;
    private int _previousRow;

    internal WorksheetReader(string name, Utf8XmlReader xml, string[] sharedStrings, StyleTable styles, bool is1904)
    {
        Name = name;
        _xml = xml;
        _sharedStrings = sharedStrings;
        _styles = styles;
        _is1904 = is1904;
        _row = new XlsxRow(sharedStrings, is1904);
    }

    private enum State : byte
    {
        BeforeData,
        InData,
        Done,
    }

    private enum CellType : byte
    {
        Number,
        Shared,
        FormulaString,
        InlineString,
        Boolean,
        Error,
        IsoDate,
    }

    /// <summary>The sheet name.</summary>
    public string Name { get; }

    /// <summary>The current row, valid after <see cref="Read"/> returned true. The same instance is reused for every row.</summary>
    public XlsxRow Row => _row;

    /// <summary>Advances to the next row that has at least one value.</summary>
    /// <returns>False at the end of the sheet.</returns>
    /// <exception cref="XlsxReadException">The worksheet part is damaged; the message names the part and row.</exception>
    public bool Read()
    {
        while (true)
        {
            switch (_state)
            {
                case State.Done:
                    return false;
                case State.BeforeData:
                    FindSheetData();
                    break;
                default:
                {
                    var token = _xml.Next();
                    if (token is XmlToken.Start or XmlToken.Empty && _xml.Name.SequenceEqual("row"u8))
                    {
                        int number;
                        if (XmlAttributes.TryGet(_xml.Attributes, "r"u8, out var raw))
                        {
                            if (!StyleTable.TryParseInt(raw, out number))
                            {
                                throw _xml.Malformed("A row has an invalid row number.");
                            }
                        }
                        else
                        {
                            number = _previousRow + 1;
                        }

                        if (number is < 1 or > CellReference.MaxRows)
                        {
                            throw _xml.Malformed($"Row number {number} is outside 1..{CellReference.MaxRows}.");
                        }

                        _previousRow = number;
                        if (token == XmlToken.Empty)
                        {
                            continue;
                        }

                        _row.Reset(number);
                        ReadCells();
                        if (_row.Count > 0)
                        {
                            return true;
                        }
                    }
                    else if (token == XmlToken.Start)
                    {
                        _xml.SkipElement();
                    }
                    else if (token is XmlToken.End or XmlToken.Eof)
                    {
                        _state = State.Done;
                    }

                    break;
                }
            }
        }
    }

    /// <summary>Returns the remaining rows. The same <see cref="XlsxRow"/> instance is yielded each time.</summary>
    public IEnumerator<XlsxRow> GetEnumerator()
    {
        while (Read())
        {
            yield return _row;
        }
    }

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    /// <summary>Releases the decompression stream and pooled buffers.</summary>
    public void Dispose()
    {
        _state = State.Done;
        _xml.Dispose();
    }

    private void FindSheetData()
    {
        while (true)
        {
            var token = _xml.Next();
            if (token == XmlToken.Eof)
            {
                _state = State.Done;
                return;
            }

            if (token is not (XmlToken.Start or XmlToken.Empty))
            {
                continue;
            }

            var name = _xml.Name;
            if (name.SequenceEqual("sheetData"u8))
            {
                _state = token == XmlToken.Start ? State.InData : State.Done;
                return;
            }

            if (token == XmlToken.Start
                && !name.SequenceEqual("worksheet"u8) && !name.SequenceEqual("dialogsheet"u8) && !name.SequenceEqual("macrosheet"u8))
            {
                _xml.SkipElement();
            }
        }
    }

    private void ReadCells()
    {
        var column = 0;
        var sorted = true;
        while (true)
        {
            var token = _xml.Next();
            if (token == XmlToken.End)
            {
                break;
            }

            if (token is not (XmlToken.Start or XmlToken.Empty))
            {
                continue;
            }

            if (!_xml.Name.SequenceEqual("c"u8))
            {
                if (token == XmlToken.Start)
                {
                    _xml.SkipElement();
                }

                continue;
            }

            var nextColumn = column + 1;
            var cellType = CellType.Number;
            var style = 0;
            var attributes = new XmlAttributes(_xml.Attributes);
            while (attributes.MoveNext())
            {
                var name = attributes.Name;
                if (name.Length != 1)
                {
                    continue;
                }

                switch (name[0])
                {
                    case (byte)'r':
                        nextColumn = ParseColumn(attributes.Value);
                        break;
                    case (byte)'t':
                        cellType = ParseCellType(attributes.Value);
                        break;
                    case (byte)'s':
                        style = ParseDigits(attributes.Value);
                        if (style < 0)
                        {
                            style = 0;
                        }

                        break;
                }
            }

            if (nextColumn is < 1 or > CellReference.MaxColumns)
            {
                throw _xml.Malformed($"Row {_row.Number} has a cell with an invalid reference.");
            }

            if (cellType == (CellType)255)
            {
                throw _xml.Malformed($"Row {_row.Number} has a cell with an unknown type.");
            }

            if (nextColumn <= column)
            {
                sorted = false;
            }

            column = nextColumn;
            if (token == XmlToken.Empty)
            {
                continue;
            }

            ReadCell(cellType, style, column);
            if (_row.Text.Length > MaxRowText)
            {
                throw XlsxReadException.LimitExceeded(_xml.Part, $"Row {_row.Number} holds more than {MaxRowText} characters of text.");
            }
        }

        if (!sorted)
        {
            _row.SortAndDeduplicate();
        }
    }

    /// <summary>Reads the children of a <c>&lt;c&gt;</c> element up to its end tag.</summary>
    private void ReadCell(CellType cellType, int style, int column)
    {
        var isText = cellType is CellType.FormulaString or CellType.InlineString or CellType.Error;
        var text = _row.Text;
        var textStart = text.Length;
        _scratchLength = 0;

        ReadOnlySpan<byte> raw;
        if (cellType == CellType.InlineString
            ? _xml.TryReadSimpleElement("<is><t>"u8, "</t></is></c>"u8, out raw)
              || _xml.TryReadSimpleElement("<is><t xml:space=\"preserve\">"u8, "</t></is></c>"u8, out raw)
            : _xml.TryReadSimpleElement("<v>"u8, "</v></c>"u8, out raw))
        {
            if (isText)
            {
                if (XmlText.Decode(text, raw, entities: true, excelEscapes: true) is { } error)
                {
                    throw _xml.Malformed(error);
                }
            }
            else if (raw.IndexOf((byte)'&') < 0)
            {
                AppendScratch(raw);
            }
            else
            {
                AppendDecoded(raw);
            }

            AddCell(cellType, style, column, textStart);
            return;
        }

        var hasValue = false;
        while (true)
        {
            var token = _xml.Next();
            if (token == XmlToken.End)
            {
                break;
            }

            if (token != XmlToken.Start)
            {
                continue;
            }

            var name = _xml.Name;
            if (name.SequenceEqual("v"u8))
            {
                hasValue = true;
                if (isText)
                {
                    _xml.ReadTextInto(text, excelEscapes: true);
                }
                else if (_xml.TryReadRawText(out raw))
                {
                    if (raw.IndexOf((byte)'&') < 0)
                    {
                        AppendScratch(raw);
                    }
                    else
                    {
                        AppendDecoded(raw);
                    }
                }
                else
                {
                    // CDATA or comments inside <v>: rare, so decode through a temporary buffer.
                    var decoded = new CharBuffer(32);
                    _xml.ReadTextInto(decoded, excelEscapes: false);
                    AppendScratch(decoded.Span);
                }
            }
            else if (name.SequenceEqual("is"u8) && cellType == CellType.InlineString)
            {
                hasValue = true;
                ReadInlineString(text);
            }
            else
            {
                _xml.SkipElement();
            }
        }

        if (hasValue)
        {
            AddCell(cellType, style, column, textStart);
        }
        else
        {
            text.Truncate(textStart);
        }
    }

    private void AppendDecoded(ReadOnlySpan<byte> raw)
    {
        var decoded = new CharBuffer(raw.Length);
        if (XmlText.Decode(decoded, raw, entities: true, excelEscapes: false) is { } error)
        {
            throw _xml.Malformed(error);
        }

        AppendScratch(decoded.Span);
    }

    private void AddCell(CellType cellType, int style, int column, int textStart)
    {
        var text = _row.Text;
        var cell = new RawCell { Column = column, Start = textStart, Length = text.Length - textStart };
        var value = _scratch.AsSpan(0, _scratchLength).Trim(" \t\r\n\f"u8);
        switch (cellType)
        {
            case CellType.FormulaString or CellType.InlineString:
                cell.Type = RawType.Text;
                break;
            case CellType.Error:
                cell.Type = RawType.Error;
                break;
            case CellType.Number:
                if (value.IsEmpty)
                {
                    return;
                }

                if (!TryParseNumber(value, out cell.Number))
                {
                    throw BadValue("number", value);
                }

                if (_styles.GetDateKind(style) is { } kind)
                {
                    cell.Type = RawType.Date;
                    cell.DateKind = kind;
                }
                else
                {
                    cell.Type = RawType.Number;
                }

                break;
            case CellType.Shared:
                var index = ParseDigits(value);
                if (index < 0 || index >= _sharedStrings.Length)
                {
                    throw BadValue("shared string index", value);
                }

                cell.Type = RawType.Shared;
                cell.Start = index;
                cell.Length = 0;
                break;
            case CellType.Boolean:
                cell.Type = RawType.Boolean;
                cell.Number = value.SequenceEqual("1"u8) || value.SequenceEqual("true"u8) ? 1
                    : value.SequenceEqual("0"u8) || value.SequenceEqual("false"u8) ? 0
                    : throw BadValue("boolean", value);
                break;
            default:
            {
                var iso = Encoding.UTF8.GetString(value);
                if (ExcelDateTime.TryParseIso(iso, _is1904, out var serial, out var isoKind))
                {
                    cell.Type = RawType.Date;
                    cell.DateKind = isoKind;
                    cell.Number = serial;
                }
                else
                {
                    text.Append(iso);
                    cell.Type = RawType.Text;
                    cell.Length = text.Length - textStart;
                }

                break;
            }
        }

        if (_row.Count >= CellReference.MaxColumns)
        {
            throw _xml.Malformed($"Row {_row.Number} has more than {CellReference.MaxColumns} cells.");
        }

        _row.Add(cell);
    }

    /// <summary>Reads <c>&lt;is&gt;</c> content: plain <c>&lt;t&gt;</c> or rich-text runs, without phonetic runs.</summary>
    private void ReadInlineString(CharBuffer text)
    {
        var target = _xml.Depth - 1;
        while (true)
        {
            var token = _xml.Next();
            if (token == XmlToken.End && _xml.Depth == target)
            {
                return;
            }

            if (token != XmlToken.Start)
            {
                continue;
            }

            var name = _xml.Name;
            if (name.SequenceEqual("t"u8))
            {
                _xml.ReadTextInto(text, excelEscapes: true);
            }
            else if (name.SequenceEqual("rPh"u8) || name.SequenceEqual("rPr"u8) || name.SequenceEqual("phoneticPr"u8))
            {
                _xml.SkipElement();
            }
        }
    }

    private void AppendScratch(ReadOnlySpan<byte> bytes)
    {
        if (_scratch.Length - _scratchLength < bytes.Length)
        {
            Array.Resize(ref _scratch, Math.Max(_scratch.Length * 2, _scratchLength + bytes.Length));
        }

        bytes.CopyTo(_scratch.AsSpan(_scratchLength));
        _scratchLength += bytes.Length;
    }

    private void AppendScratch(ReadOnlySpan<char> chars)
    {
        var bytes = Encoding.UTF8.GetByteCount(chars);
        if (_scratch.Length - _scratchLength < bytes)
        {
            Array.Resize(ref _scratch, Math.Max(_scratch.Length * 2, _scratchLength + bytes));
        }

        _scratchLength += Encoding.UTF8.GetBytes(chars, _scratch.AsSpan(_scratchLength));
    }

    private XlsxReadException BadValue(string what, ReadOnlySpan<byte> raw)
    {
        var shown = Encoding.UTF8.GetString(raw);
        if (shown.Length > 40)
        {
            shown = shown[..40];
        }

        return _xml.Malformed($"Row {_row.Number} has an invalid {what} \"{shown}\".");
    }

    private static CellType ParseCellType(ReadOnlySpan<byte> raw) => raw.Length switch
    {
        1 => raw[0] switch
        {
            (byte)'n' => CellType.Number,
            (byte)'s' => CellType.Shared,
            (byte)'b' => CellType.Boolean,
            (byte)'e' => CellType.Error,
            (byte)'d' => CellType.IsoDate,
            _ => (CellType)255,
        },
        _ when raw.SequenceEqual("str"u8) => CellType.FormulaString,
        _ when raw.SequenceEqual("inlineStr"u8) => CellType.InlineString,
        _ => (CellType)255,
    };

    /// <summary>Parses the column letters of a cell reference such as <c>AB12</c>; -1 if there are none or more than three.</summary>
    internal static int ParseColumn(ReadOnlySpan<byte> reference)
    {
        var column = 0;
        var letters = 0;
        foreach (var b in reference)
        {
            var upper = b & ~0x20;
            if (upper is < 'A' or > 'Z')
            {
                break;
            }

            if (++letters > 3)
            {
                return -1;
            }

            column = (column * 26) + (upper - 'A' + 1);
        }

        return letters > 0 ? column : -1;
    }

    /// <summary>Parses a non-negative decimal integer that fits into an <see cref="int"/>; -1 otherwise.</summary>
    internal static int ParseDigits(ReadOnlySpan<byte> raw)
    {
        if (raw.IsEmpty || raw.Length > 10)
        {
            return -1;
        }

        long n = 0;
        foreach (var b in raw)
        {
            var digit = b - '0';
            if ((uint)digit > 9)
            {
                return -1;
            }

            n = (n * 10) + digit;
        }

        return n <= int.MaxValue ? (int)n : -1;
    }

    private static readonly double[] s_powersOf10 =
    [
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15,
    ];

    /// <summary>
    /// Parses a cell number. Plain decimals with up to 15 digits take a fast path: the digits form an exact
    /// integer and 10^k is exact, so one division gives the correctly rounded result (Clinger's fast path).
    /// </summary>
    internal static bool TryParseNumber(ReadOnlySpan<byte> raw, out double value)
    {
        var digits = raw.Length > 0 && raw[0] == '-' ? raw[1..] : raw;
        if (digits.Length is > 0 and <= 16)
        {
            long n = 0;
            var count = 0;
            var point = -1;
            var i = 0;
            for (; i < digits.Length; i++)
            {
                var digit = digits[i] - '0';
                if ((uint)digit <= 9)
                {
                    n = (n * 10) + digit;
                    count++;
                }
                else if (digits[i] == '.' && point < 0)
                {
                    point = i;
                }
                else
                {
                    break;
                }
            }

            if (i == digits.Length && count is > 0 and <= 15)
            {
                var result = point < 0 ? n : n / s_powersOf10[digits.Length - point - 1];
                value = digits.Length == raw.Length ? result : -result;
                return true;
            }
        }

        return double.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out value) && double.IsFinite(value);
    }
}
