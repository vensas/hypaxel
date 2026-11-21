<p align="center">
  <img src="docs/assets/banner.svg" alt="Hypaxel: streaming XLSX writers for .NET, Rust, Python, Node.js, browsers (WebAssembly), Go, Java and PHP" width="100%">
</p>

<p align="center">
  <b>High-performance, constant-memory Excel (XLSX) writers for .NET, Rust, Python, Node.js, browsers (WebAssembly), Go, Java and PHP, built for web services. Streaming readers for .NET, Rust, Python, Node.js, browsers, Go and Java.</b>
</p>

<p align="center">
  <img alt=".NET 8 | 9 | 10 | 11" src="https://img.shields.io/badge/.NET-8_%7C_9_%7C_10_%7C_11-512bd4?style=flat-square">
  <img alt="Rust 1.85+" src="https://img.shields.io/badge/Rust-1.85%2B-dea584?style=flat-square">
  <img alt="Python 3.10+" src="https://img.shields.io/badge/Python-3.10%2B-3776ab?style=flat-square">
  <img alt="Node.js 22+" src="https://img.shields.io/badge/Node.js-22%2B-5fa04e?style=flat-square">
  <img alt="WebAssembly: Chrome/Edge 96+ | Firefox 79+ | Safari 15+" src="https://img.shields.io/badge/WebAssembly-Chrome%2FEdge_96%2B_%7C_Firefox_79%2B_%7C_Safari_15%2B-654ff0?style=flat-square">
  <img alt="Go 1.26+" src="https://img.shields.io/badge/Go-1.26%2B-00add8?style=flat-square">
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-e76f00?style=flat-square">
  <img alt="PHP 8.2+" src="https://img.shields.io/badge/PHP-8.2%2B-777bb4?style=flat-square">
  <a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/license-Apache_2.0-22c55e?style=flat-square"></a>
</p>

<p align="center">
  <a href="dotnet/README.md">.NET</a> ·
  <a href="rust/README.md">Rust</a> ·
  <a href="python/README.md">Python</a> ·
  <a href="node/README.md">Node.js</a> ·
  <a href="node/README.md#browsers">Browsers</a> ·
  <a href="go/README.md">Go</a> ·
  <a href="java/README.md">Java</a> ·
  <a href="php/README.md">PHP</a> ·
  <a href="#quick-look">Quick look</a> ·
  <a href="#features">Features</a> ·
  <a href="#roadmap">Roadmap</a>
</p>

---

Hypaxel writes `.xlsx` files as a forward-only stream. Each cell is serialized to UTF-8 XML the moment
it is written and compressed straight into a small buffer that flushes to the destination. Memory stays
flat whether you export 10 rows or 10 million, and the first bytes reach the HTTP client while your
query is still running.

```text
 rows ──▶ ▓▓▓ UTF-8 XML ──▶ ▒▒▒ deflate ──▶ ░░░ small buffer ──▶ HTTP response / file
          one cell at a time                 flushed as it fills
```

All implementations share one design. .NET, Rust, Go, Java and PHP are independent native engines
with no runtime dependencies; the Python and Node.js packages are thin, idiomatic layers over the
Rust core. The Node.js package also runs in browsers, on the Rust core compiled to WebAssembly.

## At a glance

| | Package | Runs on | Engine | Web integration | 1M rows × 10 columns | Compared with |
| --- | --- | --- | --- | --- | ---: | --- |
| [.NET](dotnet/README.md) | `Hypaxel`, `Hypaxel.AspNetCore` | .NET 8, 9, 10, 11 | native | ASP.NET Core: `Results.Extensions.Excel(...)`, `this.Excel(...)` | ~0.7–0.8 s on .NET 9+, < 1 MB allocated | ~19–24x faster than ClosedXML (100k rows) |
| [Rust](rust/README.md) | `hypaxel`, `hypaxel-axum` | Rust 1.85+ | native | axum: `Xlsx::from_records(...)`, `Xlsx::from_stream(...)` | ~0.7 s, 7.1 MB peak | ~8–9x faster than rust_xlsxwriter |
| [Python](python/README.md) | `hypaxel` | CPython 3.10+ | Rust core (PyO3) | FastAPI/Starlette `XlsxResponse`, Django, Flask | ~0.75 s, 53 MB Python heap (the output) | ~50x faster than XlsxWriter, ~85x faster than openpyxl |
| [Node.js](node/README.md) | `hypaxel` | Node.js 22+ | Rust core (napi-rs) | `stream()` for Express, Fastify, Koa; `webStream()` for Next.js, Hono | ~1.2 s, 101 MB peak (most of it Node.js) | ~6x faster than ExcelJS's streaming writer |
| [Browsers](node/README.md#browsers) | `hypaxel` | Chrome/Edge 96+, Firefox 79+, Safari 15+ | Rust core (WebAssembly) | `toBlob()` for downloads, `webStream()` | 300k rows: ~0.5 s in Chrome, 2.4 MB WebAssembly memory | ~14x faster than SheetJS, ~19x faster than ExcelJS |
| [Go](go/README.md) | `github.com/vensas/hypaxel/go` | Go 1.26+ | native | `hypaxelhttp.Handler`, `hypaxelhttp.Serve` | ~0.85 s, 21 MB peak | ~4.5–5x faster than excelize's `StreamWriter` |
| [Java](java/README.md) | `de.vensas:hypaxel` | Java 17+ | native | any `OutputStream`: Servlet, Spring `StreamingResponseBody` | ~1.1 s, 0.16 MB allocated | ~2.5–3.5x faster than FastExcel, ~8–9x faster than Apache POI SXSSF |
| [PHP](php/README.md) | `vensas/hypaxel` | PHP 8.2+ | native | `Xlsx::download()`, Symfony `StreamedResponse`, Laravel `streamDownload()` | ~3.9 s (~2.9 s with JIT), 4.0 MB peak | ~11–13x faster than OpenSpout, ~40x faster than PhpSpreadsheet (100k rows) |

Timings come from each implementation's own benchmark, all run one after another on the same
Apple M1 Max (macOS 26.6). The benchmarks differ in method (warm-up, runs, destination), so compare
each row with its "compared with" library, not the rows with each other. Each README has the full tables, versions and how to reproduce them. None of the packages
is published to a registry yet.

"Peak" is the peak resident memory of the whole process, including the runtime, except for PHP,
where it is `memory_get_peak_usage()`; Python's write figure is its `tracemalloc` heap peak. .NET and Java report the bytes the writer allocates instead.
Paired read figures are for a file with inline strings and one with a shared string table. The figures are also published as JSON in
[`benchmarks/highlights.json`](benchmarks/highlights.json) and each implementation's
`benchmarks/results/` directory.

**Reading** (.NET, Rust, Python, Node.js and browsers, Go and Java) streams rows the same way,
with memory bounded by the workbook's shared strings plus one row. PHP does not read yet.

| | Read 1M rows × 10 columns | Compared with |
| --- | ---: | --- |
| [.NET](dotnet/README.md#reading) `XlsxReader` | ~0.7–0.8 s (~0.5–0.6 s with background decompression), peak 49 MB inline / 141 MB shared | ~2.6x faster than Sylvan.Data.Excel, ~6x faster than ExcelDataReader, 9–15x faster than MiniExcel |
| [Rust](rust/README.md#reading) `XlsxReader` | ~0.72–0.75 s, peak 7 MB inline / 57 MB shared | ~2.8x faster than calamine's cell reader, 15–115x less memory than calamine's `Range` |
| [Python](python/README.md#reading) `iter_rows`, `read_dataframe` | ~1.3 s, 68 MB peak | ~26x faster than openpyxl (100k rows), ~3.4x faster than python-calamine with ~13x less memory |
| [Node.js](node/README.md#reading) `openWorkbook`, `readRows` | ~1.6–1.7 s*, peak 88 MB inline / 148 MB shared (most of it Node.js) | ~8x faster than ExcelJS's streaming reader, ~13x faster than SheetJS (100k rows) |
| [Go](go/README.md#reading) `OpenReader` | ~1.1–1.2 s*, peak 21 MB inline / 74 MB shared | ~10–14x faster than excelize's `Rows` and xlsxreader |
| [Java](java/README.md#reading) `XlsxReader` | ~0.7 s, peak 354 MB inline / 157 MB shared | ~4x faster than the FastExcel reader, ~5–7x faster than POI's streaming XSSFReader |

\* The Go and Node.js files carry a cell reference on every cell, as Excel writes them (ExcelJS
needs them), so they are larger than the other benchmarks' files: about 40% for Go, and 94 MB
instead of 55 MB for Node.js. As above, compare each row with its own competitors.

```python
for row in hypaxel.iter_rows("orders.xlsx", "Orders"):
    ...
df = hypaxel.read_dataframe("orders.xlsx")          # pandas or polars
```

## Quick look

**.NET / ASP.NET Core**

```csharp
app.MapGet("/orders.xlsx", (AppDb db) =>
    Results.Extensions.Excel(db.Orders.AsNoTracking().AsAsyncEnumerable(), "orders"));
```

**Python / FastAPI**

```python
@app.get("/orders.xlsx")
async def orders():
    return XlsxResponse(hypaxel.astream_records(fetch_orders()), filename="orders.xlsx")
```

**Node.js / Express**

```ts
app.get('/orders.xlsx', async (req, res) => {
  res.setHeader('Content-Type', CONTENT_TYPE)
  await pipeline(stream(db.orders.cursor()), res)
})
```

**Browser (WebAssembly)**

```ts
import { toBlob } from 'hypaxel'   // bundlers pick the WebAssembly build

const blob = await toBlob(orders, { sheetName: 'Orders' })
const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: 'orders.xlsx' })
link.click()
```

**Go / net/http**

```go
http.Handle("/orders.xlsx", hypaxelhttp.Handler("orders", func(r *http.Request, w *hypaxel.Writer) error {
	return hypaxel.WriteSheetSeq2(w, "Orders", db.Orders(r.Context()))
}))
```

**Java / Spring MVC**

```java
StreamingResponseBody body = out -> {
    try (XlsxWriter writer = XlsxWriter.create(out); Stream<Order> orders = repository.streamAll()) {
        writer.writeWorksheet("Orders", Order.class, orders);
    }
};
```

**PHP**

```php
Xlsx::download('orders.xlsx', $pdo->query('SELECT id, customer, amount FROM orders', PDO::FETCH_ASSOC));
```

**Rust / axum**

```rust
async fn orders(State(pool): State<PgPool>) -> Xlsx {
    Xlsx::from_stream("Orders", sqlx::query_as::<_, Order>("SELECT ...").fetch(&pool)).filename("orders.xlsx")
}
```

## Features

Shared by all seven implementations (milestone 1: creating Excel files).

- **Values:** strings, numbers, booleans, dates/times/durations (1900 and 1904 systems), formulas, styled blanks
- **Styles:** fonts, fills, borders, alignment and number formats. Styles are de-duplicated
- **Worksheets:** multiple sheets, column widths, auto-fit columns, row heights, row grouping (outline levels), frozen panes, merged cells, auto filter, Excel tables, hidden sheets, tab colors
- **Strings:** inline (default, constant memory) or a shared string table
- **Compact output:** cell references are only written after gaps
- **Valid output:** checked against the Office Open XML schema validator and read back by independent readers
  (Open XML SDK, calamine, openpyxl, LibreOffice)

Reading (.NET, Rust, Python, Node.js and browsers, Go, Java): streamed rows of strings, numbers, booleans, errors and dates, times and
durations recognised from number formats, in both date systems; sheet list with visibility; Excel
tables, row outline levels, hidden and collapsed rows, row heights, column widths and outline
settings, so a file written with Hypaxel can be checked with Hypaxel alone; files
from Excel, LibreOffice and other producers, including Strict Open XML; clear errors for legacy
`.xls`, encrypted and damaged files; limits for untrusted input.

## Repository layout

```
dotnet/    .NET solution: src/, tests/, samples/, benchmarks/
rust/      Cargo workspace: hypaxel/, hypaxel-axum/, benchmarks/results/
python/    PyO3 + maturin package over rust/hypaxel: src/ (bindings), hypaxel/ (Python API), tests/, benchmarks/
node/      npm package over rust/hypaxel: engine/ (shared), src/ (napi-rs), wasm/ (WebAssembly), ts/ (TypeScript API), test/, benchmarks/
go/        Go module: the library, hypaxelhttp/, and tests/ (nested module with read-back tests and benchmarks)
java/      Maven project: src/, benchmarks/ (separate project)
php/       Composer package: src/, tests/, benchmarks/, examples/
benchmarks/ highlights.json and the format of each implementation's benchmarks/results/*.json
scripts/   check_benchmarks.py: checks the benchmark JSON against the README tables
testdata/  workbooks saved by Excel and LibreOffice, read by every reader's tests
.github/   CI: one workflow per implementation, each triggered only by changes to its own code
```

## Roadmap

1. ✅ **Create Excel files** (this milestone)
2. Hyperlinks, data validation, conditional formatting, images, document properties
3. .NET: a source generator for object mapping (AOT-friendly). Rust: optional parallel compression.
   Python: an Arrow-native DataFrame path
4. ✅ Streaming readers for .NET, Rust, Python, Node.js (and browsers), Go and Java; PHP when there is demand
5. Read-modify-write for templates

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). First-time
contributors sign the [Contributor License Agreement](CLA.md) on their pull request.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2026 vensas GmbH.
