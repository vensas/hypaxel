using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Net.Http.Headers;

namespace Hypaxel.AspNetCore;

/// <summary>
/// Streams an XLSX workbook directly into the HTTP response body. Works as a Minimal API
/// <see cref="IResult"/> and as an MVC <see cref="IActionResult"/>. Nothing is buffered beyond
/// <see cref="XlsxWriterOptions.FlushThreshold"/>, so arbitrarily large exports run in constant memory.
/// </summary>
public sealed class ExcelResult : IResult, IActionResult, IContentTypeHttpResult, IFileHttpResult
{
    /// <summary>The XLSX media type.</summary>
    public const string XlsxContentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private readonly Func<XlsxWriter, CancellationToken, ValueTask> _write;

    /// <summary>Creates a result that runs <paramref name="write"/> against a writer bound to the response.</summary>
    /// <param name="write">Callback that writes worksheets; the workbook is completed afterwards.</param>
    /// <param name="fileName">Download file name (".xlsx" is appended if missing), or null for an inline response.</param>
    /// <param name="options">Writer options.</param>
    public ExcelResult(Func<XlsxWriter, CancellationToken, ValueTask> write, string? fileName = null, XlsxWriterOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(write);
        _write = write;
        FileDownloadName = NormalizeFileName(fileName);
        Options = options;
    }

    /// <summary>Writer options used for the response.</summary>
    public XlsxWriterOptions? Options { get; }

    /// <inheritdoc />
    public string ContentType => XlsxContentType;

    /// <inheritdoc />
    public string? FileDownloadName { get; }

    /// <inheritdoc />
    public async Task ExecuteAsync(HttpContext httpContext)
    {
        ArgumentNullException.ThrowIfNull(httpContext);
        var response = httpContext.Response;
        var cancellationToken = httpContext.RequestAborted;

        response.ContentType = XlsxContentType;
        if (FileDownloadName is not null)
        {
            var disposition = new ContentDispositionHeaderValue("attachment");
            disposition.SetHttpFileName(FileDownloadName);
            response.Headers.ContentDisposition = disposition.ToString();
        }

        // Make sure response-buffering middleware does not undo the streaming.
        httpContext.Features.Get<IHttpResponseBodyFeature>()?.DisableBuffering();

        var options = Options ?? new XlsxWriterOptions();
        if (options.OwnsStream)
        {
            throw new InvalidOperationException("ExcelResult never owns the response stream; OwnsStream must be false.");
        }

        await using var writer = new XlsxWriter(response.Body, options);
        await _write(writer, cancellationToken).ConfigureAwait(false);
        await writer.CompleteAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <inheritdoc />
    Task IActionResult.ExecuteResultAsync(ActionContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        return ExecuteAsync(context.HttpContext);
    }

    private static string? NormalizeFileName(string? fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName))
        {
            return null;
        }

        return fileName.EndsWith(".xlsx", StringComparison.OrdinalIgnoreCase) ? fileName : fileName + ".xlsx";
    }
}
