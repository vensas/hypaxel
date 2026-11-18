using System.Net;
using Microsoft.AspNetCore.Mvc.Testing;

namespace Hypaxel.Tests;

/// <summary>
/// End-to-end tests against the sample app. The test server, like Kestrel, rejects synchronous
/// writes to the response body, so these also prove the writer only performs async I/O.
/// </summary>
public class AspNetCoreTests(WebApplicationFactory<Program> factory) : IClassFixture<WebApplicationFactory<Program>>
{
    [Fact]
    public async Task MinimalApi_StreamsObjects()
    {
        using var client = factory.CreateClient();
        using var response = await client.GetAsync("/orders.xlsx?count=25000");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.Content.Headers.ContentType!.MediaType);
        Assert.Equal("orders.xlsx", response.Content.Headers.ContentDisposition!.FileNameStar);

        using var wb = new Workbook(await response.Content.ReadAsByteArrayAsync());
        wb.AssertValid();
        Assert.Equal(25_001, wb.RowCount("Orders"));
        Assert.Equal("Order #", wb.Value("Orders", "A1"));
        Assert.Equal("25000", wb.Value("Orders", "A25001"));
    }

    [Fact]
    public async Task MinimalApi_CustomReport()
    {
        using var client = factory.CreateClient();
        var bytes = await client.GetByteArrayAsync("/report.xlsx");

        using var wb = new Workbook(bytes);
        wb.AssertValid();
        Assert.Equal(["Summary", "Orders"], wb.SheetNames);
        Assert.Equal("Quarterly revenue", wb.Value("Summary", "A1"));
        Assert.Equal("SUM(B4:B7)", wb.Cell("Summary", "B8")!.CellFormula!.Text);
    }

    [Fact]
    public async Task Mvc_Controller()
    {
        using var client = factory.CreateClient();
        using var response = await client.GetAsync("/api/export");
        response.EnsureSuccessStatusCode();
        Assert.Equal("export.xlsx", response.Content.Headers.ContentDisposition!.FileNameStar);

        using var wb = new Workbook(await response.Content.ReadAsByteArrayAsync());
        wb.AssertValid();
        Assert.Equal(5_001, wb.RowCount("Sheet1"));
    }
}
