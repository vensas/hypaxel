namespace Hypaxel;

/// <summary>Options for <see cref="XlsxReader"/>.</summary>
public sealed class XlsxReaderOptions
{
    /// <summary>
    /// Largest amount of text (UTF-8 bytes) the shared string table may hold. Default: 1 GiB.
    /// </summary>
    /// <remarks>
    /// The shared string table is the one part of a workbook that must be held in memory to read rows;
    /// everything else is streamed. Lower this when reading untrusted uploads.
    /// </remarks>
    public long MaxSharedStringsBytes { get; set; } = 1L << 30;

    /// <summary>
    /// Decompresses worksheets and the shared string table on a thread-pool thread, up to four 256 KiB chunks
    /// ahead of the parser, so that inflating and parsing overlap. Default: false.
    /// </summary>
    /// <remarks>
    /// Turn it on to read one large workbook sooner when the machine has idle cores: reading takes about 30%
    /// less time, but about 20% more CPU in total, and each open worksheet holds 1 MiB of buffers. Leave it
    /// off on servers that read many workbooks at once: when every core is busy, the extra CPU makes the total
    /// slower. Errors are reported on the reading thread exactly as without it, and disposing the
    /// <see cref="WorksheetReader"/> stops the background work.
    /// </remarks>
    public bool BackgroundDecompression { get; set; }

    /// <summary>
    /// Also returns the rows that the file stores without any value, for example a spacer row with a height
    /// or an empty hidden row. Default: false.
    /// </summary>
    /// <remarks>
    /// Such a row has no cells (<see cref="XlsxRow.Count"/> is 0) but carries its number and properties.
    /// Rows that the file does not store are never returned.
    /// </remarks>
    public bool IncludeEmptyRows { get; set; }

    /// <summary>When true the stream passed to the reader is disposed together with it. Default: false.</summary>
    public bool OwnsStream { get; set; }

    internal void Validate()
    {
        if (MaxSharedStringsBytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(MaxSharedStringsBytes), MaxSharedStringsBytes, "The limit cannot be negative.");
        }
    }
}

/// <summary>Whether a sheet is shown in Excel's tab bar.</summary>
public enum SheetVisibility
{
    /// <summary>Shown.</summary>
    Visible,

    /// <summary>Hidden; the user can unhide it.</summary>
    Hidden,

    /// <summary>Hidden; only VBA can unhide it.</summary>
    VeryHidden,
}

/// <summary>What a sheet contains.</summary>
public enum SheetKind
{
    /// <summary>A grid of cells.</summary>
    Worksheet,

    /// <summary>A sheet that only holds a chart; it has no rows.</summary>
    Chartsheet,

    /// <summary>A dialog sheet or Excel 4 macro sheet.</summary>
    Other,
}

/// <summary>A sheet listed in the workbook.</summary>
/// <param name="Name">The sheet name.</param>
/// <param name="Visibility">Whether the tab is shown.</param>
/// <param name="Kind">Worksheet, chart sheet or other.</param>
public sealed record SheetInfo(string Name, SheetVisibility Visibility, SheetKind Kind)
{
    internal string? Part { get; init; }
}
