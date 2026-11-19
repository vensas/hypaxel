namespace Hypaxel;

/// <summary>Why a workbook could not be read.</summary>
public enum XlsxReadErrorKind
{
    /// <summary>
    /// The input is not an XLSX workbook: not a ZIP archive, a legacy <c>.xls</c> file, a password-protected
    /// (encrypted) workbook, or another Office document such as a Word file.
    /// </summary>
    InvalidFile,

    /// <summary>A part of the workbook is damaged or does not follow the Office Open XML format. <see cref="XlsxReadException.Part"/> names it.</summary>
    Malformed,

    /// <summary>The input exceeds a configured or built-in safety limit (see <see cref="XlsxReaderOptions"/>).</summary>
    LimitExceeded,

    /// <summary>The workbook uses a feature the reader does not support (e.g. an encrypted ZIP entry or a chart sheet).</summary>
    Unsupported,
}

/// <summary>
/// Thrown by <see cref="XlsxReader"/> and <see cref="WorksheetReader"/> when the input is not a readable
/// workbook. Damaged or malicious input always produces this exception (or an <see cref="IOException"/>
/// from the underlying stream), never another exception type.
/// </summary>
public sealed class XlsxReadException : Exception
{
    /// <summary>Creates the exception.</summary>
    public XlsxReadException(XlsxReadErrorKind kind, string? part, string message)
        : base(part is null ? message : $"{part}: {message}")
    {
        Kind = kind;
        Part = part;
    }

    /// <summary>The category of the problem.</summary>
    public XlsxReadErrorKind Kind { get; }

    /// <summary>The package part the problem was found in (e.g. <c>xl/worksheets/sheet1.xml</c>), if any.</summary>
    public string? Part { get; }

    internal static XlsxReadException InvalidFile(string message) => new(XlsxReadErrorKind.InvalidFile, null, message);

    internal static XlsxReadException Malformed(string part, string message) => new(XlsxReadErrorKind.Malformed, part, message);

    internal static XlsxReadException LimitExceeded(string? part, string message) => new(XlsxReadErrorKind.LimitExceeded, part, message);

    internal static XlsxReadException Unsupported(string? part, string message) => new(XlsxReadErrorKind.Unsupported, part, message);
}
