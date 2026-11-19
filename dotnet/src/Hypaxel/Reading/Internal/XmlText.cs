using System.Buffers;
using System.Globalization;
using System.Text;
using System.Text.Unicode;

namespace Hypaxel.Internal.Reading;

/// <summary>A growable UTF-16 buffer that is reused between rows.</summary>
internal sealed class CharBuffer(int capacity = 256)
{
    private char[] _chars = new char[capacity];

    public int Length { get; private set; }

    public ReadOnlySpan<char> Span => _chars.AsSpan(0, Length);

    public ReadOnlySpan<char> Slice(int start, int length) => _chars.AsSpan(start, length);

    public void Clear() => Length = 0;

    public void Truncate(int length) => Length = length;

    public Span<char> GetSpan(int sizeHint)
    {
        if (_chars.Length - Length < sizeHint)
        {
            var size = (int)Math.Min(Math.Max((long)_chars.Length * 2, (long)Length + sizeHint), Array.MaxLength);
            if (size - Length < sizeHint)
            {
                throw new OutOfMemoryException();
            }

            Array.Resize(ref _chars, size);
        }

        return _chars.AsSpan(Length);
    }

    public void Advance(int count) => Length += count;

    public void Append(char c)
    {
        GetSpan(1)[0] = c;
        Length++;
    }

    public void Append(ReadOnlySpan<char> text)
    {
        text.CopyTo(GetSpan(text.Length));
        Length += text.Length;
    }

    public Span<char> Writable(int start) => _chars.AsSpan(start, Length - start);
}

/// <summary>Decoding of XML character data.</summary>
internal static class XmlText
{
    private static readonly SearchValues<byte> s_entityOrCr = SearchValues.Create("&\r"u8);

    /// <summary>
    /// Appends raw UTF-8 character data to <paramref name="output"/>. With <paramref name="entities"/>, entity and
    /// character references are resolved and line endings normalized (text content); without, the bytes are
    /// taken as they are (CDATA). With <paramref name="excelEscapes"/>, Excel's <c>_xHHHH_</c> escapes are
    /// decoded as well. Returns an error message, or null on success; on error the output is left unchanged.
    /// </summary>
    public static string? Decode(CharBuffer output, ReadOnlySpan<byte> raw, bool entities, bool excelEscapes)
    {
        var start = output.Length;
        var error = entities ? DecodeEntities(output, raw) : Transcode(output, raw);
        if (error is not null)
        {
            output.Truncate(start);
            return error;
        }

        if (excelEscapes)
        {
            DecodeExcelEscapes(output, start);
        }

        return null;
    }

    /// <summary>Decodes an attribute value (entities, no Excel escapes); null if it is malformed.</summary>
    public static string? DecodeAttribute(ReadOnlySpan<byte> raw)
    {
        if (raw.IndexOfAny(s_entityOrCr) < 0)
        {
            try
            {
                return new UTF8Encoding(false, true).GetString(raw);
            }
            catch (DecoderFallbackException)
            {
                return null;
            }
        }

        var buffer = new CharBuffer(raw.Length);
        return DecodeEntities(buffer, raw) is null ? buffer.Span.ToString() : null;
    }

    private static string? Transcode(CharBuffer output, ReadOnlySpan<byte> raw)
    {
        if (raw.IsEmpty)
        {
            return null;
        }

        var status = Utf8.ToUtf16(raw, output.GetSpan(raw.Length), out _, out var written, replaceInvalidSequences: false);
        if (status != OperationStatus.Done)
        {
            return "Text is not valid UTF-8.";
        }

        output.Advance(written);
        return null;
    }

    private static string? DecodeEntities(CharBuffer output, ReadOnlySpan<byte> raw)
    {
        while (true)
        {
            var next = raw.IndexOfAny(s_entityOrCr);
            if (next < 0)
            {
                return Transcode(output, raw);
            }

            if (Transcode(output, raw[..next]) is { } error)
            {
                return error;
            }

            raw = raw[next..];
            if (raw[0] == '\r')
            {
                // XML end-of-line handling: CR LF and a lone CR become LF.
                output.Append('\n');
                raw = raw[(raw.Length > 1 && raw[1] == '\n' ? 2 : 1)..];
                continue;
            }

            var semi = raw.IndexOf((byte)';');
            if (semi < 0 || semi > 12)
            {
                return "Unterminated entity reference.";
            }

            var entity = raw[1..semi];
            raw = raw[(semi + 1)..];
            if (entity.SequenceEqual("lt"u8))
            {
                output.Append('<');
            }
            else if (entity.SequenceEqual("gt"u8))
            {
                output.Append('>');
            }
            else if (entity.SequenceEqual("amp"u8))
            {
                output.Append('&');
            }
            else if (entity.SequenceEqual("quot"u8))
            {
                output.Append('"');
            }
            else if (entity.SequenceEqual("apos"u8))
            {
                output.Append('\'');
            }
            else
            {
                if (entity.Length < 2 || entity[0] != '#')
                {
                    return "Unknown entity reference.";
                }

                var hex = entity[1] is (byte)'x' or (byte)'X';
                var digits = hex ? entity[2..] : entity[1..];
                if (digits.IsEmpty || digits.Length > 8
                    || !uint.TryParse(digits, hex ? NumberStyles.AllowHexSpecifier : NumberStyles.None, CultureInfo.InvariantCulture, out var code)
                    || !Rune.TryCreate(code, out var rune))
                {
                    return "Unknown entity reference.";
                }

                output.Advance(rune.EncodeToUtf16(output.GetSpan(2)));
            }
        }
    }

    /// <summary>
    /// Decodes <c>_xHHHH_</c> escapes in <c>output[start..]</c> in place. Unpaired surrogates are left as
    /// written; <c>_x005F_</c> is an escaped underscore.
    /// </summary>
    private static void DecodeExcelEscapes(CharBuffer output, int start)
    {
        var chars = output.Writable(start);
        var first = chars.IndexOf("_x");
        if (first < 0)
        {
            return;
        }

        var write = first;
        var i = first;
        while (i < chars.Length)
        {
            if (TryParseEscape(chars, i, out var code))
            {
                if (code is >= 0xD800 and < 0xDC00)
                {
                    if (TryParseEscape(chars, i + 7, out var low) && low is >= 0xDC00 and < 0xE000)
                    {
                        chars[write++] = (char)code;
                        chars[write++] = (char)low;
                        i += 14;
                        continue;
                    }
                }
                else if (code is < 0xDC00 or >= 0xE000)
                {
                    chars[write++] = (char)code;
                    i += 7;
                    continue;
                }
            }

            chars[write++] = chars[i++];
        }

        output.Truncate(start + write);
    }

    private static bool TryParseEscape(ReadOnlySpan<char> chars, int i, out int code)
    {
        code = 0;
        if (i + 7 > chars.Length || chars[i] != '_' || chars[i + 1] != 'x' || chars[i + 6] != '_')
        {
            return false;
        }

        for (var k = i + 2; k < i + 6; k++)
        {
            var c = chars[k];
            var digit = char.IsAsciiDigit(c) ? c - '0' : char.IsAsciiHexDigitUpper(c) ? c - 'A' + 10 : char.IsAsciiHexDigitLower(c) ? c - 'a' + 10 : -1;
            if (digit < 0)
            {
                return false;
            }

            code = (code << 4) | digit;
        }

        return true;
    }
}
