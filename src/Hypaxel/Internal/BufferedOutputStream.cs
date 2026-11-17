using System.Buffers;

namespace Hypaxel.Internal;

/// <summary>
/// Write-only, non-seekable stream that sits between <see cref="System.IO.Compression.ZipArchive"/>
/// and the user's destination stream. <c>ZipArchive</c> only performs synchronous writes, which
/// ASP.NET Core forbids on the response body by default. All compressed bytes therefore land in a
/// pooled in-memory buffer first and are pushed to the destination with real asynchronous I/O
/// whenever the buffer exceeds the configured threshold.
/// </summary>
internal sealed class BufferedOutputStream : Stream
{
    private readonly Stream _destination;
    private byte[] _buffer;
    private int _length;
    private long _position;

    public BufferedOutputStream(Stream destination, int initialCapacity)
    {
        _destination = destination;
        _buffer = ArrayPool<byte>.Shared.Rent(initialCapacity);
    }

    /// <summary>Number of bytes waiting to be written to the destination.</summary>
    public int BufferedBytes => _length;

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();

    public override long Position
    {
        get => _position;
        set => throw new NotSupportedException();
    }

    public override void Write(byte[] buffer, int offset, int count) => Write(buffer.AsSpan(offset, count));

    public override void Write(ReadOnlySpan<byte> buffer)
    {
        if (buffer.Length > _buffer.Length - _length)
        {
            Grow(buffer.Length);
        }

        buffer.CopyTo(_buffer.AsSpan(_length));
        _length += buffer.Length;
        _position += buffer.Length;
    }

    public override void WriteByte(byte value)
    {
        if (_length == _buffer.Length)
        {
            Grow(1);
        }

        _buffer[_length++] = value;
        _position++;
    }

    public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
    {
        Write(buffer.AsSpan(offset, count));
        return Task.CompletedTask;
    }

    public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
    {
        Write(buffer.Span);
        return default;
    }

    /// <summary>Intentionally a no-op: flushing to the destination is explicit via <see cref="DrainAsync"/>.</summary>
    public override void Flush()
    {
    }

    public override Task FlushAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    public ValueTask DrainAsync(CancellationToken cancellationToken)
    {
        if (_length == 0)
        {
            return default;
        }

        return DrainCoreAsync(cancellationToken);
    }

    private async ValueTask DrainCoreAsync(CancellationToken cancellationToken)
    {
        await _destination.WriteAsync(_buffer.AsMemory(0, _length), cancellationToken).ConfigureAwait(false);
        _length = 0;
    }

    public void Drain()
    {
        if (_length == 0)
        {
            return;
        }

        _destination.Write(_buffer, 0, _length);
        _length = 0;
    }

    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    private void Grow(int additional)
    {
        var newSize = Math.Max(_buffer.Length * 2, _length + additional);
        var newBuffer = ArrayPool<byte>.Shared.Rent(newSize);
        _buffer.AsSpan(0, _length).CopyTo(newBuffer);
        ArrayPool<byte>.Shared.Return(_buffer);
        _buffer = newBuffer;
    }

    protected override void Dispose(bool disposing)
    {
        var buffer = _buffer;
        if (buffer.Length != 0)
        {
            _buffer = [];
            _length = 0;
            ArrayPool<byte>.Shared.Return(buffer);
        }

        base.Dispose(disposing);
    }
}
