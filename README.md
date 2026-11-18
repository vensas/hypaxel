# Hypaxel

**High-performance, constant-memory Excel (XLSX) writers for .NET and Rust, built for web services.**

Hypaxel writes `.xlsx` files as a forward-only stream. Each cell is serialized to UTF-8 XML the moment
it is written and compressed straight into a small buffer that flushes to the destination. Memory stays
flat whether you export 10 rows or 10 million, and the first bytes reach the HTTP client while your
query is still running.

The two implementations share one design but are independent and idiomatic. Neither wraps the other.

| | .NET | Rust |
| --- | --- | --- |
| Directory | [`dotnet/`](dotnet/README.md) | [`rust/`](rust/README.md) |
| Packages | `Hypaxel`, `Hypaxel.AspNetCore` | `hypaxel`, `hypaxel-axum` |
| Targets | .NET 8, .NET 9 | Rust 1.85+ (edition 2024) |
| Web integration | ASP.NET Core: `Results.Extensions.Excel(...)`, `this.Excel(...)` | axum: `Xlsx::from_records(...)`, `Xlsx::from_stream(...)` |
| Object mapping | `IEnumerable<T>` / `IAsyncEnumerable<T>` with `[ExcelColumn]` | any `T: Serialize` (serde), sync or async `Stream` |
| I/O | any `Stream`, async only (Kestrel-safe) | any `io::Write` or tokio `AsyncWrite` |
| 1M rows × 10 columns | ~1.0 s, 0 B allocated in the write loop | ~0.73 s, ~200 KiB of buffers |
| vs. popular library | ~20–30x faster than ClosedXML | ~11–14x faster than rust_xlsxwriter |

## Quick look

**.NET / ASP.NET Core**

```csharp
app.MapGet("/orders.xlsx", (AppDb db) =>
    Results.Extensions.Excel(db.Orders.AsNoTracking().AsAsyncEnumerable(), "orders"));
```

**Rust / axum**

```rust
async fn orders(State(pool): State<PgPool>) -> Xlsx {
    Xlsx::from_stream("Orders", sqlx::query_as::<_, Order>("SELECT ...").fetch(&pool)).filename("orders.xlsx")
}
```

## Shared feature set (milestone 1: creating Excel files)

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
.github/   CI: dotnet.yml and rust.yml, each triggered only by changes in its own directory
```

## Roadmap

1. **Create Excel files** (this milestone)
2. Hyperlinks, data validation, conditional formatting, images, tables, document properties
3. .NET: a source generator for object mapping (AOT-friendly). Rust: optional parallel compression
4. Streaming readers
5. Read-modify-write for templates

## License

Not yet chosen.
