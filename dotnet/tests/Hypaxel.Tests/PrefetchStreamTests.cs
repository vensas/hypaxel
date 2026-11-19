using System.Diagnostics;
using Hypaxel.Internal.Reading;

namespace Hypaxel.Tests;

public class PrefetchStreamTests
{
    /// <summary>Produces a byte pattern, optionally failing after a number of bytes; counts what was requested.</summary>
    private sealed class Source(long length, long failAfter = long.MaxValue) : Stream
    {
        private long _position;

        public long Produced => Interlocked.Read(ref _position);

        public bool Disposed { get; private set; }

        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_position >= failAfter)
            {
                throw XlsxReadException.Malformed("xl/worksheets/sheet1.xml", "CRC-32 mismatch: the ZIP entry is damaged.");
            }

            var n = (int)Math.Min(Math.Min(count, length - _position), failAfter - _position);
            for (var i = 0; i < n; i++)
            {
                buffer[offset + i] = (byte)((_position + i) % 251);
            }

            Interlocked.Add(ref _position, n);
            return n;
        }

        protected override void Dispose(bool disposing)
        {
            Disposed = true;
            base.Dispose(disposing);
        }

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    [Fact]
    public void DeliversAllBytesInOrder()
    {
        const long length = (PrefetchStream.ChunkSize * 9L) + 123;
        using var stream = new PrefetchStream(new Source(length));
        var buffer = new byte[10_007];
        long total = 0;
        int read;
        while ((read = stream.Read(buffer)) > 0)
        {
            for (var i = 0; i < read; i++)
            {
                Assert.Equal((byte)((total + i) % 251), buffer[i]);
            }

            total += read;
        }

        Assert.Equal(length, total);
        Assert.Equal(0, stream.Read(buffer));
    }

    [Fact]
    public void RethrowsErrorsOnTheReadingThreadAfterThePrecedingData()
    {
        const long failAfter = (PrefetchStream.ChunkSize * 2L) + 1000;
        using var stream = new PrefetchStream(new Source(long.MaxValue, failAfter));
        var buffer = new byte[4096];
        long total = 0;
        var error = Assert.Throws<XlsxReadException>((Action)(() =>
        {
            while (true)
            {
                total += stream.Read(buffer);
            }
        }));
        Assert.Equal(failAfter, total);
        Assert.Equal(XlsxReadErrorKind.Malformed, error.Kind);
        Assert.Equal("xl/worksheets/sheet1.xml", error.Part);
        Assert.Contains("CRC-32", error.Message);
        Assert.Throws<XlsxReadException>(() => stream.Read(buffer));
    }

    [Fact]
    public void ReadsAheadABoundedAmountAndStopsPromptlyOnDispose()
    {
        var source = new Source(long.MaxValue);
        var stream = new PrefetchStream(source);
        stream.ReadExactly(new byte[100]);
        var bound = (long)PrefetchStream.ChunkSize * (PrefetchStream.ChunkCount + 1);
        var watch = Stopwatch.StartNew();
        while (source.Produced < (long)PrefetchStream.ChunkSize * PrefetchStream.ChunkCount && watch.ElapsedMilliseconds < 5_000)
        {
            Thread.Sleep(5);
        }

        Thread.Sleep(50);
        Assert.InRange(source.Produced, 1, bound);

        watch.Restart();
        stream.Dispose();
        Assert.True(watch.ElapsedMilliseconds < 2_000, $"Dispose took {watch.ElapsedMilliseconds} ms");
        Assert.True(source.Disposed);
        Assert.Throws<ObjectDisposedException>(() => stream.Read(new byte[1]));
    }

    [Fact]
    public void ReportsDamagedWorksheetsLikeTheSynchronousPath()
    {
        var bytes = XlsxReaderTests.Write(w =>
        {
            var s = w.AddWorksheet("S");
            for (var i = 0; i < 50_000; i++)
            {
                s.AddRow(i, "text", i * 0.5);
            }
        });

        // Flip a byte late in the worksheet's compressed data, so that inflate or the CRC check fails mid-sheet.
        var entryStart = bytes.AsSpan().IndexOf("xl/worksheets/sheet1.xml"u8);
        var damaged = (byte[])bytes.Clone();
        damaged[entryStart + 200_000] ^= 0x5A;

        string Error(bool background)
        {
            using var reader = new XlsxReader(new MemoryStream(damaged), new XlsxReaderOptions { BackgroundDecompression = background });
            using var rows = reader.OpenWorksheet(0);
            var error = Assert.Throws<XlsxReadException>(() =>
            {
                while (rows.Read())
                {
                }
            });
            return $"{error.Kind} {error.Part} {error.Message}";
        }

        Assert.Equal(Error(false), Error(true));
    }
}
