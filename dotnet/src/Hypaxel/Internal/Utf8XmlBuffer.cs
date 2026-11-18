using System.Buffers;
using System.Globalization;
using System.Runtime.CompilerServices;
using System.Text.Unicode;

namespace Hypaxel.Internal;

/// <summary>
/// Minimal, allocation-free UTF-8 XML emitter. It writes raw bytes into a pooled buffer and forwards
/// full chunks to the target stream (a zip entry). Unlike <see cref="System.Xml.XmlWriter"/> it does
/// no validation, no namespace tracking and no state machine: callers emit well-formed markup.
/// </summary>
internal sealed class Utf8XmlBuffer : IDisposable
{
    // Largest single escape sequence we emit is "_x005F_" / "&quot;" (7 bytes); a UTF-8 scalar is at most 4.
    private const int MinimumFree = 32;

    private static readonly SearchValues<char> s_specialChars = SearchValues.Create(
        "&<>\"_\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u000B\u000C\u000E\u000F" +
        "\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001F￾￿");

    private byte[] _buffer;
    private int _pos;
    private Stream? _target;

    public Utf8XmlBuffer(int size)
    {
        _buffer = ArrayPool<byte>.Shared.Rent(size);
    }

    public Stream? Target
    {
        get => _target;
        set => _target = value;
    }

    /// <summary>Returns writable space of at least <paramref name="sizeHint"/> bytes; commit with <see cref="Advance"/>.</summary>
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public Span<byte> GetSpan(int sizeHint)
    {
        if (_buffer.Length - _pos < sizeHint)
        {
            Flush();
        }

        return _buffer.AsSpan(_pos);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void Advance(int count) => _pos += count;

    public void Flush()
    {
        if (_pos > 0)
        {
            _target!.Write(_buffer, 0, _pos);
            _pos = 0;
        }
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteRaw(ReadOnlySpan<byte> utf8)
    {
        if (utf8.Length <= _buffer.Length - _pos)
        {
            utf8.CopyTo(_buffer.AsSpan(_pos));
            _pos += utf8.Length;
        }
        else
        {
            WriteRawSlow(utf8);
        }
    }

    private void WriteRawSlow(ReadOnlySpan<byte> utf8)
    {
        Flush();
        if (utf8.Length > _buffer.Length)
        {
            _target!.Write(utf8);
            return;
        }

        utf8.CopyTo(_buffer.AsSpan(_pos));
        _pos += utf8.Length;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteByte(byte value)
    {
        if (_pos == _buffer.Length)
        {
            Flush();
        }

        _buffer[_pos++] = value;
    }

    public void Write(int value)
    {
        var span = GetSpan(MinimumFree);
        value.TryFormat(span, out var written, default, CultureInfo.InvariantCulture);
        _pos += written;
    }

    public void Write(long value)
    {
        var span = GetSpan(MinimumFree);
        value.TryFormat(span, out var written, default, CultureInfo.InvariantCulture);
        _pos += written;
    }

    public void Write(double value)
    {
        var span = GetSpan(MinimumFree);
        _pos += FormatDouble(value, span);
    }

    /// <summary>
    /// Formats a finite double with the shortest round-trippable representation. Integral values
    /// (very common: counts, ids, whole dates) skip the comparatively expensive float formatter.
    /// </summary>
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static int FormatDouble(double value, Span<byte> destination)
    {
        int written;
        if (value == Math.Truncate(value) && Math.Abs(value) < 9007199254740992.0)
        {
            ((long)value).TryFormat(destination, out written, default, CultureInfo.InvariantCulture);
        }
        else
        {
            value.TryFormat(destination, out written, default, CultureInfo.InvariantCulture);
        }

        return written;
    }

    public void Write(decimal value)
    {
        var span = GetSpan(MinimumFree * 2);
        value.TryFormat(span, out var written, default, CultureInfo.InvariantCulture);
        _pos += written;
    }

    /// <summary>
    /// Writes text escaped for use in XML element content or a double-quoted attribute value.
    /// Characters that XML 1.0 cannot represent are written with Excel's <c>_xHHHH_</c> escape;
    /// literal sequences that look like such an escape are protected with <c>_x005F_</c>.
    /// Unpaired surrogates are replaced with U+FFFD.
    /// </summary>
    public void WriteEscaped(ReadOnlySpan<char> text)
    {
        while (!text.IsEmpty)
        {
            var index = text.IndexOfAny(s_specialChars);
            if (index < 0)
            {
                WriteUtf8(text);
                return;
            }

            if (index > 0)
            {
                WriteUtf8(text[..index]);
            }

            var c = text[index];
            switch (c)
            {
                case '&':
                    WriteRaw("&amp;"u8);
                    break;
                case '<':
                    WriteRaw("&lt;"u8);
                    break;
                case '>':
                    WriteRaw("&gt;"u8);
                    break;
                case '"':
                    WriteRaw("&quot;"u8);
                    break;
                case '_':
                    if (LooksLikeExcelEscape(text[index..]))
                    {
                        WriteRaw("_x005F_"u8);
                    }
                    else
                    {
                        WriteByte((byte)'_');
                    }

                    break;
                default:
                    WriteExcelEscape(c);
                    break;
            }

            text = text[(index + 1)..];
        }
    }

    private static bool LooksLikeExcelEscape(ReadOnlySpan<char> s)
    {
        // _xHHHH_
        return s.Length >= 7
            && s[1] == 'x'
            && char.IsAsciiHexDigit(s[2])
            && char.IsAsciiHexDigit(s[3])
            && char.IsAsciiHexDigit(s[4])
            && char.IsAsciiHexDigit(s[5])
            && s[6] == '_';
    }

    private void WriteExcelEscape(char c)
    {
        var span = GetSpan(8);
        span[0] = (byte)'_';
        span[1] = (byte)'x';
        ((int)c).TryFormat(span[2..], out _, "X4", CultureInfo.InvariantCulture);
        span[6] = (byte)'_';
        _pos += 7;
    }

    private void WriteUtf8(ReadOnlySpan<char> text)
    {
        while (true)
        {
            var span = GetSpan(MinimumFree);
            var status = Utf8.FromUtf16(text, span, out var read, out var written, replaceInvalidSequences: true, isFinalBlock: true);
            _pos += written;
            if (status == OperationStatus.Done)
            {
                return;
            }

            text = text[read..];
            Flush();
        }
    }

    public void Dispose()
    {
        var buffer = _buffer;
        if (buffer.Length != 0)
        {
            _buffer = [];
            _pos = 0;
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }
}
