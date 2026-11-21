using System.Buffers;

namespace Hypaxel.Internal.Reading;

internal enum CellShape : byte
{
    /// <summary><c>&lt;c …&gt;&lt;v&gt;text&lt;/v&gt;&lt;/c&gt;</c></summary>
    Value,

    /// <summary><c>&lt;c …&gt;&lt;is&gt;&lt;t&gt;text&lt;/t&gt;&lt;/is&gt;&lt;/c&gt;</c></summary>
    Inline,

    /// <summary><c>&lt;c …/&gt;</c> or <c>&lt;c …&gt;&lt;/c&gt;</c></summary>
    Empty,
}

/// <summary>A cell matched by <see cref="Utf8XmlReader.TryScanCell"/>: buffer ranges of its attributes and raw text (-1 start when absent).</summary>
internal struct ScannedCell
{
    public CellShape Shape;
    public int ReferenceStart;
    public int ReferenceLength;
    public int TypeStart;
    public int TypeLength;
    public int StyleStart;
    public int StyleLength;
    public int TextStart;
    public int TextLength;
}

internal enum XmlToken : byte
{
    Start,
    Empty,
    End,
    Text,
    CData,
    Eof,
}

/// <summary>
/// Minimal pull parser for the XML that spreadsheet packages contain. It reads UTF-8 bytes directly,
/// ignores namespaces by matching local names, rejects document type declarations (so entity expansion
/// attacks are impossible) and bounds every token.
/// </summary>
/// <remarks>
/// <see cref="Name"/>, <see cref="Attributes"/> and <see cref="Value"/> point into the internal buffer
/// and are only valid until the next call that reads input.
/// </remarks>
internal sealed class Utf8XmlReader : IDisposable
{
    private const int InitialBuffer = 64 * 1024;

    /// <summary>Bytes kept buffered ahead of a fast-path match, so that a whole cell fits.</summary>
    private const int CellLookahead = 4096;

    /// <summary>Largest single token (a tag or a run of text). Excel limits cell text to 32,767 characters.</summary>
    internal const int MaxToken = 16 * 1024 * 1024;

    private static readonly SearchValues<byte> s_whitespace = SearchValues.Create(" \t\r\n\f"u8);
    private static readonly SearchValues<byte> s_tagEndOrQuote = SearchValues.Create(">\"'"u8);

    private readonly Stream _source;
    private byte[] _buffer;
    private int _pos;
    private int _end;
    private bool _eof;
    private bool _started;
    private int _depth;
    private int _nameStart;
    private int _nameLength;
    private int _valueStart;
    private int _valueLength;

    public Utf8XmlReader(Stream source, string part, int initialBuffer = InitialBuffer)
    {
        _source = source;
        Part = part;
        _buffer = ArrayPool<byte>.Shared.Rent(initialBuffer);
    }

    public string Part { get; }

    /// <summary>Number of open elements.</summary>
    public int Depth => _depth;

    /// <summary>Local name of the current start, empty or end tag.</summary>
    public ReadOnlySpan<byte> Name => _buffer.AsSpan(_nameStart, _nameLength);

    /// <summary>Raw attribute text of the current start or empty tag.</summary>
    public ReadOnlySpan<byte> Attributes => _buffer.AsSpan(_valueStart, _valueLength);

    /// <summary>Raw (undecoded) content of the current text or CDATA token.</summary>
    public ReadOnlySpan<byte> Value => _buffer.AsSpan(_valueStart, _valueLength);

    public XlsxReadException Malformed(string message) => XlsxReadException.Malformed(Part, message);

    private XlsxReadException UnexpectedEof() => Malformed("Unexpected end of XML.");

    /// <summary>Reads more input, keeping <c>_pos.._end</c>. Returns false at the end of input.</summary>
    private bool Fill()
    {
        if (_eof)
        {
            return false;
        }

        if (_pos > 0)
        {
            _buffer.AsSpan(_pos, _end - _pos).CopyTo(_buffer);
            _end -= _pos;
            _pos = 0;
        }

        if (_end == _buffer.Length)
        {
            if (_buffer.Length >= MaxToken)
            {
                throw XlsxReadException.LimitExceeded(Part, $"The part contains an XML token larger than {MaxToken} bytes.");
            }

            var larger = ArrayPool<byte>.Shared.Rent(Math.Min(_buffer.Length * 2, MaxToken));
            _buffer.AsSpan(0, _end).CopyTo(larger);
            ArrayPool<byte>.Shared.Return(_buffer);
            _buffer = larger;
        }

        var read = _source.Read(_buffer, _end, _buffer.Length - _end);
        if (read == 0)
        {
            _eof = true;
            return false;
        }

        _end += read;
        return true;
    }

    /// <summary>
    /// Reads the rest of the input without parsing it, so that the source can verify what it delivers
    /// (for a ZIP entry, its size and CRC-32).
    /// </summary>
    public void Drain()
    {
        _pos = _end;
        if (_eof)
        {
            return;
        }

        var sink = _buffer.Length > 0 ? _buffer : new byte[8192];
        while (_source.Read(sink, 0, sink.Length) > 0)
        {
        }

        _pos = _end = 0;
        _eof = true;
    }

    /// <summary>Ensures that at least <paramref name="n"/> bytes are buffered after <c>_pos</c>.</summary>
    private bool Need(int n)
    {
        while (_end - _pos < n)
        {
            if (!Fill())
            {
                return false;
            }
        }

        return true;
    }

    /// <summary>Finds a byte at or after <c>_pos + from</c>, reading more input as needed. Returns the offset relative to <c>_pos</c>, or -1.</summary>
    private int Find(byte needle, int from)
    {
        while (true)
        {
            var available = _end - _pos;
            if (from < available)
            {
                var i = _buffer.AsSpan(_pos + from, available - from).IndexOf(needle);
                if (i >= 0)
                {
                    return from + i;
                }

                from = available;
            }

            if (!Fill())
            {
                return -1;
            }
        }
    }

    private int Find(ReadOnlySpan<byte> needle, int from)
    {
        while (true)
        {
            var available = _end - _pos;
            if (from < available)
            {
                var i = _buffer.AsSpan(_pos + from, available - from).IndexOf(needle);
                if (i >= 0)
                {
                    return from + i;
                }

                from = Math.Max(from, available - (needle.Length - 1));
            }

            if (!Fill())
            {
                return -1;
            }
        }
    }

    /// <summary>Finds the <c>&gt;</c> that closes a start tag, skipping <c>&gt;</c> inside quoted attribute values.</summary>
    private int FindTagEnd()
    {
        // Fast path: the first '>' closes the tag unless it sits inside a quoted value.
        var hay = _buffer.AsSpan(_pos + 1, _end - _pos - 1);
        var gt = hay.IndexOf((byte)'>');
        if (gt >= 0)
        {
            var tag = hay[..gt];
            if (!tag.Contains((byte)'\'') && (tag.Count((byte)'"') & 1) == 0)
            {
                return gt + 1;
            }
        }

        var at = 1;
        byte quote = 0;
        while (true)
        {
            var available = _end - _pos;
            while (at < available)
            {
                var rest = _buffer.AsSpan(_pos + at, available - at);
                if (quote != 0)
                {
                    var i = rest.IndexOf(quote);
                    if (i < 0)
                    {
                        at = available;
                        break;
                    }

                    at += i + 1;
                    quote = 0;
                }
                else
                {
                    var i = rest.IndexOfAny(s_tagEndOrQuote);
                    if (i < 0)
                    {
                        at = available;
                        break;
                    }

                    var b = rest[i];
                    if (b == '>')
                    {
                        return at + i;
                    }

                    quote = b;
                    at += i + 1;
                }
            }

            if (!Fill())
            {
                return -1;
            }
        }
    }

    /// <summary>Skips a UTF-8 byte order mark and rejects UTF-16, which spreadsheet producers do not use.</summary>
    private void CheckEncoding()
    {
        _started = true;
        Need(3);
        var head = _buffer.AsSpan(_pos, _end - _pos);
        if (head.StartsWith((ReadOnlySpan<byte>)[0xEF, 0xBB, 0xBF]))
        {
            _pos += 3;
        }
        else if (head.StartsWith((ReadOnlySpan<byte>)[0xFF, 0xFE]) || head.StartsWith((ReadOnlySpan<byte>)[0xFE, 0xFF]))
        {
            throw XlsxReadException.Unsupported(Part, "The part is encoded as UTF-16; only UTF-8 is supported.");
        }
    }

    public XmlToken Next()
    {
        if (!_started)
        {
            CheckEncoding();
        }

        while (true)
        {
            if (_pos == _end && !Fill())
            {
                if (_depth > 0)
                {
                    throw UnexpectedEof();
                }

                return XmlToken.Eof;
            }

            if (_buffer[_pos] != '<')
            {
                var length = Find((byte)'<', 0);
                if (length < 0)
                {
                    length = _end - _pos;
                }

                _valueStart = _pos;
                _valueLength = length;
                _pos += length;
                return XmlToken.Text;
            }

            if (!Need(2))
            {
                throw UnexpectedEof();
            }

            switch (_buffer[_pos + 1])
            {
                case (byte)'/':
                {
                    var close = Find((byte)'>', 2);
                    if (close < 0)
                    {
                        throw UnexpectedEof();
                    }

                    var begin = _pos;
                    _pos += close + 1;
                    if (_depth == 0)
                    {
                        throw Malformed("Unbalanced end tag.");
                    }

                    _depth--;
                    var name = _buffer.AsSpan(begin + 2, close - 2).TrimEnd(s_whitespaceBytes);
                    SetName(begin + 2, name);
                    return XmlToken.End;
                }

                case (byte)'?':
                {
                    var close = Find("?>"u8, 2);
                    if (close < 0)
                    {
                        throw UnexpectedEof();
                    }

                    _pos += close + 2;
                    continue;
                }

                case (byte)'!':
                {
                    Need(9);
                    var rest = _buffer.AsSpan(_pos, _end - _pos);
                    if (rest.StartsWith("<!--"u8))
                    {
                        var close = Find("-->"u8, 4);
                        if (close < 0)
                        {
                            throw UnexpectedEof();
                        }

                        _pos += close + 3;
                        continue;
                    }

                    if (rest.StartsWith("<![CDATA["u8))
                    {
                        var close = Find("]]>"u8, 9);
                        if (close < 0)
                        {
                            throw UnexpectedEof();
                        }

                        _valueStart = _pos + 9;
                        _valueLength = close - 9;
                        _pos += close + 3;
                        return XmlToken.CData;
                    }

                    if (rest.StartsWith("<!DOCTYPE"u8))
                    {
                        throw Malformed("Document type declarations (DTDs) are not allowed.");
                    }

                    throw Malformed("Unexpected markup declaration.");
                }

                default:
                {
                    var close = FindTagEnd();
                    if (close < 0)
                    {
                        throw UnexpectedEof();
                    }

                    var begin = _pos;
                    _pos += close + 1;
                    var innerStart = begin + 1;
                    var innerLength = close - 1;
                    var empty = innerLength > 0 && _buffer[innerStart + innerLength - 1] == '/';
                    if (empty)
                    {
                        innerLength--;
                    }

                    var inner = _buffer.AsSpan(innerStart, innerLength);
                    var nameEnd = inner.IndexOfAny(s_whitespace);
                    if (nameEnd < 0)
                    {
                        nameEnd = innerLength;
                    }

                    if (nameEnd == 0)
                    {
                        throw Malformed("An element has no name.");
                    }

                    SetName(innerStart, inner[..nameEnd]);
                    _valueStart = innerStart + nameEnd;
                    _valueLength = innerLength - nameEnd;
                    if (empty)
                    {
                        return XmlToken.Empty;
                    }

                    _depth++;
                    return XmlToken.Start;
                }
            }
        }
    }

    private static ReadOnlySpan<byte> s_whitespaceBytes => " \t\r\n\f"u8;

    private void SetName(int start, ReadOnlySpan<byte> qualified)
    {
        var colon = qualified.LastIndexOf((byte)':');
        _nameStart = start + colon + 1;
        _nameLength = qualified.Length - colon - 1;
    }

    /// <summary>
    /// For an element whose start tag was just read: if its content is plain text followed by its end tag,
    /// consumes both and returns the raw (undecoded) text. Otherwise consumes nothing.
    /// </summary>
    public bool TryReadRawText(out ReadOnlySpan<byte> text)
    {
        text = default;
        var lt = Find((byte)'<', 0);
        if (lt < 0 || !Need(lt + 2) || _buffer[_pos + lt + 1] != '/')
        {
            return false;
        }

        var gt = Find((byte)'>', lt + 2);
        if (gt < 0)
        {
            return false;
        }

        var begin = _pos;
        _pos += gt + 1;
        _depth--;
        text = _buffer.AsSpan(begin, lt);
        return true;
    }

    /// <summary>A range of the buffer returned by a scan; valid until the next call that reads input.</summary>
    public ReadOnlySpan<byte> Bytes(int start, int length) => _buffer.AsSpan(start, length);

    private void FillLookahead()
    {
        while (_end - _pos < CellLookahead && Fill())
        {
        }
    }

    /// <summary>
    /// Fast path for the cell shapes nearly all producers write. If a complete, unprefixed cell of one of
    /// these shapes starts at the current position (attributes separated by plain spaces, quoted values),
    /// consumes it and returns its ranges; otherwise consumes nothing, and the caller falls back to
    /// <see cref="Next"/>.
    /// </summary>
    public bool TryScanCell(out ScannedCell cell)
    {
        cell = new ScannedCell { ReferenceStart = -1, TypeStart = -1, StyleStart = -1 };
        if (!_started)
        {
            return false;
        }

        FillLookahead();
        var b = _buffer.AsSpan(0, _end);
        var e = b.Length;
        var i = _pos + 2;
        if (i >= e || b[_pos] != '<' || b[_pos + 1] != 'c' || b[i] is not ((byte)' ' or (byte)'>' or (byte)'/'))
        {
            return false;
        }

        while (true)
        {
            if (i >= e)
            {
                return false;
            }

            var x = b[i];
            if (x == ' ')
            {
                i++;
                continue;
            }

            if (x == '>')
            {
                i++;
                break;
            }

            if (x == '/')
            {
                if (i + 1 >= e || b[i + 1] != '>')
                {
                    return false;
                }

                cell.Shape = CellShape.Empty;
                _pos = i + 2;
                return true;
            }

            var nameStart = i;
            while (i < e && b[i] != '=')
            {
                if (b[i] is (byte)':' or (byte)'>' or (byte)'/' or (byte)' ' or (byte)'\t' or (byte)'\r' or (byte)'\n' or (byte)'\f')
                {
                    return false;
                }

                i++;
            }

            var nameLength = i - nameStart;
            i++;
            if (i >= e || b[i] is not ((byte)'"' or (byte)'\''))
            {
                return false;
            }

            var valueStart = i + 1;
            var length = b[valueStart..].IndexOf(b[i]);
            if (length < 0)
            {
                return false;
            }

            if (nameLength == 1)
            {
                switch (b[nameStart])
                {
                    case (byte)'r':
                        cell.ReferenceStart = valueStart;
                        cell.ReferenceLength = length;
                        break;
                    case (byte)'t':
                        cell.TypeStart = valueStart;
                        cell.TypeLength = length;
                        break;
                    case (byte)'s':
                        cell.StyleStart = valueStart;
                        cell.StyleLength = length;
                        break;
                }
            }

            i = valueStart + length + 1;
        }

        if (i + 4 > e || b[i] != '<')
        {
            return false;
        }

        if (b[i + 1] == 'v' && b[i + 2] == '>')
        {
            var start = i + 3;
            var close = b[start..].IndexOf((byte)'<');
            if (close < 0 || !b[(start + close)..].StartsWith("</v></c>"u8))
            {
                return false;
            }

            cell.Shape = CellShape.Value;
            cell.TextStart = start;
            cell.TextLength = close;
            _pos = start + close + 8;
            return true;
        }

        if (b[i + 1] == '/' && b[i + 2] == 'c' && b[i + 3] == '>')
        {
            cell.Shape = CellShape.Empty;
            _pos = i + 4;
            return true;
        }

        if (b[i + 1] == 'i' && b[i + 2] == 's' && b[i + 3] == '>'
            && ScanText(b, i + 4, "</t></is></c>"u8, out cell.TextStart, out cell.TextLength, out var after))
        {
            cell.Shape = CellShape.Inline;
            _pos = after;
            return true;
        }

        return false;
    }

    /// <summary>Fast path for <c>&lt;si&gt;&lt;t&gt;text&lt;/t&gt;&lt;/si&gt;</c> shared string items: consumes the item and returns its raw text range, or consumes nothing.</summary>
    public bool TryScanSharedString(out int textStart, out int textLength)
    {
        textStart = textLength = 0;
        if (!_started)
        {
            return false;
        }

        FillLookahead();
        var b = _buffer.AsSpan(0, _end);
        if (!b[_pos..].StartsWith("<si>"u8) || !ScanText(b, _pos + 4, "</t></si>"u8, out textStart, out textLength, out var after))
        {
            return false;
        }

        _pos = after;
        return true;
    }

    /// <summary>Matches <c>&lt;t&gt;text</c> or <c>&lt;t attributes&gt;text</c> at <paramref name="at"/>, followed by <paramref name="close"/>.</summary>
    private static bool ScanText(ReadOnlySpan<byte> b, int at, ReadOnlySpan<byte> close, out int start, out int length, out int after)
    {
        start = length = after = 0;
        if (!b[at..].StartsWith("<t"u8) || at + 2 >= b.Length)
        {
            return false;
        }

        var j = at + 2;
        if (b[j] == ' ')
        {
            byte quote = 0;
            while (true)
            {
                if (j >= b.Length)
                {
                    return false;
                }

                var x = b[j];
                if (quote == 0 && x == '>')
                {
                    break;
                }

                if (quote != 0 && x == quote)
                {
                    quote = 0;
                }
                else if (quote == 0 && x is (byte)'"' or (byte)'\'')
                {
                    quote = x;
                }

                j++;
            }

            if (b[j - 1] == '/')
            {
                return false;
            }
        }
        else if (b[j] != '>')
        {
            return false;
        }

        start = j + 1;
        var end = b[start..].IndexOf((byte)'<');
        if (end < 0 || !b[(start + end)..].StartsWith(close))
        {
            return false;
        }

        length = end;
        after = start + end + close.Length;
        return true;
    }

    /// <summary>
    /// Fast path for the common shapes of a cell, such as <c>&lt;v&gt;42&lt;/v&gt;&lt;/c&gt;</c> right after the
    /// cell's start tag: if the input continues with <paramref name="open"/>, plain text and
    /// <paramref name="close"/>, consumes all of it (including the enclosing element's end tag) and returns the
    /// raw text. Otherwise consumes nothing.
    /// </summary>
    public bool TryReadSimpleElement(ReadOnlySpan<byte> open, ReadOnlySpan<byte> close, out ReadOnlySpan<byte> text)
    {
        text = default;
        if (!Need(open.Length) || !_buffer.AsSpan(_pos, open.Length).SequenceEqual(open))
        {
            return false;
        }

        var lt = Find((byte)'<', open.Length);
        if (lt < 0 || !Need(lt + close.Length) || !_buffer.AsSpan(_pos + lt, close.Length).SequenceEqual(close))
        {
            return false;
        }

        text = _buffer.AsSpan(_pos + open.Length, lt - open.Length);
        _pos += lt + close.Length;
        _depth--;
        return true;
    }

    /// <summary>Skips the rest of the element whose start tag was just read.</summary>
    public void SkipElement()
    {
        var target = _depth - 1;
        while (true)
        {
            if (Next() == XmlToken.End && _depth == target)
            {
                return;
            }
        }
    }

    /// <summary>
    /// Appends the decoded text of the element whose start tag was just read and consumes its end tag.
    /// Text inside nested elements is included.
    /// </summary>
    public void ReadTextInto(CharBuffer output, bool excelEscapes)
    {
        var target = _depth - 1;
        while (true)
        {
            string? error = null;
            switch (Next())
            {
                case XmlToken.Text:
                    error = XmlText.Decode(output, Value, entities: true, excelEscapes);
                    break;
                case XmlToken.CData:
                    error = XmlText.Decode(output, Value, entities: false, excelEscapes);
                    break;
                case XmlToken.End when _depth == target:
                    return;
            }

            if (error is not null)
            {
                throw Malformed(error);
            }
        }
    }

    public void Dispose()
    {
        var buffer = _buffer;
        _buffer = [];
        if (buffer.Length > 0)
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }

        _source.Dispose();
    }
}

/// <summary>Iterates over the attributes of a tag as (local name, raw value) pairs. Raw values still contain entity references.</summary>
internal ref struct XmlAttributes(ReadOnlySpan<byte> attributes)
{
    private ReadOnlySpan<byte> _rest = attributes;

    public ReadOnlySpan<byte> Name { get; private set; }

    public ReadOnlySpan<byte> Value { get; private set; }

    public bool MoveNext()
    {
        while (NextRaw(out var name, out var value))
        {
            if (name.SequenceEqual("xmlns"u8) || name.StartsWith("xmlns:"u8))
            {
                continue;
            }

            var colon = name.LastIndexOf((byte)':');
            Name = name[(colon + 1)..];
            Value = value;
            return true;
        }

        return false;
    }

    public static bool TryGet(ReadOnlySpan<byte> attributes, ReadOnlySpan<byte> name, out ReadOnlySpan<byte> value)
    {
        var e = new XmlAttributes(attributes);
        while (e.MoveNext())
        {
            if (e.Name.SequenceEqual(name))
            {
                value = e.Value;
                return true;
            }
        }

        value = default;
        return false;
    }

    private bool NextRaw(out ReadOnlySpan<byte> name, out ReadOnlySpan<byte> value)
    {
        name = value = default;
        var s = _rest;
        var start = s.IndexOfAnyExcept(" \t\r\n\f"u8);
        if (start < 0)
        {
            return false;
        }

        var eq = s[start..].IndexOf((byte)'=');
        if (eq < 0)
        {
            return false;
        }

        eq += start;
        name = s[start..eq].TrimEnd(" \t\r\n\f"u8);
        var open = s[(eq + 1)..].IndexOfAnyExcept(" \t\r\n\f"u8);
        if (open < 0)
        {
            return false;
        }

        open += eq + 1;
        var quote = s[open];
        if (quote != '"' && quote != '\'')
        {
            _rest = default;
            return false;
        }

        var close = s[(open + 1)..].IndexOf(quote);
        if (close < 0)
        {
            return false;
        }

        close += open + 1;
        _rest = s[(close + 1)..];
        value = s[(open + 1)..close];
        return true;
    }
}
