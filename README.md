<p align="center">
  <img src="docs/assets/banner.svg" alt="Hypaxel: streaming XLSX writers for .NET, Rust and Python" width="100%">
</p>

<p align="center">
  <b>High-performance, constant-memory Excel (XLSX) writers for .NET, Rust and Python, built for web services.</b>
</p>

<p align="center">
  <img alt=".NET 8 | 9 | 10 | 11" src="https://img.shields.io/badge/.NET-8_%7C_9_%7C_10_%7C_11-512bd4?style=flat-square">
  <img alt="Rust 1.85+" src="https://img.shields.io/badge/Rust-1.85%2B-dea584?style=flat-square">
  <img alt="Python 3.10+" src="https://img.shields.io/badge/Python-3.10%2B-3776ab?style=flat-square">
  <a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/license-Apache_2.0-22c55e?style=flat-square"></a>
</p>

<p align="center">
  <a href="dotnet/README.md">.NET</a> ·
  <a href="rust/README.md">Rust</a> ·
  <a href="python/README.md">Python</a> ·
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

The .NET and Rust implementations share one design but are independent and idiomatic. The Python
package is a thin, idiomatic layer over the Rust core.

## At a glance

| | .NET | Rust | Python |
| --- | --- | --- | --- |
| Directory | [`dotnet/`](dotnet/README.md) | [`rust/`](rust/README.md) | [`python/`](python/README.md) |
| Packages | `Hypaxel`, `Hypaxel.AspNetCore` | `hypaxel`, `hypaxel-axum` | `hypaxel` (wheels built with maturin) |
| Targets | .NET 8, 9, 10, 11 | Rust 1.85+ (edition 2024) | CPython 3.10+ |
| Web integration | ASP.NET Core: `Results.Extensions.Excel(...)`, `this.Excel(...)` | axum: `Xlsx::from_records(...)`, `Xlsx::from_stream(...)` | FastAPI/Starlette `XlsxResponse`, Django, Flask streaming generators |
| Object mapping | `IEnumerable<T>` / `IAsyncEnumerable<T>` with `[ExcelColumn]` | any `T: Serialize` (serde), sync or async `Stream` | dicts, dataclasses, pydantic, pandas, polars, async iterables |
| I/O | any `Stream`, async only (Kestrel-safe) | any `io::Write` or tokio `AsyncWrite` | path, binary file, or in-memory chunks |
| 1M rows × 10 columns | ~1.0 s, 0 B allocated in the write loop | ~0.73 s, ~200 KiB of buffers | ~0.9 s from Python tuples |
| vs. popular library | ~20–25x faster than ClosedXML | ~11–14x faster than rust_xlsxwriter | ~50x faster than XlsxWriter, ~95x faster than openpyxl |

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

**Rust / axum**

```rust
async fn orders(State(pool): State<PgPool>) -> Xlsx {
    Xlsx::from_stream("Orders", sqlx::query_as::<_, Order>("SELECT ...").fetch(&pool)).filename("orders.xlsx")
}
```

## Features

Shared by all three implementations (milestone 1: creating Excel files).

- **Values:** strings, numbers, booleans, dates/times/durations (1900 and 1904 systems), formulas, styled blanks
- **Styles:** fonts, fills, borders, alignment and number formats. Styles are de-duplicated
- **Worksheets:** multiple sheets, column widths, row heights, frozen panes, merged cells, auto filter, hidden sheets, tab colors
- **Strings:** inline (default, constant memory) or a shared string table
- **Compact output:** cell references are only written after gaps
- **Valid output:** checked against the Office Open XML schema validator and read back by independent readers
  (Open XML SDK, calamine, openpyxl, LibreOffice)

## Repository layout

```
dotnet/    .NET solution: src/, tests/, samples/, benchmarks/
rust/      Cargo workspace: hypaxel/, hypaxel-axum/
python/    PyO3 + maturin package over rust/hypaxel: src/ (bindings), hypaxel/ (Python API), tests/, benchmarks/
.github/   CI: dotnet.yml, rust.yml, python.yml, each triggered only by changes to its own code
```

## Roadmap

1. ✅ **Create Excel files** (this milestone)
2. Hyperlinks, data validation, conditional formatting, images, tables, document properties
3. .NET: a source generator for object mapping (AOT-friendly). Rust: optional parallel compression.
   Python: an Arrow-native DataFrame path. More languages: Node.js/TypeScript (napi-rs), then PHP and Go
4. Streaming readers
5. Read-modify-write for templates

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2026 vensas GmbH.
