using System.Buffers.Binary;
using System.IO.Compression;
using System.Text;

namespace Hypaxel.Internal.Reading;

internal sealed class ZipEntry
{
    public required string Name { get; init; }

    public required ushort Method { get; init; }

    public required ushort Flags { get; init; }

    public required uint Crc { get; init; }

    public required long CompressedSize { get; init; }

    public required long UncompressedSize { get; init; }

    public required long HeaderOffset { get; init; }
}

/// <summary>
/// The central directory of a ZIP archive (including ZIP64). Entries are opened as streams whose declared
/// size and CRC-32 are enforced, so a damaged archive or one that lies about its sizes (a "zip bomb")
/// fails instead of producing unbounded or wrong output.
/// </summary>
internal sealed class ZipDirectory
{
    private const uint LocalHeaderSignature = 0x04034b50;
    private const uint CentralHeaderSignature = 0x02014b50;
    private const uint EndOfCentralDirectorySignature = 0x06054b50;
    private const uint Zip64EndSignature = 0x06064b50;
    private const uint Zip64LocatorSignature = 0x07064b50;
    private const int EndOfCentralDirectoryLength = 22;
    private const int MaxComment = 0xFFFF;
    private const long MaxCentralDirectory = 64 * 1024 * 1024;
    private const ushort MethodStored = 0;
    private const ushort MethodDeflate = 8;
    private const ushort FlagEncrypted = 1;

    private static ReadOnlySpan<byte> OleMagic => [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];

    private readonly Dictionary<string, ZipEntry> _exact;
    private readonly Dictionary<string, ZipEntry> _ignoreCase;

    private ZipDirectory(List<ZipEntry> entries)
    {
        _exact = new Dictionary<string, ZipEntry>(entries.Count, StringComparer.Ordinal);
        _ignoreCase = new Dictionary<string, ZipEntry>(entries.Count, StringComparer.OrdinalIgnoreCase);
        foreach (var entry in entries)
        {
            _exact.TryAdd(entry.Name, entry);
            _ignoreCase.TryAdd(entry.Name, entry);
        }
    }

    public static ZipDirectory Read(Stream source)
    {
        var length = source.Length;
        if (length >= 8)
        {
            Span<byte> magic = stackalloc byte[8];
            ReadExactAt(source, 0, magic);
            if (magic.SequenceEqual(OleMagic))
            {
                throw XlsxReadException.InvalidFile(
                    "This is an OLE compound file: a legacy .xls workbook or a password-protected (encrypted) workbook. Only .xlsx files can be read.");
            }
        }

        if (length < EndOfCentralDirectoryLength)
        {
            throw XlsxReadException.InvalidFile("The input is too small to be a ZIP archive, so it is not an .xlsx file.");
        }

        var tailLength = (int)Math.Min(length, EndOfCentralDirectoryLength + MaxComment);
        var tailStart = length - tailLength;
        var tail = new byte[tailLength];
        ReadExactAt(source, tailStart, tail);
        var eocd = -1;
        for (var i = tailLength - EndOfCentralDirectoryLength; i >= 0; i--)
        {
            if (BinaryPrimitives.ReadUInt32LittleEndian(tail.AsSpan(i)) == EndOfCentralDirectorySignature)
            {
                eocd = i;
                break;
            }
        }

        if (eocd < 0)
        {
            throw XlsxReadException.InvalidFile("No ZIP end of central directory record was found: the input is not an .xlsx file (it may be CSV, HTML or a damaged download).");
        }

        var eocdOffset = tailStart + eocd;
        var e = tail.AsSpan(eocd);
        long count = BinaryPrimitives.ReadUInt16LittleEndian(e[10..]);
        long cdSize = BinaryPrimitives.ReadUInt32LittleEndian(e[12..]);
        long cdOffset = BinaryPrimitives.ReadUInt32LittleEndian(e[16..]);

        if ((count == 0xFFFF || cdSize == 0xFFFFFFFF || cdOffset == 0xFFFFFFFF) && eocdOffset >= 20)
        {
            Span<byte> locator = stackalloc byte[20];
            ReadExactAt(source, eocdOffset - 20, locator);
            if (BinaryPrimitives.ReadUInt32LittleEndian(locator) == Zip64LocatorSignature)
            {
                Span<byte> record = stackalloc byte[56];
                var recordOffset = BinaryPrimitives.ReadUInt64LittleEndian(locator[8..]);
                if (recordOffset > (ulong)length)
                {
                    throw XlsxReadException.InvalidFile("The ZIP64 end of central directory record lies outside the archive.");
                }

                ReadExactAt(source, (long)recordOffset, record);
                if (BinaryPrimitives.ReadUInt32LittleEndian(record) != Zip64EndSignature)
                {
                    throw XlsxReadException.InvalidFile("The ZIP64 end of central directory record is missing.");
                }

                count = (long)Math.Min(BinaryPrimitives.ReadUInt64LittleEndian(record[32..]), long.MaxValue);
                cdSize = (long)Math.Min(BinaryPrimitives.ReadUInt64LittleEndian(record[40..]), long.MaxValue);
                cdOffset = (long)Math.Min(BinaryPrimitives.ReadUInt64LittleEndian(record[48..]), long.MaxValue);
            }
        }

        if (cdSize > MaxCentralDirectory)
        {
            throw XlsxReadException.LimitExceeded(null, $"The ZIP central directory is {cdSize} bytes; the limit is {MaxCentralDirectory}.");
        }

        if (cdOffset < 0 || cdOffset + cdSize > eocdOffset)
        {
            throw XlsxReadException.InvalidFile("The ZIP central directory lies outside the archive.");
        }

        var cd = new byte[cdSize];
        ReadExactAt(source, cdOffset, cd);

        var entries = new List<ZipEntry>((int)Math.Min(count, cdSize / 46));
        var at = 0;
        while (at + 46 <= cd.Length)
        {
            var h = cd.AsSpan(at);
            if (BinaryPrimitives.ReadUInt32LittleEndian(h) != CentralHeaderSignature)
            {
                break;
            }

            var flags = BinaryPrimitives.ReadUInt16LittleEndian(h[8..]);
            var method = BinaryPrimitives.ReadUInt16LittleEndian(h[10..]);
            var crc = BinaryPrimitives.ReadUInt32LittleEndian(h[16..]);
            ulong compressed = BinaryPrimitives.ReadUInt32LittleEndian(h[20..]);
            ulong uncompressed = BinaryPrimitives.ReadUInt32LittleEndian(h[24..]);
            int nameLength = BinaryPrimitives.ReadUInt16LittleEndian(h[28..]);
            int extraLength = BinaryPrimitives.ReadUInt16LittleEndian(h[30..]);
            int commentLength = BinaryPrimitives.ReadUInt16LittleEndian(h[32..]);
            ulong headerOffset = BinaryPrimitives.ReadUInt32LittleEndian(h[42..]);
            var end = 46 + nameLength + extraLength + commentLength;
            if (h.Length < end)
            {
                throw XlsxReadException.InvalidFile("A ZIP central directory entry is truncated.");
            }

            var name = Encoding.UTF8.GetString(h.Slice(46, nameLength)).Replace('\\', '/');
            var extra = h.Slice(46 + nameLength, extraLength);
            while (extra.Length >= 4)
            {
                var id = BinaryPrimitives.ReadUInt16LittleEndian(extra);
                var size = Math.Min((int)BinaryPrimitives.ReadUInt16LittleEndian(extra[2..]), extra.Length - 4);
                if (id == 0x0001)
                {
                    var field = extra.Slice(4, size);
                    uncompressed = Take(uncompressed, ref field);
                    compressed = Take(compressed, ref field);
                    headerOffset = Take(headerOffset, ref field);
                }

                extra = extra[(4 + size)..];
            }

            entries.Add(new ZipEntry
            {
                Name = name,
                Method = method,
                Flags = flags,
                Crc = crc,
                CompressedSize = (long)Math.Min(compressed, long.MaxValue),
                UncompressedSize = (long)Math.Min(uncompressed, long.MaxValue),
                HeaderOffset = (long)Math.Min(headerOffset, long.MaxValue),
            });
            at += end;
        }

        if (entries.Count == 0)
        {
            throw XlsxReadException.InvalidFile("The ZIP archive has no entries.");
        }

        return new ZipDirectory(entries);

        static ulong Take(ulong value, ref Span<byte> field)
        {
            if (value != 0xFFFFFFFF || field.Length < 8)
            {
                return value;
            }

            var wide = BinaryPrimitives.ReadUInt64LittleEndian(field);
            field = field[8..];
            return wide;
        }
    }

    /// <summary>Finds an entry by its part name, ignoring a leading <c>/</c> and, as a fallback, ASCII case.</summary>
    public ZipEntry? Find(string name)
    {
        name = name.TrimStart('/');
        return _exact.TryGetValue(name, out var entry) || _ignoreCase.TryGetValue(name, out entry) ? entry : null;
    }

    /// <summary>Opens an entry for streaming decompression.</summary>
    public static Stream Open(ZipEntry entry, Stream source)
    {
        if ((entry.Flags & FlagEncrypted) != 0)
        {
            throw XlsxReadException.Unsupported(entry.Name, "The ZIP entry is encrypted.");
        }

        if (entry.Method != MethodStored && entry.Method != MethodDeflate)
        {
            throw XlsxReadException.Unsupported(entry.Name, $"The ZIP entry uses compression method {entry.Method}; only stored and deflated entries are supported.");
        }

        Span<byte> header = stackalloc byte[30];
        ReadExactAt(source, entry.HeaderOffset, header);
        if (BinaryPrimitives.ReadUInt32LittleEndian(header) != LocalHeaderSignature)
        {
            throw XlsxReadException.Malformed(entry.Name, "The local header of the ZIP entry is missing.");
        }

        var dataStart = entry.HeaderOffset + 30 + BinaryPrimitives.ReadUInt16LittleEndian(header[26..]) + BinaryPrimitives.ReadUInt16LittleEndian(header[28..]);
        var raw = new SubReadStream(source, dataStart, entry.CompressedSize);
        Stream inner = entry.Method == MethodDeflate ? new DeflateStream(raw, CompressionMode.Decompress) : raw;
        return new EntryStream(inner, entry);
    }

    private static void ReadExactAt(Stream source, long offset, Span<byte> buffer)
    {
        if (offset < 0 || offset > source.Length - buffer.Length)
        {
            throw XlsxReadException.InvalidFile("The ZIP archive is truncated.");
        }

        source.Position = offset;
        source.ReadExactly(buffer);
    }
}

/// <summary>A window of the archive. Seeks before every read, so several entries can share one source stream.</summary>
internal sealed class SubReadStream(Stream source, long start, long length) : Stream
{
    private readonly long _end = start + length;
    private long _position = start;

    public override bool CanRead => true;

    public override bool CanSeek => false;

    public override bool CanWrite => false;

    public override long Length => throw new NotSupportedException();

    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

    public override int Read(byte[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

    public override int Read(Span<byte> buffer)
    {
        var remaining = _end - _position;
        if (remaining <= 0 || buffer.IsEmpty)
        {
            return 0;
        }

        if (buffer.Length > remaining)
        {
            buffer = buffer[..(int)remaining];
        }

        if (source.Position != _position)
        {
            source.Position = _position;
        }

        var read = source.Read(buffer);
        _position += read;
        return read;
    }

    public override void Flush()
    {
    }

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

    public override void SetLength(long value) => throw new NotSupportedException();

    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}

/// <summary>
/// Decompressed contents of one ZIP entry. Fails if the entry produces more bytes than declared, ends
/// early, or its CRC-32 does not match.
/// </summary>
internal sealed class EntryStream(Stream inner, ZipEntry entry) : Stream
{
    private uint _crc = 0xFFFFFFFF;
    private long _produced;

    public string Name => entry.Name;

    public override bool CanRead => true;

    public override bool CanSeek => false;

    public override bool CanWrite => false;

    public override long Length => throw new NotSupportedException();

    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

    public override int Read(byte[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

    public override int Read(Span<byte> buffer)
    {
        if (buffer.IsEmpty)
        {
            return 0;
        }

        var remaining = entry.UncompressedSize - _produced;

        // Ask for one byte more than declared so that oversized output is detected.
        if (buffer.Length - 1 > remaining)
        {
            buffer = buffer[..(int)(remaining + 1)];
        }

        int read;
        try
        {
            read = inner.Read(buffer);
        }
        catch (InvalidDataException e)
        {
            throw XlsxReadException.Malformed(entry.Name, $"The ZIP entry has invalid deflate data ({e.Message})");
        }

        if (read > remaining)
        {
            throw XlsxReadException.Malformed(entry.Name, "The ZIP entry is larger than its declared size.");
        }

        _produced += read;
        _crc = Crc32.Update(_crc, buffer[..read]);
        if (read == 0)
        {
            if (_produced != entry.UncompressedSize)
            {
                throw XlsxReadException.Malformed(entry.Name, "The ZIP entry is shorter than its declared size.");
            }

            if (~_crc != entry.Crc)
            {
                throw XlsxReadException.Malformed(entry.Name, "CRC-32 mismatch: the ZIP entry is damaged.");
            }
        }

        return read;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            inner.Dispose();
        }

        base.Dispose(disposing);
    }

    public override void Flush()
    {
    }

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

    public override void SetLength(long value) => throw new NotSupportedException();

    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}
