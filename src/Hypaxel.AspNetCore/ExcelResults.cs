using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Hypaxel.AspNetCore;

/// <summary>Factory methods for <see cref="ExcelResult"/>.</summary>
public static class ExcelResults
{
    /// <summary>Creates a result whose content is produced by <paramref name="write"/>.</summary>
    public static ExcelResult Excel(Func<XlsxWriter, CancellationToken, ValueTask> write, string? fileName = null, XlsxWriterOptions? options = null) =>
        new(write, fileName, options);

    /// <summary>Creates a single-worksheet result with one row per item (header, frozen top row, auto filter).</summary>
    public static ExcelResult Excel<T>(IEnumerable<T> items, string? fileName = null, string sheetName = "Sheet1", XlsxWriterOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(items);
        return new ExcelResult(
            async (writer, ct) => await writer.WriteWorksheetAsync(sheetName, items, ct).ConfigureAwait(false),
            fileName,
            options);
    }

    /// <summary>
    /// Creates a single-worksheet result from an asynchronous stream (e.g. EF Core's
    /// <c>AsAsyncEnumerable()</c>), so rows go from the database to the client without materializing.
    /// </summary>
    public static ExcelResult Excel<T>(IAsyncEnumerable<T> items, string? fileName = null, string sheetName = "Sheet1", XlsxWriterOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(items);
        return new ExcelResult(
            async (writer, ct) => await writer.WriteWorksheetAsync(sheetName, items, ct).ConfigureAwait(false),
            fileName,
            options);
    }
}

/// <summary>Minimal API entry point: <c>Results.Extensions.Excel(...)</c>.</summary>
public static class ExcelResultExtensions
{
    /// <inheritdoc cref="ExcelResults.Excel(Func{XlsxWriter, CancellationToken, ValueTask}, string?, XlsxWriterOptions?)"/>
    public static ExcelResult Excel(this IResultExtensions _, Func<XlsxWriter, CancellationToken, ValueTask> write, string? fileName = null, XlsxWriterOptions? options = null) =>
        ExcelResults.Excel(write, fileName, options);

    /// <inheritdoc cref="ExcelResults.Excel{T}(IEnumerable{T}, string?, string, XlsxWriterOptions?)"/>
    public static ExcelResult Excel<T>(this IResultExtensions _, IEnumerable<T> items, string? fileName = null, string sheetName = "Sheet1", XlsxWriterOptions? options = null) =>
        ExcelResults.Excel(items, fileName, sheetName, options);

    /// <inheritdoc cref="ExcelResults.Excel{T}(IAsyncEnumerable{T}, string?, string, XlsxWriterOptions?)"/>
    public static ExcelResult Excel<T>(this IResultExtensions _, IAsyncEnumerable<T> items, string? fileName = null, string sheetName = "Sheet1", XlsxWriterOptions? options = null) =>
        ExcelResults.Excel(items, fileName, sheetName, options);
}

/// <summary>MVC entry point: <c>return this.Excel(...)</c> from a controller action.</summary>
public static class ControllerExcelExtensions
{
    /// <inheritdoc cref="ExcelResults.Excel(Func{XlsxWriter, CancellationToken, ValueTask}, string?, XlsxWriterOptions?)"/>
    public static ExcelResult Excel(this ControllerBase _, Func<XlsxWriter, CancellationToken, ValueTask> write, string? fileName = null, XlsxWriterOptions? options = null) =>
        ExcelResults.Excel(write, fileName, options);

    /// <inheritdoc cref="ExcelResults.Excel{T}(IEnumerable{T}, string?, string, XlsxWriterOptions?)"/>
    public static ExcelResult Excel<T>(this ControllerBase _, IEnumerable<T> items, string? fileName = null, string sheetName = "Sheet1", XlsxWriterOptions? options = null) =>
        ExcelResults.Excel(items, fileName, sheetName, options);

    /// <inheritdoc cref="ExcelResults.Excel{T}(IAsyncEnumerable{T}, string?, string, XlsxWriterOptions?)"/>
    public static ExcelResult Excel<T>(this ControllerBase _, IAsyncEnumerable<T> items, string? fileName = null, string sheetName = "Sheet1", XlsxWriterOptions? options = null) =>
        ExcelResults.Excel(items, fileName, sheetName, options);
}
