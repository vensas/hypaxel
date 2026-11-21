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

> Status: **Milestone 1 — creating Excel files**, plus a **streaming reader** (`XlsxReader`, see [Reading](#reading)).
> Editing existing workbooks is planned for a later milestone (see roadmap).

> **Rust:** a native Rust implementation with the same design lives in [`rust/`](../rust/README.md):
> the `hypaxel` crate (sync `io::Write` + tokio `AsyncWrite`, serde records, chrono dates) and
> `hypaxel-axum` for streaming downloads from axum.

## Packages

| Package | Purpose |
| --- | --- |
| `Hypaxel` | Core writer and streaming reader (`net8.0`, `net9.0`, `net10.0`, `net11.0`), no dependencies |
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

## Auto-fit columns

Turn on `AutoFit` and columns without an explicit width are sized to their content:

```csharp
var sheet = writer.AddWorksheet("Orders", new WorksheetOptions { FrozenRows = 1 }.AutoFitColumns());

// Or tune the sample and cap the width (in characters):
new WorksheetOptions { AutoFit = new AutoFitOptions { SampleRows = 500, MaxWidth = 60 } };
```

Column widths come before the cell data in the file, so the writer holds back the sheet's XML
while it measures the first `SampleRows` rows (1,000 by default). It then writes the widths and
streams the rest as usual. Memory is bounded by the sample: at most `SampleRows` rows or 8 MiB
of XML, whichever comes first. Rows after the sample are not measured.

Widths are estimated from the text, the number format (dates, `#,##0.00`, percentages, …) and the
font size and weight of each cell. Formulas and blank cells are not measured. Widths you set with
`SetColumn` / `SetColumnWidths` always win.

## Excel tables

`AddTable` turns a range into an Excel table (a `ListObject`), with a header row, filter buttons,
banded rows and names you can use in formulas such as `=SUM(Orders[Units])`:

```csharp
var sheet = writer.AddWorksheet("Orders");
await sheet.AddRowAsync("Product", "Units");
await sheet.AddRowAsync("Widget", 3);
await sheet.AddRowAsync("Gadget", 5);

sheet.AddTable(new ExcelTable(1, 1, sheet.RowNumber, 2)
{
    Name = "Orders",                    // optional: Table1, Table2, … otherwise
    Columns = ["Product", "Units"],     // must match the header row
    Style = TableStyle.Medium(9),       // Light(1–21), Medium(1–28), Dark(1–11) or None
});
```

With a header row (the default), `Columns` must list the header texts exactly as written, because
Excel identifies table columns by those names. Table names are unique in the workbook and follow
Excel's name rules. Tables cannot overlap each other, merged cells or the worksheet auto filter.
The range may include rows you have not written yet.

## Row grouping

Rows can be grouped into collapsible outline levels. Declare the deepest level when you add the
worksheet, because Excel reads it before the rows:

```csharp
var sheet = writer.AddWorksheet("Regions", new WorksheetOptions
{
    RowOutlineLevels = 1,           // deepest level used, 0–7
    OutlineSummaryAbove = true,     // the group's button sits on the row above it
});

await sheet.AddRowAsync("North", 300);
sheet.BeginRow(new RowOptions { OutlineLevel = 1 });                  // AddRow fills the begun row
await sheet.AddRowAsync("Store A", 120);

sheet.BeginRow(new RowOptions { OutlineLevel = 1, Hidden = true });   // a collapsed group's rows are hidden
await sheet.AddRowAsync("Store B", 180);
```

`RowOptions` also sets `Height`, `Style` and `Collapsed` (on the summary row of a collapsed group).

## Reading

```csharp
using Hypaxel;

using var reader = XlsxReader.Open("orders.xlsx");     // or new XlsxReader(stream, options)
Console.WriteLine(string.Join(", ", reader.SheetNames));

using var rows = reader.OpenWorksheet("Orders");        // or OpenWorksheet(0)
while (rows.Read())
{
    var row = rows.Row;                                 // reused for every row; Number is the 1-based Excel row
    if (row.TryGetString(1, out var customer)           // by 1-based column
        && row.TryGetDouble(3, out var amount)
        && row.TryGetDateTime(4, out var placed))
    {
        Console.WriteLine($"{row.Number}: {customer} {amount} {placed:yyyy-MM-dd}");
    }

    foreach (var cell in row.Cells)                     // only cells with a value, in column order
    {
        switch (cell.Value.Kind)
        {
            case ExcelValueKind.String: /* cell.Value.TryGetString(...) */ break;
            case ExcelValueKind.DateTime: /* cell.Value.TryGetExcelDateTime(...) */ break;
        }
    }

    ExcelValue[] dense = row.GetValues();               // columns 1..LastColumn, ExcelValue.Empty for gaps
}

// WorksheetReader is also an IEnumerable<XlsxRow>:
foreach (var row in reader.OpenWorksheet(1)) { /* ... */ }
```

- **Values:** `ExcelValue` is a small `readonly struct` with a `Kind` (`Empty`, `String`, `Number`,
  `Boolean`, `DateTime`, `Error`) and `TryGetString` / `TryGetDouble` / `TryGetBoolean` /
  `TryGetDateTime` / `TryGetExcelDateTime` / `TryGetError`. Numbers whose built-in or custom number
  format shows a date, time or duration become `ExcelDateTime`: the raw `Serial`, its `Kind` (`Date`,
  `DateTime`, `Time`, `Duration`), `Is1904`, and `TryGetDateTime`, `TryGetDateOnly`, `TryGetTimeOnly`,
  `TryGetTimeSpan`. Both date systems are supported, including Excel's fictitious 1900-02-29 (serial 60
  has no `DateTime`); times are rounded to the millisecond. `ToString()` gives roughly Excel's
  General format, with dates in ISO 8601. Formulas yield their cached result; a formula that was never
  calculated has no value.
- **Allocations:** numbers, booleans, dates and shared strings are read without allocating. Inline
  strings allocate when you ask for a `string` (`TryGetString`, `GetValue`, `Cells`);
  `row.TryGetText(column, out ReadOnlySpan<char>)` reads any text without allocating.
  `row.GetValues(Span<ExcelValue>)` fills a reused array.
- **Memory:** rows are decompressed and parsed as a stream. What stays in memory is the shared
  string table (text that Excel stores once per workbook, held as `string`s) plus the current row.
  See [Reading performance](#reading-1) for measured peaks.
- **Compatibility:** tested with workbooks from the Hypaxel writer and the Open XML SDK, and with
  handcrafted packages that cover what other producers do: package relationships instead of fixed paths, Strict Open XML, prefixed namespaces, cells and rows
  without references, out-of-order and duplicate cells (the last one wins), rich text, inline strings,
  phonetic runs (skipped), ISO 8601 dates (`t="d"`), CDATA, CRLF line endings and Excel's `_xHHHH_`
  escapes.
- **Safety:** DTDs are rejected (no entity expansion), every XML token (16 MiB) and row (16,384
  cells, 128 Mi characters of text) is bounded, ZIP entries must match their declared size and CRC-32,
  and `XlsxReaderOptions.MaxSharedStringsBytes` (default 1 GiB) caps the shared string table; lower it
  for untrusted uploads. Damaged or malicious input throws `XlsxReadException` (or an `IOException`
  from your stream), never another exception type. Its `Kind` is `InvalidFile` (not an `.xlsx`:
  CSV, legacy `.xls`, a password-protected workbook, a Word file), `Malformed` (with the `Part`, and the
  row in the message), `LimitExceeded` or `Unsupported`. Besides `MaxSharedStringsBytes`, fixed limits
  apply: a sheet may have 4,096 tables holding 64 MiB of table and column names in total, a table 16,384
  columns, and a sheet 16,384 column ranges.
- **Sheets:** `reader.Sheets` lists every sheet with its `Visibility` (`Visible`, `Hidden`,
  `VeryHidden`) and `Kind` (`Worksheet`, `Chartsheet`, `Other`). `OpenWorksheet(name)` matches exactly,
  then ignoring case. Several worksheets of one reader can be open at once on one thread; use one reader
  per thread to read in parallel.
- **Tables:** `reader.GetTables("Orders")` (or `GetTables(0)`) returns the sheet's Excel tables as the
  same `ExcelTable` records that `AddTable` accepts: name, range, column names, style and the display
  options. Tables are stored in their own parts and are read only when you ask, so reading values costs
  the same as before. They come back from top to bottom, then left to right, whatever order the file
  lists them in. `ShowAutoFilter` tells whether the file has filter buttons, and a custom
  (non-built-in) table style reads as `TableStyle.None`. `HasTotalsRow` tells whether the last row of
  the range is a totals row; such tables can be read but not written, so `AddTable` rejects them.
- **Rows and columns:** each `XlsxRow` carries `OutlineLevel`, `Hidden`, `Collapsed` and `Height`
  (null when the row stores no height). `WorksheetReader` has `ColumnWidths` (runs of columns with
  `FirstColumn`, `LastColumn`, `Width` and `Hidden`, explicit and auto-fitted alike),
  `GetColumnWidth(column)`, `IsColumnHidden(column)`, `OutlineSummaryAbove` and `RowOutlineLevels`; they precede the rows in the file, so they are
  available before the first `Read()`. Rows without values are skipped by default, whatever their
  properties; set `new XlsxReaderOptions { IncludeEmptyRows = true }` to also get the rows the file
  stores without values (a spacer row with a height, an empty hidden row), with `Count == 0`.

  ```csharp
  var table = reader.GetTables("Orders")[0];              // table.Name, table.Columns, table.Style, ...
  using var rows = reader.OpenWorksheet("Orders");
  double? width = rows.GetColumnWidth(2);                 // null = default width
  while (rows.Read())
  {
      var (level, hidden) = (rows.Row.OutlineLevel, rows.Row.Hidden);
  }
  ```
- **Large single files:** `new XlsxReaderOptions { BackgroundDecompression = true }` inflates on a
  second thread and reads about 30% faster when cores are idle (see [Reading performance](#reading-1)).
  Leave it off for many concurrent reads.
- **Streams:** the input must be seekable, because the ZIP directory is at the end of the file. Copy a
  non-seekable stream such as a raw request body into a `MemoryStream` or temporary file first. Reading is synchronous.

Not read (yet): formula text, styles beyond date detection, merged ranges, comments, hyperlinks and
data validation.

## Features (milestone 1)

| Area | Supported |
| --- | --- |
| Values | strings, numbers, booleans, dates/times/durations, formulas, styled blanks, `#NUM!` for NaN/∞ |
| Dates | 1900 and 1904 date systems. Unstyled dates get the viewer's locale date format. Dates before 1900 are written as ISO text |
| Styles | font (name, size, bold, italic, underline, strike, color), solid fill, borders (13 line styles), alignment, number formats |
| Worksheet | multiple sheets, column widths/styles/hidden, auto-fit columns, row height/style/hidden, row outline levels, frozen rows/columns, gridlines, tab color, hidden sheets |
| Ranges | merged cells, auto filter, Excel tables (built-in styles) |
| Strings | inline (default) or shared string table. Full XML escaping, control characters as `_xHHHH_`, lone surrogates replaced |
| I/O | any writable `Stream` (seekable or not), file path helper, async and sync APIs |

## Performance

Writing 10 columns (int, 2× string, 2× double, decimal, DateTime, bool, long, unique string) to a
`MemoryStream`, on each supported .NET version.

Measured on an Apple M1 Max (10 cores, macOS 26.6) with .NET 8.0.19, 9.0.0, 10.0.13 and 11.0.0,
against SpreadCheetah 1.29.0 and ClosedXML 0.105.1, on an otherwise idle machine. 100k rows use BenchmarkDotNet 0.15.8 (ShortRun,
in-process toolchain); 1M rows use the `--quick` stopwatch runner, which times a single run after
one warm-up.

**100k rows, mean time**

| Library | .NET 8 | .NET 9 | .NET 10 | .NET 11 |
| --- | ---: | ---: | ---: | ---: |
| Hypaxel, typed `Write` | 116 ms | 85 ms | 73 ms | 71 ms |
| Hypaxel, `AddRowAsync` | 123 ms | 89 ms | 77 ms | 75 ms |
| Hypaxel, object mapping | 116 ms | 83 ms | 78 ms | 74 ms |
| Hypaxel, shared strings | 132 ms | 101 ms | 85 ms | 84 ms |
| SpreadCheetah | 120 ms | 85 ms | 74 ms | 72 ms |
| ClosedXML | 2,192 ms | 1,967 ms | 1,749 ms | 1,729 ms |

**100k rows, allocated**

| Library | .NET 8 | .NET 9 | .NET 10 | .NET 11 |
| --- | ---: | ---: | ---: | ---: |
| Hypaxel, typed `Write` | 8.8 KB | 12 KB | 13 KB | 14 KB |
| Hypaxel, `AddRowAsync` | 8.8 KB | 12 KB | 12 KB | 14 KB |
| Hypaxel, object mapping | 11 KB | 15 KB | 15 KB | 17 KB |
| Hypaxel, shared strings | 10.1 MB | 10.1 MB | 10.1 MB | 10.1 MB |
| SpreadCheetah | 11 KB | 15 KB | 16 KB | 18 KB |
| ClosedXML | 1,190 MB | 1,098 MB | 1,103 MB | 1,087 MB |

**1M rows, time**

| Library | .NET 8 | .NET 9 | .NET 10 | .NET 11 |
| --- | ---: | ---: | ---: | ---: |
| Hypaxel, typed `Write` | 1.18 s | 0.82 s | 0.73 s | 0.71 s |
| Hypaxel, `AddRowAsync` | 1.21 s | 0.86 s | 0.78 s | 0.79 s |
| Hypaxel, object mapping | 1.17 s | 0.81 s | 0.79 s | 0.74 s |
| Hypaxel, shared strings | 1.36 s | 1.03 s | 0.89 s | 0.91 s |
| SpreadCheetah | 1.22 s | 0.83 s | 0.75 s | 0.74 s |

At 1M rows Hypaxel and SpreadCheetah allocate less than 1 MB on every version; shared strings
allocate 88 MB. The file is about 50 MB (53 MB with shared strings), or about
39 MB (41 MB) on .NET 8, whose compression produces smaller output. ClosedXML is not run at 1M rows.

Hypaxel and SpreadCheetah are within measurement noise of each other; both are roughly 19 to 24
times faster than ClosedXML and allocate a small fraction of its memory. .NET 8 is the slowest
target.

**Peak RSS (.NET 10)**

| Library | 100k rows | 1M rows |
| --- | ---: | ---: |
| Rows only, nothing written | 51 MB | 51 MB |
| Hypaxel, typed `Write` | 67 MB | 68 MB |
| Hypaxel, `AddRowAsync` | 64 MB | 66 MB |
| Hypaxel, object mapping | 69 MB | 69 MB |
| Hypaxel, shared strings | 78 MB | 216 MB |
| SpreadCheetah | 61 MB | 68 MB |
| ClosedXML | 344 MB | 2,031 MB |

Peak resident memory of the whole process, the median of three runs, each case in its own process
with the `--write-rss` mode. Rows are generated as they are written and the file goes to disk, so
neither the dataset nor the output is held in memory. The first row is the .NET runtime plus
generating the rows: Hypaxel adds 16 to 17 MB on top of it at 100k rows and at 1M, while ClosedXML
grows with the sheet. Shared strings grow with the number of distinct values.

Allocations stay constant as rows grow (except with shared strings, whose table grows with the
number of distinct values). Memory per concurrent export is therefore bounded by the buffers:
about 64 KiB of XML plus about 96 KiB of compressed output by default.

Run them yourself (pick the .NET version with `-f`):

```bash
cd dotnet/benchmarks/Hypaxel.Benchmarks
dotnet run -c Release -f net10.0 -- --quick 1000000                         # quick stopwatch run
dotnet build -c Release && /usr/bin/time -l \
  dotnet bin/Release/net10.0/Hypaxel.Benchmarks.dll --write-rss hypaxel 1000000 out.xlsx   # one case, peak RSS (macOS)
dotnet run -c Release -f net10.0 -- --filter '*' --job short --inProcess    # BenchmarkDotNet run
```

`--inProcess` is required on .NET 11, which BenchmarkDotNet 0.15.8 does not recognise otherwise.

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

### Reading

The same dataset (1M rows × 10 columns, written by the Hypaxel writer with inline or shared strings),
read cell by cell. Every reader must produce the same checksum (10,000,000 cells, the sum of all
numbers including date serials, 42,833,558 string characters, 500,000 `TRUE`s), and all of them do.

Measured on an Apple M1 Max (10 cores, 64 GB, macOS 26.6) with .NET 8.0.19, 9.0.0, 10.0.13 and
11.0.0, against Sylvan.Data.Excel 0.5.8, ExcelDataReader 3.9.0 and MiniExcel 1.47.1. Times come from
one `--read-quick` stopwatch run per runtime, which runs every reader with one warm-up and then the
best of three runs. Allocations are for one run. Peak RSS comes from a separate process per reader
(`--read-quick 1000000 <reader> <inline|shared>` under `/usr/bin/time -l`) and covers the whole
process, including the runtime. The machine was otherwise idle.

**1M rows, time**

| Reader | File | .NET 8 | .NET 9 | .NET 10 | .NET 11 |
| --- | --- | ---: | ---: | ---: | ---: |
| Hypaxel, `TryGetText`/`TryGetDouble` | inline | 0.71 s | 0.77 s | 0.72 s | 0.72 s |
| Hypaxel, `GetValue` (`ExcelValue`) | inline | 0.78 s | 0.83 s | 0.76 s | 0.76 s |
| Hypaxel, `BackgroundDecompression` | inline | 0.58 s | 0.55 s | 0.51 s | 0.51 s |
| Sylvan.Data.Excel | inline | 2.10 s | 2.01 s | 1.87 s | 1.91 s |
| ExcelDataReader | inline | 5.02 s | 4.69 s | 4.26 s | 4.55 s |
| MiniExcel | inline | 8.79 s | 7.79 s | 6.89 s | 7.13 s |
| Hypaxel, `TryGetText`/`TryGetDouble` | shared | 0.76 s | 0.82 s | 0.79 s | 0.80 s |
| Hypaxel, `GetValue` (`ExcelValue`) | shared | 0.81 s | 0.86 s | 0.81 s | 0.82 s |
| Hypaxel, `BackgroundDecompression` | shared | 0.61 s | 0.59 s | 0.55 s | 0.56 s |
| Sylvan.Data.Excel | shared | 2.08 s | 2.02 s | 1.91 s | 1.96 s |
| ExcelDataReader | shared | 4.99 s | 4.69 s | 4.35 s | 4.57 s |
| MiniExcel | shared | 13.85 s | 12.73 s | 12.00 s | 12.25 s |

**1M rows, memory (.NET 10)**

| Reader | Inline: allocated | Inline: peak RSS | Shared: allocated | Shared: peak RSS |
| --- | ---: | ---: | ---: | ---: |
| Hypaxel, `TryGetText`/`TryGetDouble` | 0.1 MB | 49 MB | 92 MB | 141 MB |
| Hypaxel, `GetValue` (`ExcelValue`) | 153 MB | 55 MB | 92 MB | 135 MB |
| Hypaxel, `BackgroundDecompression` | 0.6 MB | 51 MB | 92 MB | 144 MB |
| Sylvan.Data.Excel | 154 MB | 75 MB | 85 MB | 155 MB |
| ExcelDataReader | 1,937 MB | 76 MB | 1,717 MB | 157 MB |
| MiniExcel | 10,649 MB | 81 MB | 10,157 MB | 82 MB |

Allocations are the same on the other runtimes, except MiniExcel's (10.0 to 13.3 GB per read);
peak RSS was measured on .NET 10 only. With inline strings, the only allocations are the strings
you ask for: the 153 MB are the 3M cell strings, and reading text as a span allocates nothing.
With shared strings, the 92 MB are the shared string table (1M distinct
strings), which is loaded once and then read without allocating. By design, nothing else grows with
the number of rows: the row buffers are sized by the widest row.

**Background decompression.** `XlsxReaderOptions.BackgroundDecompression` inflates and checks each
worksheet (and the shared string table) on a thread-pool thread while the calling thread parses. From the .NET 10 run above: the best of
three runs and the CPU time of that run, summed over all threads:

| Mode | File | Time | CPU time |
| --- | --- | ---: | ---: |
| Default | inline | 0.72 s | 0.74 s |
| `BackgroundDecompression` | inline | 0.51 s | 0.87 s |
| Default | shared | 0.79 s | 0.79 s |
| `BackgroundDecompression` | shared | 0.55 s | 0.92 s |

With several readers at once on the 10 cores (inline file, one process per reader, each reporting its
best of three), a read takes 0.76–0.77 s by default and 0.53–0.54 s with it for 4 readers, but
0.84–0.89 s and 0.92–1.05 s for 10 readers.

It saves about 30% of the time when cores are idle, but costs 15 to 20% more CPU. When every core
is busy, as on a server reading many uploads at once, it makes the total slower. That is why it is
off by default. Each open worksheet holds 1 MiB of buffers (four 256 KiB chunks) while it is on.

```bash
cd dotnet/benchmarks/Hypaxel.Benchmarks
dotnet run -c Release -f net10.0 -- --read-quick 1000000                    # all readers, both files
dotnet run -c Release -f net10.0 -- --read-quick 1000000 Sylvan shared      # one reader, one file
dotnet run -c Release -f net10.0 -- --filter '*ReadBenchmarks*' --job short --inProcess   # BenchmarkDotNet, 100k rows (not used for the tables)
```

The test files are generated in the temp directory on first use.

### Why reading is fast

- **A purpose-built UTF-8 pull parser** instead of `System.Xml.XmlReader`. Element and attribute names
  are compared as bytes and never become strings. A prototype that did the same work with `XmlReader`
  (`DtdProcessing.Prohibit`, an atomized `NameTable`) took 1.90 s and allocated 623 MB on the inline
  file, against 1.57 s and 161 MB for the first version of this parser.
- **Whole cells in one pass.** The cell shapes nearly every producer writes (`<c r="B2" s="1"><v>42</v></c>`,
  `<c t="inlineStr"><is><t>…</t></is></c>`, `<c/>`, `<c></c>`) and plain shared strings
  (`<si><t>…</t></si>`) are matched with their attributes in a single scan over a 4 KiB lookahead.
  Anything else (namespace prefixes, formulas, rich text, CDATA, comments) goes through the general
  parser. Measured against the previous version, interleaved: 1.02 s → 0.79 s inline and 1.12 s → 0.86 s
  shared.
- **Values parsed in place.** Numbers, shared-string indexes and booleans are parsed straight from the
  input buffer (about 4% faster than copying them out first). Decimals with up to 15 digits and no
  exponent are computed as `digits / 10^k`, which is correctly rounded because both operands are exact
  doubles. Everything else falls back to `double.TryParse`.
- **Reused row buffers.** Cells are stored as compact structs in a reused array, with text in one
  reused `char` buffer. Values are materialized only when you ask for them.
- **Cheap integrity checks.** Inflate runs on the runtime's native zlib through `DeflateStream`. The
  reader verifies CRC-32 itself (only .NET 11's `ZipArchive` does), using the ARM64 CRC instructions
  where available and a slicing-by-8 table elsewhere. Inflating the worksheet alone takes 0.14–0.23 s on
  this dataset; `BackgroundDecompression` moves it off the parsing thread. The slicing-by-8 path (x64)
  has not been benchmarked.

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
- Column definitions, frozen panes and the outline depth go in `WorksheetOptions` when you create the sheet,
  because they come before the cell data in the file. Merges, filters and tables can be added at any time
  before the sheet completes.
- Formulas are stored without cached results. Excel recalculates them when the file opens.

## Roadmap

1. **Create Excel files** (this milestone)
2. Hyperlinks, data validation, conditional formatting, images, document properties
3. Source generator for object mapping (AOT/trimming-friendly)
4. ~~Streaming reader (`XlsxReader`) with low-allocation row enumeration~~ (available)
5. Read-modify-write for templates

## Building

```bash
cd dotnet
dotnet build
dotnet test
dotnet run --project samples/Hypaxel.Samples.WebApi -f net10.0   # GET /orders.xlsx, /report.xlsx, /api/export
```

## License

Licensed under the [Apache License, Version 2.0](../LICENSE). Copyright 2026 vensas GmbH.
