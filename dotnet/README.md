# Hypaxel for .NET

**High-performance Excel (XLSX) library for .NET, built for ASP.NET Core.**

Hypaxel writes `.xlsx` files as a forward-only stream. Each cell is serialized to UTF-8 XML the moment
you write it and compressed straight into the output, so memory stays flat whether you export
100 rows or 10 million, and the first bytes reach the HTTP client while your query is still running.

- **Fast, no allocations:** writes a million 10-column rows in about 1 second with 0 bytes allocated in the
  write loop (default settings). That's about 20x faster than DOM-based libraries.
- **Async streaming:** never performs synchronous I/O, so it is safe on Kestrel's response body
  (`AllowSynchronousIO = false`) and works with non-seekable streams.
- **ASP.NET Core integration:** `Results.Extensions.Excel(...)` for Minimal APIs and `this.Excel(...)` for MVC.
  It also accepts `IAsyncEnumerable<T>`, so EF Core results stream from the database to the browser.
- **Object mapping:** export any `IEnumerable<T>` / `IAsyncEnumerable<T>` with a header row, frozen pane,
  auto filter and per-column formats. Uses compiled accessors with no boxing.
- **Styling:** fonts, fills, borders, alignment and number formats. Styles are de-duplicated.
- **Valid output:** every test validates the output against the Office Open XML schema with the Open XML SDK.

> Status: **Milestone 1 — creating Excel files.** Reading/editing is planned for later milestones (see roadmap).

> **Rust:** a native Rust implementation with the same design lives in [`rust/`](../rust/README.md):
> the `hypaxel` crate (sync `io::Write` + tokio `AsyncWrite`, serde records, chrono dates) and
> `hypaxel-axum` for streaming downloads from axum.

## Packages

| Package | Purpose |
| --- | --- |
| `Hypaxel` | Core writer (`net8.0`, `net9.0`), no dependencies |
| `Hypaxel.AspNetCore` | `IResult` / `IActionResult` helpers for streaming workbooks as HTTP responses |

## Quick start

```csharp
using Hypaxel;
using Hypaxel.Styling;

await using var writer = XlsxWriter.Create("report.xlsx");

var header = writer.AddStyle(new CellStyle
{
    Font = new Font { Bold = true, Color = ExcelColor.White },
    Fill = new Fill(ExcelColor.FromHex("#305496")),
});
var money = writer.AddStyle(new CellStyle { NumberFormat = NumberFormats.Thousands2 });

var sheet = writer.AddWorksheet("Sales", new WorksheetOptions { FrozenRows = 1 }.SetColumnWidths(20, 12, 14));

await sheet.AddRowAsync(CellValue.From("Product", header), CellValue.From("Date", header), CellValue.From("Revenue", header));
await sheet.AddRowAsync("Widget", new DateOnly(2024, 5, 1), CellValue.From(1234.5, money));
await sheet.AddRowAsync("Gadget", new DateOnly(2024, 5, 2), CellValue.From(99.99, money));
await sheet.AddRowAsync("Total", CellValue.Empty, CellValue.Formula("SUM(C2:C3)", money));

await writer.CompleteAsync();
```

For the hottest loops, write cells one at a time. No `CellValue` is created:

```csharp
foreach (var order in orders)
{
    sheet.Write(order.Id);
    sheet.Write(order.Customer);
    sheet.Write(order.Total, money);
    sheet.Write(order.CreatedAt);      // DateTime → Excel date, formatted automatically
    await sheet.EndRowAsync();         // flushes to the destination when the buffer is full
}
```

## ASP.NET Core

```csharp
using Hypaxel.AspNetCore;

// One line: objects → worksheet with header, frozen top row and auto filter.
app.MapGet("/orders.xlsx", (AppDb db) =>
    Results.Extensions.Excel(db.Orders.AsNoTracking().AsAsyncEnumerable(), "orders"));

// Full control.
app.MapGet("/report.xlsx", () => Results.Extensions.Excel(async (writer, ct) =>
{
    var sheet = writer.AddWorksheet("Summary");
    await sheet.AddRowAsync(["Generated", DateTime.UtcNow], ct);
    await writer.WriteWorksheetAsync("Orders", LoadOrders(), ct);
}, fileName: "report"));

// MVC
public IActionResult Export() => this.Excel(_service.GetRows(), "export");
```

`ExcelResult` sets the content type and `Content-Disposition` headers, turns off response
buffering, and passes `HttpContext.RequestAborted` through so a cancelled download stops the export.

## Object mapping

```csharp
public sealed class Order
{
    [ExcelColumn("Order #", Order = 0, Width = 10)]
    public int Id { get; init; }

    public string Customer { get; init; } = "";

    [ExcelColumn(NumberFormat = "yyyy-mm-dd")]
    public DateOnly Date { get; init; }

    [ExcelColumn(NumberFormat = "#,##0.00")]
    public decimal Amount { get; init; }

    [ExcelIgnore]
    public string InternalNote { get; init; } = "";
}

await writer.WriteWorksheetAsync("Orders", orders);                 // new sheet, header + filter + widths
await sheet.WriteObjectsAsync(moreOrders, new ObjectWriteOptions   // append to an existing sheet
{
    WriteHeader = false,
});
```

Supported member types: `string`, all integer types, `float`, `double`, `decimal`, `bool`,
`DateTime`, `DateTimeOffset`, `DateOnly`, `TimeOnly`, `TimeSpan`, `Guid`, enums (written as names),
`char`, nullable versions of all of these, and any other type via `ToString()`.

## Features (milestone 1)

| Area | Supported |
| --- | --- |
| Values | strings, numbers, booleans, dates/times/durations, formulas, styled blanks, `#NUM!` for NaN/∞ |
| Dates | 1900 and 1904 date systems. Unstyled dates get the viewer's locale date format. Dates before 1900 are written as ISO text |
| Styles | font (name, size, bold, italic, underline, strike, color), solid fill, borders (13 line styles), alignment, number formats |
| Worksheet | multiple sheets, column widths/styles/hidden, row height/style, frozen rows/columns, gridlines, tab color, hidden sheets |
| Ranges | merged cells, auto filter |
| Strings | inline (default) or shared string table. Full XML escaping, control characters as `_xHHHH_`, lone surrogates replaced |
| I/O | any writable `Stream` (seekable or not), file path helper, async and sync APIs |

## Performance

Writing 10 columns (int, 2× string, 2× double, decimal, DateTime, bool, long, unique string) to a
`MemoryStream`. .NET 9, Xeon 2.1 GHz, 4 vCPUs. 100k rows measured with BenchmarkDotNet (ShortRun);
1M rows measured with the `--quick` stopwatch runner.

| Library | 100k rows | Allocated (100k) | 1M rows | Allocated (1M) | File (1M) |
| --- | ---: | ---: | ---: | ---: | ---: |
| **Hypaxel**, typed `Write` | **102 ms** | **7.6 KB** | ~1.0 s | **~0 MB** | 50 MB |
| **Hypaxel**, `AddRowAsync` | 109 ms | 7.6 KB | ~1.1 s | ~0 MB | 50 MB |
| **Hypaxel**, object mapping | **100 ms** | 10.3 KB | ~1.0 s | ~0 MB | 50 MB |
| Hypaxel, shared strings | 145 ms | 10 MB | ~1.5 s | 88 MB | 53 MB |
| SpreadCheetah 1.29 | 107 ms | 10.4 KB | ~1.1 s | ~0 MB | 50 MB |
| ClosedXML 0.105 | 3,324 ms | 1,098 MB | — | — | — |

Allocations stay constant as rows grow (except with shared strings, whose table grows with the
number of distinct values). Memory per concurrent export is therefore bounded by the buffers:
about 64 KiB of XML plus about 96 KiB of compressed output by default.

Run them yourself:

```bash
cd dotnet/benchmarks/Hypaxel.Benchmarks
dotnet run -c Release -- --quick 1000000      # quick stopwatch run
dotnet run -c Release -- --filter '*'         # full BenchmarkDotNet run
```

### Why it's fast

- **No XML library.** Markup is written as precomputed UTF-8 byte literals into a pooled buffer.
  Each cell reserves buffer space once and writes into it directly, with no per-fragment bounds checks.
- **Compact XML.** Cell references (`r="B7"`) are only written after a gap. The format allows this,
  and it removes about 20% of the XML that has to be deflated.
- **Cheap number formatting.** Integral doubles use the integer formatter. Date serials use integer
  arithmetic instead of the general float formatter. Column letters come from a lookup table.
- **SIMD escaping.** `SearchValues` finds the characters that need escaping. Clean text goes through
  the vectorized UTF-16 → UTF-8 transcoder.
- **Async buffering.** `ZipArchive` only writes synchronously, so its output goes to a pooled
  buffer. When the buffer passes `FlushThreshold` (64 KiB by default), it is drained to the
  destination with real async I/O.

## Options

```csharp
new XlsxWriterOptions
{
    CompressionLevel = CompressionLevel.Fastest,  // or Optimal for smaller files
    StringStorage = StringStorage.Inline,         // or SharedStrings for repetitive text
    FlushThreshold = 64 * 1024,                   // bytes buffered before an async write
    WriteCellReferences = false,                  // true = r="A1" on every cell
    Use1904DateSystem = false,
    OwnsStream = false,
};
```

## Constraints

- Rows are written top to bottom, and cells left to right. Use `SkipCells` / `SkipRows` / `BeginRow(n)` to leave gaps.
- One worksheet is open at a time. Adding the next worksheet completes the previous one.
- Column definitions and frozen panes go in `WorksheetOptions` when you create the sheet, because
  they come before the cell data in the file. Merges and filters can be added at any time before the sheet completes.
- Formulas are stored without cached results. Excel recalculates them when the file opens.

## Roadmap

1. **Create Excel files** (this milestone)
2. Hyperlinks, data validation, conditional formatting, images, tables (ListObjects), document properties
3. Source generator for object mapping (AOT/trimming-friendly)
4. Streaming reader (`XlsxReader`) with low-allocation row enumeration
5. Read-modify-write for templates

## Building

```bash
cd dotnet
dotnet build
dotnet test
dotnet run --project samples/Hypaxel.Samples.WebApi   # GET /orders.xlsx, /report.xlsx, /api/export
```

## License

Licensed under the [Apache License, Version 2.0](../LICENSE). Copyright 2026 vensas GmbH.
