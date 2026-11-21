using Hypaxel.Internal.Reading;

namespace Hypaxel;

/// <summary>
/// Reads XLSX workbooks one row at a time.
/// </summary>
/// <remarks>
/// Opening a reader parses the package structure, the sheet list and the cell formats. Worksheet rows are
/// then streamed: memory use is bounded by the shared string table plus the widest row, whatever the
/// number of rows. The source must be seekable (a file or a <see cref="MemoryStream"/>), because the ZIP
/// directory sits at the end of the file. Reading is synchronous. Instances are not thread-safe; open one
/// reader per thread to read sheets in parallel.
/// </remarks>
/// <example>
/// <code>
/// using var reader = XlsxReader.Open("orders.xlsx");
/// using var rows = reader.OpenWorksheet("Orders");   // or OpenWorksheet(0)
/// while (rows.Read())
/// {
///     var row = rows.Row;                             // reused for every row
///     if (row.TryGetString(1, out var customer) &amp;&amp; row.TryGetDouble(3, out var amount))
///     {
///         Console.WriteLine($"{row.Number}: {customer} {amount}");
///     }
/// }
/// </code>
/// </example>
public sealed class XlsxReader : IDisposable
{
    private const int MaxTables = 4096;
    private const string NotAWorkbook = "The package has no workbook part; it may be a Word or PowerPoint file.";

    private readonly Stream _stream;
    private readonly bool _ownsStream;
    private readonly XlsxReaderOptions _options;
    private readonly ZipDirectory _directory;
    private readonly StyleTable _styles;
    private readonly string? _sharedStringsPart;
    private readonly SheetInfo[] _sheets;
    private string[]? _sharedStrings;
    private bool _disposed;

    /// <summary>Reads the workbook structure from <paramref name="stream"/>.</summary>
    /// <param name="stream">A readable, seekable stream positioned anywhere; the reader seeks as needed.</param>
    /// <param name="options">Reader options, or null for the defaults.</param>
    /// <exception cref="XlsxReadException">The input is not a readable XLSX workbook.</exception>
    public XlsxReader(Stream stream, XlsxReaderOptions? options = null)
        : this(stream, options ?? new XlsxReaderOptions(), (options?.OwnsStream).GetValueOrDefault())
    {
    }

    private XlsxReader(Stream stream, XlsxReaderOptions options, bool ownsStream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        options.Validate();
        if (!stream.CanRead || !stream.CanSeek)
        {
            throw new ArgumentException(
                "The stream must be readable and seekable. Copy a non-seekable stream (such as an HTTP request body) into a MemoryStream or a temporary file first.",
                nameof(stream));
        }

        _stream = stream;
        _ownsStream = ownsStream;
        _options = options;
        try
        {
            _directory = ZipDirectory.Read(stream);
            var rootRels = ReadRelationships("_rels/.rels");
            var workbookPart = rootRels?.Find(r => r.Is("officeDocument"))?.Target ?? "xl/workbook.xml";
            if (_directory.Find(workbookPart) is null)
            {
                throw XlsxReadException.InvalidFile(NotAWorkbook);
            }

            var rels = ReadRelationships(RelationshipsPath(workbookPart)) ?? [];
            var (is1904, listed) = ReadWorkbook(workbookPart);
            Is1904DateSystem = is1904;
            _sheets = new SheetInfo[listed.Count];
            for (var i = 0; i < listed.Count; i++)
            {
                var (name, visibility, relId) = listed[i];
                var rel = relId is null ? null : rels.Find(r => r.Id == relId);
                var kind = rel is null || rel.Is("worksheet") ? SheetKind.Worksheet
                    : rel.Is("chartsheet") ? SheetKind.Chartsheet
                    : SheetKind.Other;
                _sheets[i] = new SheetInfo(name, visibility, kind) { Part = rel?.Target };
            }

            var stylesPart = rels.Find(r => r.Is("styles"))?.Target;
            if (stylesPart is not null && _directory.Find(stylesPart) is not null)
            {
                using var xml = OpenXml(stylesPart);
                _styles = StyleTable.Parse(xml);
            }
            else
            {
                _styles = StyleTable.Empty;
            }

            _sharedStringsPart = rels.Find(r => r.Is("sharedStrings"))?.Target;
            if (_sharedStringsPart is not null && _directory.Find(_sharedStringsPart) is null)
            {
                _sharedStringsPart = null;
            }
        }
        catch
        {
            if (ownsStream)
            {
                stream.Dispose();
            }

            throw;
        }
    }

    /// <summary>Opens the workbook file at <paramref name="path"/>. The file is closed when the reader is disposed.</summary>
    /// <exception cref="XlsxReadException">The file is not a readable XLSX workbook.</exception>
    public static XlsxReader Open(string path, XlsxReaderOptions? options = null)
    {
        var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024);
        return new XlsxReader(stream, options ?? new XlsxReaderOptions(), ownsStream: true);
    }

    /// <summary>The sheets in workbook order, including hidden sheets and chart sheets.</summary>
    public IReadOnlyList<SheetInfo> Sheets => _sheets;

    /// <summary>The sheet names in workbook order.</summary>
    public IEnumerable<string> SheetNames => _sheets.Select(s => s.Name);

    /// <summary>True if the workbook uses the 1904 date system.</summary>
    public bool Is1904DateSystem { get; }

    /// <summary>
    /// Streams the rows of the sheet with this name, matched exactly first and then ignoring case.
    /// Several worksheets of one reader may be open at the same time (on one thread).
    /// </summary>
    /// <exception cref="KeyNotFoundException">No sheet has this name.</exception>
    /// <exception cref="XlsxReadException">The sheet is a chart sheet, or its part is missing or damaged.</exception>
    public WorksheetReader OpenWorksheet(string name)
    {
        return OpenWorksheet(IndexOf(name));
    }

    private int IndexOf(string name)
    {
        ArgumentNullException.ThrowIfNull(name);
        var index = Array.FindIndex(_sheets, s => string.Equals(s.Name, name, StringComparison.Ordinal));
        if (index < 0)
        {
            index = Array.FindIndex(_sheets, s => string.Equals(s.Name, name, StringComparison.OrdinalIgnoreCase));
        }

        if (index < 0)
        {
            throw new KeyNotFoundException($"The workbook has no sheet named '{name}'. Sheets: {string.Join(", ", _sheets.Select(s => $"'{s.Name}'"))}.");
        }

        return index;
    }

    /// <summary>Streams the rows of the sheet at a 0-based position in <see cref="Sheets"/>.</summary>
    /// <exception cref="ArgumentOutOfRangeException">There is no sheet at this position.</exception>
    /// <exception cref="XlsxReadException">The sheet is a chart sheet, or its part is missing or damaged.</exception>
    public WorksheetReader OpenWorksheet(int index)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if ((uint)index >= (uint)_sheets.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(index), index, $"The workbook has {_sheets.Length} sheet(s).");
        }

        var info = _sheets[index];
        if (info.Kind == SheetKind.Chartsheet)
        {
            throw XlsxReadException.Unsupported(info.Part, $"'{info.Name}' is a chart sheet and has no cells.");
        }

        if (info.Part is null)
        {
            throw XlsxReadException.Malformed("xl/workbook.xml", $"Sheet '{info.Name}' has no relationship to a worksheet part.");
        }

        if (_directory.Find(info.Part) is null)
        {
            throw XlsxReadException.Malformed(info.Part, $"The worksheet part of sheet '{info.Name}' is missing.");
        }

        var sharedStrings = LoadSharedStrings();
        return new WorksheetReader(info.Name, OpenXml(info.Part, _options.BackgroundDecompression), sharedStrings, _styles, Is1904DateSystem, _options.IncludeEmptyRows);
    }

    /// <summary>
    /// The Excel tables of the sheet with this name, matched exactly first and then ignoring case.
    /// </summary>
    /// <inheritdoc cref="GetTables(int)" path="/remarks"/>
    /// <exception cref="KeyNotFoundException">No sheet has this name.</exception>
    /// <exception cref="XlsxReadException">A table part is missing or damaged.</exception>
    public IReadOnlyList<ExcelTable> GetTables(string name) => GetTables(IndexOf(name));

    /// <summary>
    /// The Excel tables of the sheet at a 0-based position in <see cref="Sheets"/>, as the
    /// <see cref="ExcelTable"/> values that <see cref="Worksheet.AddTable"/> accepts.
    /// </summary>
    /// <remarks>
    /// Tables live in their own parts, which are read on each call; reading rows does not touch them.
    /// A table style that is not one of Excel's built-in styles is reported as <see cref="TableStyle.None"/>.
    /// </remarks>
    /// <exception cref="ArgumentOutOfRangeException">There is no sheet at this position.</exception>
    /// <exception cref="XlsxReadException">A table part is missing or damaged.</exception>
    public IReadOnlyList<ExcelTable> GetTables(int index)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if ((uint)index >= (uint)_sheets.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(index), index, $"The workbook has {_sheets.Length} sheet(s).");
        }

        var part = _sheets[index].Part;
        var rels = part is null ? null : ReadRelationships(RelationshipsPath(part));
        if (rels is null)
        {
            return [];
        }

        var tables = new List<ExcelTable>();
        foreach (var rel in rels)
        {
            if (!rel.Is("table"))
            {
                continue;
            }

            if (tables.Count >= MaxTables)
            {
                throw XlsxReadException.LimitExceeded(part, $"The sheet has more than {MaxTables} tables.");
            }

            using var xml = OpenXml(rel.Target);
            tables.Add(TableReader.Parse(xml));
        }

        return tables;
    }

    /// <summary>Number of entries in the shared string table (loaded with the first worksheet).</summary>
    public int SharedStringCount => LoadSharedStrings().Length;

    private string[] LoadSharedStrings()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_sharedStrings is null)
        {
            if (_sharedStringsPart is null)
            {
                _sharedStrings = [];
            }
            else
            {
                using var xml = OpenXml(_sharedStringsPart, _options.BackgroundDecompression);
                _sharedStrings = SharedStringTableReader.Parse(xml, _options.MaxSharedStringsBytes);
            }
        }

        return _sharedStrings;
    }

    /// <summary>Closes the stream if the reader owns it. Open <see cref="WorksheetReader"/>s become unusable.</summary>
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        if (_ownsStream)
        {
            _stream.Dispose();
        }
    }

    private Utf8XmlReader OpenXml(string part, bool background = false)
    {
        var entry = _directory.Find(part) ?? throw XlsxReadException.Malformed(part, "The part is missing.");
        var stream = ZipDirectory.Open(entry, _stream);
        return new Utf8XmlReader(background ? new PrefetchStream(stream) : stream, part);
    }

    private List<Relationship>? ReadRelationships(string part)
    {
        if (_directory.Find(part) is null)
        {
            return null;
        }

        var baseDir = SourceDirectory(part);
        var rels = new List<Relationship>();
        using var xml = OpenXml(part);
        while (true)
        {
            var token = xml.Next();
            if (token == XmlToken.Eof)
            {
                break;
            }

            if (token is not (XmlToken.Start or XmlToken.Empty) || !xml.Name.SequenceEqual("Relationship"u8))
            {
                continue;
            }

            var attributes = xml.Attributes;
            if (Attribute(attributes, "TargetMode"u8) is { } mode && mode.Equals("External", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (Attribute(attributes, "Id"u8) is { } id && Attribute(attributes, "Type"u8) is { } type && Attribute(attributes, "Target"u8) is { } target)
            {
                rels.Add(new Relationship(id, type, Resolve(baseDir, target)));
            }
        }

        return rels;
    }

    private (bool Is1904, List<(string Name, SheetVisibility Visibility, string? RelId)> Sheets) ReadWorkbook(string part)
    {
        using var xml = OpenXml(part);
        var is1904 = false;
        var sheets = new List<(string, SheetVisibility, string?)>();
        var root = true;
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
            if (root)
            {
                root = false;
                if (!name.SequenceEqual("workbook"u8))
                {
                    throw XlsxReadException.InvalidFile(NotAWorkbook);
                }
            }

            var attributes = xml.Attributes;
            if (name.SequenceEqual("workbookPr"u8))
            {
                is1904 = XmlAttributes.TryGet(attributes, "date1904"u8, out var raw) && (raw.SequenceEqual("1"u8) || raw.SequenceEqual("true"u8));
            }
            else if (name.SequenceEqual("sheet"u8))
            {
                var sheetName = Attribute(attributes, "name"u8) ?? throw XlsxReadException.Malformed(part, "A sheet has no name.");
                var visibility = XmlAttributes.TryGet(attributes, "state"u8, out var state)
                    ? state.SequenceEqual("hidden"u8) ? SheetVisibility.Hidden
                    : state.SequenceEqual("veryHidden"u8) ? SheetVisibility.VeryHidden
                    : SheetVisibility.Visible
                    : SheetVisibility.Visible;
                sheets.Add((sheetName, visibility, Attribute(attributes, "id"u8)));
            }
        }

        return (is1904, sheets);
    }

    private static string? Attribute(ReadOnlySpan<byte> attributes, ReadOnlySpan<byte> name) =>
        XmlAttributes.TryGet(attributes, name, out var raw) ? XmlText.DecodeAttribute(raw) : null;

    /// <summary>
    /// The directory of the part a relationships part belongs to, e.g. <c>xl/</c> for
    /// <c>xl/_rels/workbook.xml.rels</c>; targets are relative to it.
    /// </summary>
    internal static string SourceDirectory(string relsPart)
    {
        var slash = relsPart.LastIndexOf('/');
        var dir = slash < 0 ? string.Empty : relsPart[..slash];
        return dir.EndsWith("_rels", StringComparison.Ordinal) ? dir[..^5] : dir.Length == 0 ? dir : dir + "/";
    }

    internal static string RelationshipsPath(string part)
    {
        var slash = part.LastIndexOf('/');
        return slash < 0 ? $"_rels/{part}.rels" : $"{part[..slash]}/_rels/{part[(slash + 1)..]}.rels";
    }

    /// <summary>Resolves a relationship target against the source part's directory.</summary>
    internal static string Resolve(string baseDir, string target)
    {
        var joined = target.StartsWith('/') ? target[1..] : baseDir + target;
        var parts = new List<string>();
        foreach (var segment in joined.Split('/', '\\'))
        {
            switch (segment)
            {
                case "" or ".":
                    break;
                case "..":
                    if (parts.Count > 0)
                    {
                        parts.RemoveAt(parts.Count - 1);
                    }

                    break;
                default:
                    parts.Add(segment);
                    break;
            }
        }

        return string.Join('/', parts);
    }

    private sealed record Relationship(string Id, string Type, string Target)
    {
        /// <summary>Matches the last segment of the relationship type, so that Transitional and Strict Open XML are both accepted.</summary>
        public bool Is(string kind)
        {
            var slash = Type.LastIndexOf('/');
            return Type.AsSpan(slash + 1).SequenceEqual(kind);
        }
    }
}
