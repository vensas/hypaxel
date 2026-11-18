using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;
using DocumentFormat.OpenXml.Validation;
using CellReference = Hypaxel.CellReference;

namespace Hypaxel.Tests;

/// <summary>Reads a generated workbook back with the Open XML SDK and validates it against the schema.</summary>
internal sealed class Workbook : IDisposable
{
    private readonly SpreadsheetDocument _doc;

    public Workbook(byte[] bytes)
    {
        _doc = SpreadsheetDocument.Open(new MemoryStream(bytes), false);
    }

    public static Workbook Load(MemoryStream stream) => new(stream.ToArray());

    public WorkbookPart Part => _doc.WorkbookPart!;

    public IReadOnlyList<string> SheetNames =>
        Part.Workbook.Sheets!.Elements<Sheet>().Select(s => s.Name!.Value!).ToList();

    public void AssertValid()
    {
        var validator = new OpenXmlValidator(FileFormatVersions.Office2019);
        var errors = validator.Validate(_doc).ToList();
        Assert.True(errors.Count == 0, string.Join(Environment.NewLine, errors.Select(e => $"{e.Path?.XPath}: {e.Description}")));
    }

    public WorksheetPart SheetPart(string name)
    {
        var sheet = Part.Workbook.Sheets!.Elements<Sheet>().Single(s => s.Name == name);
        return (WorksheetPart)Part.GetPartById(sheet.Id!);
    }

    /// <summary>Finds a cell by A1 reference, inferring positions of cells written without an explicit reference.</summary>
    public Cell? Cell(string sheet, string reference)
    {
        foreach (var row in SheetPart(sheet).Worksheet.Descendants<Row>())
        {
            var column = 0;
            foreach (var cell in row.Elements<Cell>())
            {
                if (cell.CellReference?.Value is { } explicitReference)
                {
                    column = ColumnIndex(explicitReference);
                }
                else
                {
                    column++;
                }

                if (CellReference.ToA1((int)row.RowIndex!.Value, column) == reference)
                {
                    return cell;
                }
            }
        }

        return null;
    }

    private static int ColumnIndex(string reference)
    {
        var column = 0;
        foreach (var c in reference.TakeWhile(char.IsAsciiLetter))
        {
            column = (column * 26) + (c - 'A' + 1);
        }

        return column;
    }

    /// <summary>Returns the displayed text of a string cell or the raw value of any other cell.</summary>
    public string? Value(string sheet, string reference)
    {
        var cell = Cell(sheet, reference);
        if (cell is null)
        {
            return null;
        }

        if (cell.DataType?.Value == CellValues.SharedString)
        {
            var index = int.Parse(cell.CellValue!.Text);
            return Part.SharedStringTablePart!.SharedStringTable.Elements<SharedStringItem>().ElementAt(index).InnerText;
        }

        if (cell.DataType?.Value == CellValues.InlineString)
        {
            return cell.InlineString!.InnerText;
        }

        return cell.CellValue?.Text;
    }

    public CellFormat Format(string sheet, string reference)
    {
        var index = (int)(Cell(sheet, reference)!.StyleIndex?.Value ?? 0);
        return Part.WorkbookStylesPart!.Stylesheet.CellFormats!.Elements<CellFormat>().ElementAt(index);
    }

    public int RowCount(string sheet) => SheetPart(sheet).Worksheet.Descendants<Row>().Count();

    public void Dispose() => _doc.Dispose();
}

/// <summary>Mimics an ASP.NET Core response body: non-seekable and throws on synchronous writes.</summary>
internal sealed class AsyncOnlyStream : Stream
{
    public MemoryStream Inner { get; } = new();

    public int AsyncWrites { get; private set; }

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

    public override void Write(byte[] buffer, int offset, int count) =>
        throw new InvalidOperationException("Synchronous operations are disallowed.");

    public override void Flush() => throw new InvalidOperationException("Synchronous operations are disallowed.");

    public override Task FlushAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
    {
        AsyncWrites++;
        await Task.Yield();
        Inner.Write(buffer.Span);
    }

    public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
        WriteAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();

    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}
