using System.Buffers;
using System.Runtime.ExceptionServices;

namespace Hypaxel.Internal.Reading;

/// <summary>
/// Reads an inner stream (inflate + CRC) on a thread-pool task, a bounded number of chunks ahead of the
/// consumer, so that decompression overlaps parsing. Memory is bounded by the chunk ring. Errors from the
/// inner stream are rethrown on the reading thread, after the data that preceded them. While the ring is
/// full the producer waits asynchronously, so an abandoned instance holds no thread.
/// </summary>
internal sealed class PrefetchStream : Stream
{
    internal const int ChunkSize = 256 * 1024;
    internal const int ChunkCount = 4;

    private readonly Stream _inner;
    private readonly Chunk[] _ring = new Chunk[ChunkCount];
    private readonly SemaphoreSlim _filled = new(0, ChunkCount);
    private readonly SemaphoreSlim _free = new(ChunkCount, ChunkCount);
    private readonly CancellationTokenSource _cancel = new();
    private readonly Task _producer;
    private Chunk? _current;
    private int _readIndex;
    private int _offset;
    private bool _disposed;

    public PrefetchStream(Stream inner)
    {
        _inner = inner;
        for (var i = 0; i < ChunkCount; i++)
        {
            _ring[i] = new Chunk(ArrayPool<byte>.Shared.Rent(ChunkSize));
        }

        _producer = Task.Run(ProduceAsync);
    }

    private async Task ProduceAsync()
    {
        var token = _cancel.Token;
        var index = 0;
        try
        {
            while (true)
            {
                await _free.WaitAsync(token).ConfigureAwait(false);
                var chunk = _ring[index];
                index = (index + 1) % ChunkCount;
                chunk.Count = 0;
                try
                {
                    int read;
                    do
                    {
                        read = _inner.Read(chunk.Buffer, chunk.Count, ChunkSize - chunk.Count);
                        chunk.Count += read;
                    }
                    while (read > 0 && chunk.Count < ChunkSize && !token.IsCancellationRequested);

                    chunk.End = read == 0;
                }
                catch (Exception e)
                {
                    chunk.Error = ExceptionDispatchInfo.Capture(e);
                }

                _filled.Release();
                if (chunk.End || chunk.Error is not null)
                {
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    public override int Read(byte[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

    public override int Read(Span<byte> buffer)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        while (true)
        {
            if (_current is { } chunk)
            {
                if (_offset < chunk.Count)
                {
                    var n = Math.Min(buffer.Length, chunk.Count - _offset);
                    chunk.Buffer.AsSpan(_offset, n).CopyTo(buffer);
                    _offset += n;
                    return n;
                }

                chunk.Error?.Throw();
                if (chunk.End)
                {
                    return 0;
                }

                _current = null;
                _free.Release();
            }

            _filled.Wait();
            _current = _ring[_readIndex];
            _readIndex = (_readIndex + 1) % ChunkCount;
            _offset = 0;
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing && !_disposed)
        {
            _disposed = true;
            _cancel.Cancel();

            // The producer stops after the chunk it is inflating; the inner stream must not be in use when it is disposed.
            try
            {
                _producer.Wait();
            }
            catch (AggregateException)
            {
            }

            _inner.Dispose();
            foreach (var chunk in _ring)
            {
                ArrayPool<byte>.Shared.Return(chunk.Buffer);
            }

            _cancel.Dispose();
            _filled.Dispose();
            _free.Dispose();
        }

        base.Dispose(disposing);
    }

    public override bool CanRead => true;

    public override bool CanSeek => false;

    public override bool CanWrite => false;

    public override long Length => throw new NotSupportedException();

    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

    public override void Flush()
    {
    }

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

    public override void SetLength(long value) => throw new NotSupportedException();

    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    private sealed class Chunk(byte[] buffer)
    {
        public byte[] Buffer { get; } = buffer;

        public int Count { get; set; }

        public bool End { get; set; }

        public ExceptionDispatchInfo? Error { get; set; }
    }
}
