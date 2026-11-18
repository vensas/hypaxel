using Hypaxel.Mapping;

namespace Hypaxel;

public sealed partial class XlsxWriter
{
    /// <summary>
    /// Adds a worksheet containing one row per item with a styled, frozen header row, an auto filter
    /// and column widths taken from <see cref="ExcelColumnAttribute.Width"/> (or estimated).
    /// </summary>
    public async ValueTask<Worksheet> WriteWorksheetAsync<T>(string name, IEnumerable<T> items, CancellationToken cancellationToken = default)
    {
        var sheet = AddWorksheet(name, CreateTableOptions<T>());
        await sheet.WriteObjectsAsync(items, new ObjectWriteOptions { AutoFilter = true }, cancellationToken).ConfigureAwait(false);
        return sheet;
    }

    /// <inheritdoc cref="WriteWorksheetAsync{T}(string, IEnumerable{T}, CancellationToken)"/>
    public async ValueTask<Worksheet> WriteWorksheetAsync<T>(string name, IAsyncEnumerable<T> items, CancellationToken cancellationToken = default)
    {
        var sheet = AddWorksheet(name, CreateTableOptions<T>());
        await sheet.WriteObjectsAsync(items, new ObjectWriteOptions { AutoFilter = true }, cancellationToken).ConfigureAwait(false);
        return sheet;
    }

    private static WorksheetOptions CreateTableOptions<T>()
    {
        var options = new WorksheetOptions { FrozenRows = 1 };
        options.SetColumnWidths(TypeMap<T>.Instance.Widths);
        return options;
    }
}
