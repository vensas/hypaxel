using System.IO.Compression;
using Hypaxel.Internal;
using Hypaxel.Styling;

namespace Hypaxel;

/// <summary>
/// Forward-only XLSX writer. Worksheets are written one after another straight into a zip stream;
/// nothing is kept in memory except the shared string table (optional) and the style table.
/// </summary>
/// <example>
/// <code>
/// await using var writer = new XlsxWriter(stream);
/// var bold = writer.AddStyle(new CellStyle { Font = new Font { Bold = true } });
/// var sheet = writer.AddWorksheet("Report");
/// await sheet.AddRowAsync(CellValue.From("Name", bold), CellValue.From("Total", bold));
/// await sheet.AddRowAsync("Alice", 42);
/// await writer.CompleteAsync();
/// </code>
/// </example>
public sealed partial class XlsxWriter : IAsyncDisposable, IDisposable
{
    private const long DaysTo1900 = 693593; // 1899-12-30, the OLE automation epoch
    private const long DaysTo1904 = 695055; // 1904-01-01

    private readonly Stream _destination;
    private readonly XlsxWriterOptions _options;
    private readonly BufferedOutputStream _output;
    private readonly ZipArchive _zip;
    private readonly Utf8XmlBuffer _xml;
    private readonly List<Worksheet> _sheets = [];
    private readonly HashSet<string> _sheetNames = new(StringComparer.OrdinalIgnoreCase);
    private Stream? _entryStream;
    private Worksheet? _current;
    private bool _completed;
    private bool _disposed;

    /// <summary>Creates a writer that streams the workbook to <paramref name="destination"/>.</summary>
    /// <param name="destination">Any writable stream; it does not need to be seekable (e.g. an HTTP response body).</param>
    /// <param name="options">Writer options, or null for the defaults.</param>
    public XlsxWriter(Stream destination, XlsxWriterOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(destination);
        if (!destination.CanWrite)
        {
            throw new ArgumentException("The destination stream must be writable.", nameof(destination));
        }

        _options = options ?? new XlsxWriterOptions();
        _options.Validate();
        _destination = destination;
        _output = new BufferedOutputStream(destination, _options.FlushThreshold + (_options.FlushThreshold / 2));
        _zip = new ZipArchive(_output, ZipArchiveMode.Create, leaveOpen: true);
        _xml = new Utf8XmlBuffer(_options.XmlBufferSize);
        SharedStrings = _options.StringStorage == StringStorage.SharedStrings ? new SharedStringTable() : null;
    }

    /// <summary>Creates a writer that writes a new file at <paramref name="path"/>.</summary>
    public static XlsxWriter Create(string path, XlsxWriterOptions? options = null)
    {
        var stream = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 1, FileOptions.Asynchronous);
        options ??= new XlsxWriterOptions();
        options.OwnsStream = true;
        return new XlsxWriter(stream, options);
    }

    internal StyleRegistry Styles { get; } = new();

    internal SharedStringTable? SharedStrings { get; }

    internal bool UsesFormulas { get; set; }

    internal bool WriteCellReferences => _options.WriteCellReferences;

    /// <summary>Worksheets added so far.</summary>
    public IReadOnlyList<Worksheet> Worksheets => _sheets;

    /// <summary>Registers a cell format and returns a handle to use when writing cells. Identical styles share a handle.</summary>
    public StyleId AddStyle(CellStyle style)
    {
        EnsureNotCompleted();
        return Styles.Add(style);
    }

    /// <summary>
    /// Adds a worksheet and makes it the current one. The previous worksheet is completed and can no
    /// longer be written to.
    /// </summary>
    public Worksheet AddWorksheet(string name, WorksheetOptions? options = null)
    {
        EnsureNotCompleted();
        ValidateSheetName(name);
        if (!_sheetNames.Add(name))
        {
            throw new ArgumentException($"A worksheet named '{name}' already exists (names are case-insensitive).", nameof(name));
        }

        CompleteCurrentWorksheet();
        var index = _sheets.Count;
        OpenEntry($"xl/worksheets/sheet{index + 1}.xml");
        var sheet = new Worksheet(this, _xml, name, index, options);
        _sheets.Add(sheet);
        _current = sheet;
        return sheet;
    }

    /// <summary>Finishes the workbook and flushes everything to the destination stream.</summary>
    public async ValueTask CompleteAsync(CancellationToken cancellationToken = default)
    {
        if (_completed)
        {
            return;
        }

        WriteRemainingParts();
        await _output.DrainAsync(cancellationToken).ConfigureAwait(false);
        await _destination.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Synchronous variant of <see cref="CompleteAsync"/>.</summary>
    public void Complete()
    {
        if (_completed)
        {
            return;
        }

        WriteRemainingParts();
        _output.Drain();
        _destination.Flush();
    }

    /// <summary>Pushes any buffered output to the destination stream.</summary>
    public ValueTask FlushAsync(CancellationToken cancellationToken = default) => _output.DrainAsync(cancellationToken);

    internal ValueTask DrainIfNeededAsync(CancellationToken cancellationToken)
    {
        return _output.BufferedBytes >= _options.FlushThreshold
            ? _output.DrainAsync(cancellationToken)
            : default;
    }

    internal void DrainIfNeeded()
    {
        if (_output.BufferedBytes >= _options.FlushThreshold)
        {
            _output.Drain();
        }
    }

    internal void EnsureCurrent(Worksheet sheet)
    {
        if (!ReferenceEquals(_current, sheet))
        {
            throw new InvalidOperationException($"Worksheet '{sheet.Name}' is not the current worksheet.");
        }
    }

    /// <summary>Converts ticks to the whole-day part of an Excel date serial; false when outside Excel's date range.</summary>
    internal bool TryGetSerialDay(long ticks, out long day)
    {
        var days = ticks / TimeSpan.TicksPerDay;
        if (_options.Use1904DateSystem)
        {
            day = days - DaysTo1904;
            return day >= 0 && day < 2_958_465 - 1462;
        }

        day = days - DaysTo1900;

        // Excel treats 1900 as a leap year: serials before 1900-03-01 are one lower than OLE dates.
        if (day < 61)
        {
            day--;
        }

        return day >= 1 && day <= 2_958_465; // 1900-01-01 .. 9999-12-31
    }

    // ------------------------------------------------------------------ internals

    private void OpenEntry(string name)
    {
        var entry = _zip.CreateEntry(name, _options.CompressionLevel);
        _entryStream = entry.Open();
        _xml.Target = _entryStream;
    }

    private void CloseEntry()
    {
        _xml.Flush();
        _entryStream?.Dispose();
        _entryStream = null;
        _xml.Target = null;
    }

    private void CompleteCurrentWorksheet()
    {
        if (_current is null)
        {
            return;
        }

        _current.Complete();
        CloseEntry();
        _current = null;
    }

    private void WriteRemainingParts()
    {
        if (_disposed)
        {
            throw new ObjectDisposedException(nameof(XlsxWriter));
        }

        if (_sheets.Count == 0)
        {
            AddWorksheet("Sheet1");
        }

        CompleteCurrentWorksheet();
        _completed = true;

        if (_sheets.TrueForAll(s => s.Hidden))
        {
            throw new InvalidOperationException("A workbook must contain at least one visible worksheet.");
        }

        var hasSharedStrings = SharedStrings is { UniqueCount: > 0 };
        if (hasSharedStrings)
        {
            OpenEntry("xl/sharedStrings.xml");
            SharedStrings!.WriteTo(_xml);
            CloseEntry();
        }

        OpenEntry("xl/styles.xml");
        Styles.WriteTo(_xml);
        CloseEntry();

        OpenEntry("xl/workbook.xml");
        WorkbookParts.WriteWorkbook(_xml, _sheets, UsesFormulas, _options.Use1904DateSystem);
        CloseEntry();

        OpenEntry("xl/_rels/workbook.xml.rels");
        WorkbookParts.WriteWorkbookRelationships(_xml, _sheets.Count, hasSharedStrings);
        CloseEntry();

        OpenEntry("[Content_Types].xml");
        WorkbookParts.WriteContentTypes(_xml, _sheets.Count, hasSharedStrings);
        CloseEntry();

        OpenEntry("_rels/.rels");
        WorkbookParts.WriteRootRelationships(_xml);
        CloseEntry();

        // Writes the zip central directory into the output buffer.
        _zip.Dispose();
    }

    private void EnsureNotCompleted()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_completed)
        {
            throw new InvalidOperationException("The workbook has been completed.");
        }
    }

    private static void ValidateSheetName(string name)
    {
        ArgumentException.ThrowIfNullOrEmpty(name);
        if (name.Length > 31)
        {
            throw new ArgumentException("Worksheet names cannot exceed 31 characters.", nameof(name));
        }

        if (name.AsSpan().IndexOfAny(@":\/?*[]") >= 0)
        {
            throw new ArgumentException(@"Worksheet names cannot contain : \ / ? * [ ]", nameof(name));
        }

        if (name[0] == '\'' || name[^1] == '\'')
        {
            throw new ArgumentException("Worksheet names cannot start or end with an apostrophe.", nameof(name));
        }

        if (string.Equals(name, "History", StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException("'History' is a reserved worksheet name.", nameof(name));
        }
    }

    /// <summary>Completes the workbook (if not yet completed) and releases pooled buffers.</summary>
    public async ValueTask DisposeAsync()
    {
        if (_disposed)
        {
            return;
        }

        try
        {
            if (!_completed)
            {
                await CompleteAsync().ConfigureAwait(false);
            }
        }
        finally
        {
            ReleaseResources();
            if (_options.OwnsStream)
            {
                await _destination.DisposeAsync().ConfigureAwait(false);
            }
        }
    }

    /// <summary>Completes the workbook (if not yet completed) and releases pooled buffers.</summary>
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        try
        {
            if (!_completed)
            {
                Complete();
            }
        }
        finally
        {
            ReleaseResources();
            if (_options.OwnsStream)
            {
                _destination.Dispose();
            }
        }
    }

    private void ReleaseResources()
    {
        _disposed = true;
        _entryStream?.Dispose();
        if (!_completed)
        {
            _zip.Dispose();
        }

        _xml.Dispose();
        _output.Dispose();
    }
}
