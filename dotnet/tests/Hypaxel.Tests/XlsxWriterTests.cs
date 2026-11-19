using System.Globalization;
using System.IO.Compression;
using Hypaxel.Styling;

namespace Hypaxel.Tests;

public class XlsxWriterTests
{
    private static async Task<Workbook> WriteAsync(Func<XlsxWriter, Task> write, XlsxWriterOptions? options = null)
    {
        var stream = new MemoryStream();
        await using (var writer = new XlsxWriter(stream, options))
        {
            await write(writer);
            await writer.CompleteAsync();
        }

        var workbook = Workbook.Load(stream);
        workbook.AssertValid();
        return workbook;
    }

    [Fact]
    public async Task EmptyWorkbook_GetsDefaultSheet()
    {
        using var wb = await WriteAsync(_ => Task.CompletedTask);
        Assert.Equal(["Sheet1"], wb.SheetNames);
        Assert.Equal(0, wb.RowCount("Sheet1"));
    }

    [Theory]
    [InlineData(StringStorage.SharedStrings)]
    [InlineData(StringStorage.Inline)]
    public async Task WritesAllValueTypes(StringStorage storage)
    {
        var date = new DateTime(2024, 3, 15, 13, 45, 30);
        using var wb = await WriteAsync(
            async w =>
            {
                var sheet = w.AddWorksheet("Data");
                await sheet.AddRowAsync("text", 42, 1234567890123L, 3.25, 1.5m, true, false);
                await sheet.AddRowAsync(date, new DateOnly(2024, 3, 15), new TimeOnly(6, 0), TimeSpan.FromHours(36), CellValue.Formula("=SUM(B1:D1)"));
                await sheet.AddRowAsync(double.NaN, double.PositiveInfinity, (string?)null, "after-gap");
            },
            new XlsxWriterOptions { StringStorage = storage });

        Assert.Equal("text", wb.Value("Data", "A1"));
        Assert.Equal("42", wb.Value("Data", "B1"));
        Assert.Equal("1234567890123", wb.Value("Data", "C1"));
        Assert.Equal("3.25", wb.Value("Data", "D1"));
        Assert.Equal("1.5", wb.Value("Data", "E1"));
        Assert.Equal("1", wb.Value("Data", "F1"));
        Assert.Equal("0", wb.Value("Data", "G1"));

        Assert.Equal(date.ToOADate(), double.Parse(wb.Value("Data", "A2")!, CultureInfo.InvariantCulture), 9);
        Assert.Equal(22u, wb.Format("Data", "A2").NumberFormatId!.Value);
        Assert.Equal(new DateTime(2024, 3, 15).ToOADate().ToString(CultureInfo.InvariantCulture), wb.Value("Data", "B2"));
        Assert.Equal(14u, wb.Format("Data", "B2").NumberFormatId!.Value);
        Assert.Equal("0.25", wb.Value("Data", "C2"));
        Assert.Equal(21u, wb.Format("Data", "C2").NumberFormatId!.Value);
        Assert.Equal("1.5", wb.Value("Data", "D2"));
        Assert.Equal(46u, wb.Format("Data", "D2").NumberFormatId!.Value);
        Assert.Equal("SUM(B1:D1)", wb.Cell("Data", "E2")!.CellFormula!.Text);

        Assert.Equal("#NUM!", wb.Value("Data", "A3"));
        Assert.Null(wb.Cell("Data", "C3"));
        Assert.Equal("after-gap", wb.Value("Data", "D3"));
    }

    [Fact]
    public async Task EscapesSpecialCharacters()
    {
        const string tricky = "a & b < c > d \"quoted\" 'single' \t tab\nnewline \u0001ctrl _x0041_ literal 😀 \ud800 lone";
        using var wb = await WriteAsync(async w =>
        {
            var sheet = w.AddWorksheet("Esc & <Sheet>");
            await sheet.AddRowAsync(tricky, "  padded  ", "");
        });

        Assert.Equal("Esc & <Sheet>", wb.SheetNames[0]);
        var value = wb.Value("Esc & <Sheet>", "A1")!;
        // The Open XML SDK does not decode Excel's _xHHHH_ escapes, so compare the encoded form.
        Assert.Equal(tricky.Replace("\u0001", "_x0001_").Replace("_x0041_", "_x005F_x0041_").Replace("\ud800", "�"), value);
        Assert.Equal("  padded  ", wb.Value("Esc & <Sheet>", "B1"));
        Assert.Equal("", wb.Value("Esc & <Sheet>", "C1"));
    }

    [Fact]
    public async Task SharedStrings_AreDeduplicated()
    {
        var stream = new MemoryStream();
        await using (var writer = new XlsxWriter(stream, new XlsxWriterOptions { StringStorage = StringStorage.SharedStrings }))
        {
            var sheet = writer.AddWorksheet("S");
            for (var i = 0; i < 100; i++)
            {
                await sheet.AddRowAsync("same", i % 2 == 0 ? "even" : "odd");
            }
        }

        using var wb = Workbook.Load(stream);
        wb.AssertValid();
        var sst = wb.Part.SharedStringTablePart!.SharedStringTable;
        Assert.Equal(3u, sst.UniqueCount!.Value);
        Assert.Equal(200u, sst.Count!.Value);
    }

    [Fact]
    public async Task Styles_AreRegisteredAndDeduplicated()
    {
        StyleId a = default, b = default;
        using var wb = await WriteAsync(async w =>
        {
            a = w.AddStyle(new CellStyle
            {
                NumberFormat = "#,##0.000",
                Font = new Font { Bold = true, Italic = true, Color = ExcelColor.FromHex("#FF0000"), Size = 14, Name = "Arial" },
                Fill = new Fill(ExcelColor.Yellow),
                Border = Border.All(BorderLineStyle.Thin, ExcelColor.Black),
                Alignment = new Alignment { Horizontal = HorizontalAlignment.Center, Vertical = VerticalAlignment.Center, WrapText = true },
            });
            b = w.AddStyle(new CellStyle
            {
                NumberFormat = "#,##0.000",
                Font = new Font { Bold = true, Italic = true, Color = ExcelColor.FromHex("FF0000"), Size = 14, Name = "Arial" },
                Fill = new Fill(ExcelColor.Yellow),
                Border = Border.All(BorderLineStyle.Thin, ExcelColor.Black),
                Alignment = new Alignment { Horizontal = HorizontalAlignment.Center, Vertical = VerticalAlignment.Center, WrapText = true },
            });
            var sheet = w.AddWorksheet("S");
            sheet.Write(1.5, a);
            sheet.Write(new DateTime(2020, 1, 1), a); // style already has a number format: kept as is
            sheet.Write(new DateTime(2020, 1, 1), w.AddStyle(new CellStyle { Font = new Font { Bold = true } }));
            await sheet.EndRowAsync();
        });

        Assert.Equal(a, b);
        var format = wb.Format("S", "A1");
        Assert.Equal(164u, format.NumberFormatId!.Value);
        Assert.True(format.Alignment!.WrapText!.Value);
        Assert.Equal(format.NumberFormatId.Value, wb.Format("S", "B1").NumberFormatId!.Value);

        var derived = wb.Format("S", "C1");
        Assert.Equal(22u, derived.NumberFormatId!.Value);
        Assert.NotEqual(0u, derived.FontId!.Value); // keeps the bold font
    }

    [Fact]
    public async Task WorksheetOptions_AreApplied()
    {
        using var wb = await WriteAsync(async w =>
        {
            var options = new WorksheetOptions { FrozenRows = 1, FrozenColumns = 2, ShowGridLines = false, TabColor = ExcelColor.Blue }
                .SetColumnWidths(10, 20, 30)
                .SetColumn(5, new ColumnDefinition { Hidden = true });
            var sheet = w.AddWorksheet("Opts", options);
            sheet.BeginRow(height: 30);
            sheet.Write("Header");
            await sheet.EndRowAsync();
            sheet.MergeCells(2, 1, 3, 2);
            sheet.SetAutoFilter(1, 1, 5, 3);
            sheet.SkipRows(3);
            await sheet.AddRowAsync("row 5");
            w.AddWorksheet("Hidden", new WorksheetOptions { Hidden = true });
        });

        var ws = wb.SheetPart("Opts").Worksheet;
        var pane = ws.Descendants<DocumentFormat.OpenXml.Spreadsheet.Pane>().Single();
        Assert.Equal("C2", pane.TopLeftCell!.Value);
        Assert.Equal(4, ws.Descendants<DocumentFormat.OpenXml.Spreadsheet.Column>().Count());
        Assert.Equal("A2:B3", ws.Descendants<DocumentFormat.OpenXml.Spreadsheet.MergeCell>().Single().Reference!.Value);
        Assert.Equal("A1:C5", ws.Descendants<DocumentFormat.OpenXml.Spreadsheet.AutoFilter>().Single().Reference!.Value);
        Assert.Equal("row 5", wb.Value("Opts", "A5"));
        Assert.Equal(30d, ws.Descendants<DocumentFormat.OpenXml.Spreadsheet.Row>().First().Height!.Value);
        Assert.Equal(["Opts", "Hidden"], wb.SheetNames);
    }

    [Fact]
    public async Task MultipleSheets_PreviousSheetIsClosed()
    {
        Worksheet? first = null;
        using var wb = await WriteAsync(async w =>
        {
            first = w.AddWorksheet("One");
            await first.AddRowAsync(1);
            var second = w.AddWorksheet("Two");
            await second.AddRowAsync(2);
            Assert.Throws<InvalidOperationException>(() => first.Write(3));
        });

        Assert.Equal("1", wb.Value("One", "A1"));
        Assert.Equal("2", wb.Value("Two", "A1"));
    }

    [Theory]
    [InlineData("")]
    [InlineData("a/b")]
    [InlineData("[x]")]
    [InlineData("'quoted'")]
    [InlineData("This name is definitely longer than 31 characters")]
    public void InvalidSheetNames_Throw(string name)
    {
        using var writer = new XlsxWriter(new MemoryStream());
        Assert.ThrowsAny<ArgumentException>(() => writer.AddWorksheet(name));
    }

    [Fact]
    public void DuplicateSheetNames_Throw()
    {
        using var writer = new XlsxWriter(new MemoryStream());
        writer.AddWorksheet("Data");
        Assert.Throws<ArgumentException>(() => writer.AddWorksheet("DATA"));
    }

    [Fact]
    public void RowsMustAscend()
    {
        using var writer = new XlsxWriter(new MemoryStream());
        var sheet = writer.AddWorksheet("S");
        sheet.BeginRow(5);
        Assert.Throws<InvalidOperationException>(() => sheet.BeginRow(5));
    }

    [Fact]
    public void TooLongText_Throws()
    {
        using var writer = new XlsxWriter(new MemoryStream());
        var sheet = writer.AddWorksheet("S");
        Assert.Throws<ArgumentException>(() => sheet.Write(new string('x', 32_768)));
    }

    [Fact]
    public async Task AsyncOnlyStream_IsNeverWrittenSynchronously()
    {
        var stream = new AsyncOnlyStream();
        await using (var writer = new XlsxWriter(stream, new XlsxWriterOptions { FlushThreshold = 4096 }))
        {
            var sheet = writer.AddWorksheet("Big");
            for (var i = 0; i < 20_000; i++)
            {
                await sheet.AddRowAsync(i, $"row {i}", i * 0.5, Guid.NewGuid().ToString());
            }

            await writer.CompleteAsync();
        }

        Assert.True(stream.AsyncWrites > 10, $"expected incremental flushing, got {stream.AsyncWrites} writes");
        using var wb = new Workbook(stream.Inner.ToArray());
        wb.AssertValid();
        Assert.Equal(20_000, wb.RowCount("Big"));
        Assert.Equal("row 19999", wb.Value("Big", "B20000"));
    }

    [Fact]
    public async Task DisposeAsync_CompletesWorkbook()
    {
        var stream = new MemoryStream();
        await using (var writer = new XlsxWriter(stream))
        {
            await writer.AddWorksheet("S").AddRowAsync("x");
        }

        using var wb = Workbook.Load(stream);
        wb.AssertValid();
        Assert.Equal("x", wb.Value("S", "A1"));
    }

    [Fact]
    public void SyncApi_Works()
    {
        var stream = new MemoryStream();
        using (var writer = new XlsxWriter(stream))
        {
            var sheet = writer.AddWorksheet("S");
            sheet.AddRow("a", 1);
            sheet.Write("b");
            sheet.EndRow();
            writer.Complete();
        }

        using var wb = Workbook.Load(stream);
        wb.AssertValid();
        Assert.Equal("b", wb.Value("S", "A2"));
    }

    [Fact]
    public async Task CreateFile_WritesToDisk()
    {
        var path = Path.Combine(Path.GetTempPath(), $"hypaxel-{Guid.NewGuid():N}.xlsx");
        try
        {
            await using (var writer = XlsxWriter.Create(path))
            {
                await writer.AddWorksheet("S").AddRowAsync("on disk");
            }

            using var wb = new Workbook(await File.ReadAllBytesAsync(path));
            wb.AssertValid();
            Assert.Equal("on disk", wb.Value("S", "A1"));
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public async Task Output_IsAZipWithExpectedParts()
    {
        var stream = new MemoryStream();
        await using (var writer = new XlsxWriter(stream, new XlsxWriterOptions { StringStorage = StringStorage.SharedStrings }))
        {
            await writer.AddWorksheet("S").AddRowAsync("x");
        }

        stream.Position = 0;
        using var zip = new ZipArchive(stream, ZipArchiveMode.Read);
        var names = zip.Entries.Select(e => e.FullName).ToHashSet();
        Assert.Contains("[Content_Types].xml", names);
        Assert.Contains("_rels/.rels", names);
        Assert.Contains("xl/workbook.xml", names);
        Assert.Contains("xl/_rels/workbook.xml.rels", names);
        Assert.Contains("xl/styles.xml", names);
        Assert.Contains("xl/sharedStrings.xml", names);
        Assert.Contains("xl/worksheets/sheet1.xml", names);
    }

    [Theory]
    [InlineData(1900, 1, 1, 1)]
    [InlineData(1900, 2, 28, 59)]
    [InlineData(1900, 3, 1, 61)]
    [InlineData(2024, 1, 1, 45292)]
    public async Task DateSerials_MatchExcel(int y, int m, int d, int expected)
    {
        using var wb = await WriteAsync(async w => await w.AddWorksheet("S").AddRowAsync(new DateOnly(y, m, d)));
        Assert.Equal(expected.ToString(CultureInfo.InvariantCulture), wb.Value("S", "A1"));
    }

    [Theory]
    [InlineData("2024-03-15T13:45:30.123")]
    [InlineData("2024-03-15T23:59:59.9999999")]
    [InlineData("2024-03-15T00:00:00.0000001")]
    [InlineData("1999-12-31T12:00:00")]
    public async Task DateTimeSerials_AreAccurate(string iso)
    {
        var value = DateTime.Parse(iso, CultureInfo.InvariantCulture);
        using var wb = await WriteAsync(async w => await w.AddWorksheet("S").AddRowAsync(value));
        var serial = double.Parse(wb.Value("S", "A1")!, CultureInfo.InvariantCulture);
        var roundTripped = DateTime.FromOADate(serial);
        Assert.True(Math.Abs((roundTripped - value).TotalMilliseconds) < 1, $"{roundTripped:O} vs {value:O} ({serial})");
    }

    [Fact]
    public async Task DatesOutsideExcelRange_FallBackToText()
    {
        using var wb = await WriteAsync(async w => await w.AddWorksheet("S").AddRowAsync(DateTime.MinValue));
        Assert.Equal("0001-01-01T00:00:00", wb.Value("S", "A1"));
    }

    [Fact]
    public async Task Date1904System()
    {
        using var wb = await WriteAsync(
            async w => await w.AddWorksheet("S").AddRowAsync(new DateOnly(1904, 1, 2), new DateOnly(9999, 12, 31)),
            new XlsxWriterOptions { Use1904DateSystem = true });
        Assert.Equal("1", wb.Value("S", "A1"));
        Assert.Equal("2957003", wb.Value("S", "B1"));
        Assert.True(wb.Part.Workbook.WorkbookProperties!.Date1904!.Value);
    }

    [Fact]
    public void CellReference_Helpers()
    {
        Assert.Equal("A", CellReference.ColumnName(1));
        Assert.Equal("Z", CellReference.ColumnName(26));
        Assert.Equal("AA", CellReference.ColumnName(27));
        Assert.Equal("XFD", CellReference.ColumnName(16384));
        Assert.Equal("B3:D10", CellReference.ToRange(3, 2, 10, 4));
    }

    [Fact]
    public async Task LongRow_UpToMaxColumns()
    {
        using var wb = await WriteAsync(async w =>
        {
            var sheet = w.AddWorksheet("Wide");
            for (var i = 0; i < CellReference.MaxColumns; i++)
            {
                sheet.Write(i);
            }

            Assert.Throws<InvalidOperationException>(() => sheet.Write(0));
            await sheet.EndRowAsync();
        });

        Assert.Equal("16383", wb.Value("Wide", "XFD1"));
    }
}
