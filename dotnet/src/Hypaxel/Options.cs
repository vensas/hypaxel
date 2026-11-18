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

    /// <summary>
    /// Deepest row outline (grouping) level used on this worksheet, 0–7. Excel sizes the outline bar
    /// from this value, which precedes the rows in the file, so it must be declared up front;
    /// <see cref="RowOptions.OutlineLevel"/> cannot exceed it.
    /// </summary>
    public int RowOutlineLevels { get; set; }

    /// <summary>Places the expand/collapse button of a row group on the summary row above it instead of below.</summary>
    public bool OutlineSummaryAbove { get; set; }

    /// <summary>
    /// Sizes columns without an explicit width to fit their content, or null (the default) to keep
    /// the default widths. See <see cref="AutoFitOptions"/>.
    /// </summary>
    public AutoFitOptions? AutoFit { get; set; }

    /// <summary>Auto-fits columns that have no explicit width, measuring the first 1,000 rows by default.</summary>
    public WorksheetOptions AutoFitColumns(AutoFitOptions? options = null)
    {
        AutoFit = options ?? new AutoFitOptions();
        return this;
    }

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

    internal void Validate(StyleRegistry styles)
    {
        if (FrozenRows < 0 || FrozenRows >= CellReference.MaxRows)
        {
            throw new ArgumentOutOfRangeException(nameof(FrozenRows), FrozenRows, "FrozenRows is out of range.");
        }

        if (FrozenColumns < 0 || FrozenColumns >= CellReference.MaxColumns)
        {
            throw new ArgumentOutOfRangeException(nameof(FrozenColumns), FrozenColumns, "FrozenColumns is out of range.");
        }

        foreach (var definition in Columns.Values)
        {
            if (!definition.Style.IsDefault)
            {
                styles.Validate(definition.Style);
            }
        }

        if (RowOutlineLevels is < 0 or > 7)
        {
            throw new ArgumentOutOfRangeException(nameof(RowOutlineLevels), RowOutlineLevels, "RowOutlineLevels must be between 0 and 7.");
        }

        if (AutoFit is { } fit)
        {
            if (fit.SampleRows < 1)
            {
                throw new ArgumentOutOfRangeException(nameof(AutoFit), fit.SampleRows, "The auto-fit sample needs at least one row.");
            }

            if (!(fit.MaxWidth > 0))
            {
                throw new ArgumentOutOfRangeException(nameof(AutoFit), fit.MaxWidth, "The auto-fit maximum width must be positive.");
            }
        }
    }
}

/// <summary>
/// Column auto-fit settings for <see cref="WorksheetOptions.AutoFit"/>.
/// </summary>
/// <remarks>
/// Column widths precede the cell data in the file, so the writer holds back the worksheet's XML until
/// <see cref="SampleRows"/> rows have been written (or the worksheet ends), estimates the widths from
/// those rows and then streams as usual. Memory is bounded by the sample: at most
/// <see cref="SampleRows"/> rows or 8 MiB of XML, whichever comes first. Widths are estimated from the
/// text, number format and font size of each cell; formulas and merged cells are not measured.
/// </remarks>
public sealed record AutoFitOptions
{
    /// <summary>Rows measured before the widths are fixed. Default: 1,000.</summary>
    public int SampleRows { get; init; } = 1000;

    /// <summary>Upper bound for a fitted width, in characters. Default: 255 (Excel's maximum).</summary>
    public double MaxWidth { get; init; } = 255;
}

/// <summary>Settings for one row, used with <see cref="Worksheet.BeginRow(RowOptions)"/>.</summary>
public sealed record RowOptions
{
    /// <summary>Height in points (0–409), or null for the default height.</summary>
    public double? Height { get; init; }

    /// <summary>Default format for the row's empty cells.</summary>
    public StyleId Style { get; init; }

    /// <summary>
    /// Outline (grouping) level 0–7. Must not exceed <see cref="WorksheetOptions.RowOutlineLevels"/>
    /// of the worksheet.
    /// </summary>
    public int OutlineLevel { get; init; }

    /// <summary>Hides the row (e.g. a row inside a collapsed group).</summary>
    public bool Hidden { get; init; }

    /// <summary>Marks the summary row of a collapsed group (the group's rows should be hidden).</summary>
    public bool Collapsed { get; init; }
}
