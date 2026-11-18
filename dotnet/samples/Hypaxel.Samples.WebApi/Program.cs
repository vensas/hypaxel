using Hypaxel;
using Hypaxel.AspNetCore;
using Hypaxel.Mapping;
using Hypaxel.Styling;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddControllers();
var app = builder.Build();

// 1) Objects -> worksheet in one line. The IAsyncEnumerable is streamed: rows reach the client
//    while they are still being produced (e.g. EF Core's AsAsyncEnumerable()).
app.MapGet("/orders.xlsx", (int? count) =>
    Results.Extensions.Excel(Orders.Generate(count ?? 100_000), "orders", "Orders"));

// 2) Full control: styles, multiple sheets, formulas, merged cells.
app.MapGet("/report.xlsx", () => Results.Extensions.Excel(async (writer, ct) =>
{
    var title = writer.AddStyle(new CellStyle { Font = new Font { Bold = true, Size = 16 } });
    var header = writer.AddStyle(new CellStyle
    {
        Font = new Font { Bold = true, Color = ExcelColor.White },
        Fill = new Fill(ExcelColor.FromHex("#305496")),
        Alignment = new Alignment { Horizontal = HorizontalAlignment.Center },
    });
    var money = writer.AddStyle(new CellStyle { NumberFormat = NumberFormats.Thousands2 });

    var sheet = writer.AddWorksheet("Summary", new WorksheetOptions { FrozenRows = 3 }.SetColumnWidths(18, 14, 14));
    await sheet.AddRowAsync([CellValue.From("Quarterly revenue", title)], ct);
    sheet.MergeCells(1, 1, 1, 3);
    await sheet.EndRowAsync(ct); // blank row 2
    await sheet.AddRowAsync([CellValue.From("Region", header), CellValue.From("Revenue", header), CellValue.From("Share", header)], ct);

    string[] regions = ["North", "South", "East", "West"];
    foreach (var region in regions)
    {
        var row = sheet.RowNumber + 1;
        await sheet.AddRowAsync(
            [region, CellValue.From(Random.Shared.Next(10_000, 99_999) * 1.01, money), CellValue.Formula($"B{row}/SUM(B$4:B${3 + regions.Length})")],
            ct);
    }

    await sheet.AddRowAsync([CellValue.From("Total", title), CellValue.Formula($"SUM(B4:B{3 + regions.Length})", money)], ct);

    await writer.WriteWorksheetAsync("Orders", Orders.Generate(1_000), ct);
}, "report"));

app.MapControllers();
app.Run();

public sealed class Order
{
    [ExcelColumn("Order #", Width = 10)]
    public int Id { get; init; }

    public required string Customer { get; init; }

    [ExcelColumn(NumberFormat = NumberFormats.Date)]
    public DateOnly Date { get; init; }

    [ExcelColumn(NumberFormat = NumberFormats.Thousands2)]
    public decimal Amount { get; init; }

    public bool Paid { get; init; }

    [ExcelIgnore]
    public string InternalNote { get; init; } = "";
}

public static class Orders
{
    public static async IAsyncEnumerable<Order> Generate(int count)
    {
        var start = new DateOnly(2024, 1, 1);
        for (var i = 1; i <= count; i++)
        {
            if (i % 10_000 == 0)
            {
                await Task.Yield(); // stand-in for awaiting the next database page
            }

            yield return new Order
            {
                Id = i,
                Customer = $"Customer {i % 977}",
                Date = start.AddDays(i % 365),
                Amount = (i * 37 % 100_000) / 100m,
                Paid = i % 3 != 0,
            };
        }
    }
}

[Microsoft.AspNetCore.Mvc.ApiController]
[Microsoft.AspNetCore.Mvc.Route("api/[controller]")]
public sealed class ExportController : Microsoft.AspNetCore.Mvc.ControllerBase
{
    // MVC flavor: GET /api/export
    [Microsoft.AspNetCore.Mvc.HttpGet]
    public Microsoft.AspNetCore.Mvc.IActionResult Get() => this.Excel(Orders.Generate(5_000), "export");
}

public partial class Program;
