using System.IO.Compression;
using Hypaxel.Styling;

namespace Hypaxel;

/// <summary>How string cell values are stored.</summary>
public enum StringStorage
{
    /// <summary>
    /// Strings are written inline in each cell (the default). Constant memory regardless of data
    /// volume and the fastest option: ideal for web exports.
    /// </summary>
    Inline,

    /// <summary>
    /// Strings are de-duplicated into the shared string table (what Excel itself writes). Produces
    /// smaller files when values repeat a lot; memory grows with the number of distinct strings.
    /// </summary>
    SharedStrings,
}

/// <summary>Options for <see cref="XlsxWriter"/>.</summary>
public sealed class XlsxWriterOptions
{
    /// <summary>Deflate level for the zip container. Default: <see cref="CompressionLevel.Fastest"/>.</summary>
    public CompressionLevel CompressionLevel { get; set; } = CompressionLevel.Fastest;

    /// <summary>How string values are stored. Default: <see cref="StringStorage.Inline"/>.</summary>
    public StringStorage StringStorage { get; set; } = StringStorage.Inline;

    /// <summary>
    /// Compressed bytes buffered before they are pushed to the destination stream. Larger values mean
    /// fewer, bigger writes; smaller values mean lower memory per concurrent export. Default: 64 KiB.
    /// </summary>
    public int FlushThreshold { get; set; } = 64 * 1024;

    /// <summary>Size of the uncompressed XML buffer. Default: 64 KiB.</summary>
    public int XmlBufferSize { get; set; } = 64 * 1024;

    /// <summary>
    /// When false (the default) a cell's <c>r="B7"</c> reference is only written when the cell does not
    /// directly follow the previous one, which the format allows and every mainstream reader supports.
    /// Set to true for consumers that require explicit references on every cell.
    /// </summary>
    public bool WriteCellReferences { get; set; }

    /// <summary>When true the destination stream is disposed together with the writer. Default: false.</summary>
    public bool OwnsStream { get; set; }

    /// <summary>
    /// Whether dates are serialized using the 1904 date system. Default: false (the 1900 system used by
    /// Excel on Windows and by default on all modern versions).
    /// </summary>
    public bool Use1904DateSystem { get; set; }

    internal void Validate()
    {
        if (FlushThreshold < 1024)
        {
            throw new ArgumentOutOfRangeException(nameof(FlushThreshold), "FlushThreshold must be at least 1024 bytes.");
        }

        if (XmlBufferSize < 1024)
        {
            throw new ArgumentOutOfRangeException(nameof(XmlBufferSize), "XmlBufferSize must be at least 1024 bytes.");
        }
    }
}

/// <summary>Definition of a column (or a run of identical columns).</summary>
public sealed record ColumnDefinition
{
    /// <summary>Width in characters of the default font, or null to keep the default width.</summary>
    public double? Width { get; init; }

    /// <summary>Default format for cells in this column that have no value.</summary>
    public StyleId Style { get; init; }

    /// <summary>Hides the column.</summary>
    public bool Hidden { get; init; }

    /// <summary>Creates a column definition with the given width.</summary>
    public static ColumnDefinition WithWidth(double width) => new() { Width = width };
}

/// <summary>Options that must be known before a worksheet's rows are written.</summary>
public sealed class WorksheetOptions
{
    /// <summary>
    /// Column definitions keyed by 1-based column index. Use <see cref="SetColumn"/> or
    /// <see cref="SetColumnWidths"/> for convenience.
    /// </summary>
    public SortedDictionary<int, ColumnDefinition> Columns { get; } = [];

    /// <summary>Number of rows frozen at the top (e.g. 1 to keep a header visible).</summary>
    public int FrozenRows { get; set; }

    /// <summary>Number of columns frozen at the left.</summary>
    public int FrozenColumns { get; set; }

    /// <summary>Whether gridlines are shown. Default: true.</summary>
    public bool ShowGridLines { get; set; } = true;

    /// <summary>Hides the worksheet tab.</summary>
    public bool Hidden { get; set; }

    /// <summary>Optional tab color.</summary>
    public ExcelColor? TabColor { get; set; }

    /// <summary>Defines a column.</summary>
    public WorksheetOptions SetColumn(int column, ColumnDefinition definition)
    {
        CellReference.ValidateColumn(column);
        ArgumentNullException.ThrowIfNull(definition);
        Columns[column] = definition;
        return this;
    }

    /// <summary>Sets the widths of columns 1..n.</summary>
    public WorksheetOptions SetColumnWidths(params ReadOnlySpan<double> widths)
    {
        for (var i = 0; i < widths.Length; i++)
        {
            var column = i + 1;
            Columns[column] = Columns.TryGetValue(column, out var existing)
                ? existing with { Width = widths[i] }
                : ColumnDefinition.WithWidth(widths[i]);
        }

        return this;
    }
}
