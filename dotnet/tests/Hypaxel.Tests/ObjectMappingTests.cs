using System.Globalization;
using Hypaxel.Mapping;

namespace Hypaxel.Tests;

public class ObjectMappingTests
{
    public enum Status
    {
        Active,
        Suspended,
    }

    public sealed class Customer
    {
        [ExcelColumn("Customer #", Order = 0)]
        public int Id { get; init; }

        public string? Name { get; init; }

        [ExcelColumn(NumberFormat = "#,##0.00", Width = 15)] // built-in format id 4
        public decimal Balance { get; init; }

        public DateTime? LastOrder { get; init; }

        public Status Status { get; init; }

        public Guid Key { get; init; }

        public short Small { get; init; }

        [ExcelIgnore]
        public string Secret { get; init; } = "hidden";

        public double? Score;
    }

    private static List<Customer> Sample() =>
    [
        new() { Id = 1, Name = "Alice", Balance = 1234.5m, LastOrder = new DateTime(2024, 5, 1), Status = Status.Active, Key = Guid.Empty, Small = 7, Score = 9.5 },
        new() { Id = 2, Name = null, Balance = -3m, LastOrder = null, Status = Status.Suspended, Key = Guid.Empty, Small = -1, Score = null },
    ];

    [Fact]
    public async Task WriteWorksheetAsync_WritesHeaderAndRows()
    {
        var stream = new MemoryStream();
        await using (var writer = new XlsxWriter(stream))
        {
            await writer.WriteWorksheetAsync("Customers", Sample());
        }

        using var wb = Workbook.Load(stream);
        wb.AssertValid();

        string[] expectedHeaders = ["Customer #", "Name", "Balance", "LastOrder", "Status", "Key", "Small", "Score"];
        for (var i = 0; i < expectedHeaders.Length; i++)
        {
            Assert.Equal(expectedHeaders[i], wb.Value("Customers", CellReference.ToA1(1, i + 1)));
        }

        Assert.Null(wb.Value("Customers", "I1")); // [ExcelIgnore]

        Assert.Equal("1", wb.Value("Customers", "A2"));
        Assert.Equal("Alice", wb.Value("Customers", "B2"));
        Assert.Equal("1234.5", wb.Value("Customers", "C2"));
        Assert.Equal(new DateTime(2024, 5, 1).ToOADate().ToString(CultureInfo.InvariantCulture), wb.Value("Customers", "D2"));
        Assert.Equal("Active", wb.Value("Customers", "E2"));
        Assert.Equal(Guid.Empty.ToString(), wb.Value("Customers", "F2"));
        Assert.Equal("7", wb.Value("Customers", "G2"));
        Assert.Equal("9.5", wb.Value("Customers", "H2"));

        Assert.Null(wb.Cell("Customers", "B3"));
        Assert.Null(wb.Cell("Customers", "D3"));
        Assert.Null(wb.Cell("Customers", "H3"));
        Assert.Equal("Suspended", wb.Value("Customers", "E3"));

        Assert.Equal(4u, wb.Format("Customers", "C2").NumberFormatId!.Value);
        Assert.Equal("A1:H3", wb.SheetPart("Customers").Worksheet.Descendants<DocumentFormat.OpenXml.Spreadsheet.AutoFilter>().Single().Reference!.Value);
    }

    [Fact]
    public async Task WriteObjectsAsync_AsyncEnumerable()
    {
        static async IAsyncEnumerable<Customer> Stream()
        {
            foreach (var c in Sample())
            {
                await Task.Yield();
                yield return c;
            }
        }

        var stream = new MemoryStream();
        await using (var writer = new XlsxWriter(stream))
        {
            var sheet = writer.AddWorksheet("S");
            await sheet.AddRowAsync("Report title");
            await sheet.WriteObjectsAsync(Stream(), new ObjectWriteOptions { WriteHeader = false });
        }

        using var wb = Workbook.Load(stream);
        wb.AssertValid();
        Assert.Equal("Report title", wb.Value("S", "A1"));
        Assert.Equal("Alice", wb.Value("S", "B2"));
        Assert.Equal(3, wb.RowCount("S"));
    }

    public sealed record Point(int X, int Y);

    [Fact]
    public async Task Records_AreSupported()
    {
        var stream = new MemoryStream();
        await using (var writer = new XlsxWriter(stream))
        {
            writer.AddWorksheet("P").WriteObjects([new Point(1, 2), new Point(3, 4)]);
        }

        using var wb = Workbook.Load(stream);
        wb.AssertValid();
        Assert.Equal("X", wb.Value("P", "A1"));
        Assert.Equal("4", wb.Value("P", "B3"));
    }
}
