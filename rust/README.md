# Hypaxel for Rust

**High-performance, constant-memory Excel (XLSX) writer for Rust, built for web services.**

Hypaxel writes `.xlsx` files as a forward-only stream. Each cell is serialized to UTF-8 XML the moment
you write it and compressed straight into a small buffer, which is flushed to any `io::Write` or tokio
`AsyncWrite`. Memory stays flat whether you export 10 rows or 10 million, and the first bytes reach
the HTTP client while your query is still running.

- **Fast:** writes 1M rows × 10 mixed columns in about 0.75 s, roughly 11–14x faster than
  rust_xlsxwriter (see the benchmarks below).
- **Constant memory:** about 200 KiB of buffers per workbook. The example axum server peaked at
  **5.4 MB RSS for the whole process** after three 3M-row downloads, and at 11 MB with 8
  concurrent downloads.
- **Streams anywhere:** no `Seek` is needed. The writer has its own streaming ZIP implementation
  with automatic ZIP64 for worksheets over 4 GB.
- **axum integration:** `hypaxel-axum` turns iterators, closures and async `Stream`s (e.g. `sqlx`)
  into download responses, with backpressure and cancellation when the client disconnects.
- **Ergonomic:** rows from tuples, arrays and slices, and any `T: Serialize` as records (serde
  field names become headers). Supports chrono dates, styles, merged cells, frozen panes, auto
  filters and formulas.
- **Valid output:** every feature is checked against the Office Open XML schema validator, and
  the files are read back with calamine, openpyxl and LibreOffice.
- **Safe Rust:** `#![deny(unsafe_code)]`.

> Status: **Milestone 1: creating Excel files.** The .NET version lives in the repository root.

## Crates

| Crate | Purpose |
| --- | --- |
| `hypaxel` | Core writer. Features: `chrono` (default), `serde` (default), `tokio` |
| `hypaxel-axum` | `Xlsx` response type for axum 0.8 |

## Quick start

```rust
use hypaxel::{formats, Color, Formula, SheetOptions, Style, Styled, XlsxWriter};

fn main() -> hypaxel::Result<()> {
    let file = std::io::BufWriter::new(std::fs::File::create("report.xlsx")?);
    let mut writer = XlsxWriter::new(file);

    let header = writer.add_style(&Style::new().bold().font_color(Color::WHITE).fill(Color::hex("#305496").unwrap()));
    let money = writer.add_style(&Style::new().num_format(formats::THOUSANDS2));

    let options = SheetOptions::new().freeze_rows(1).column_widths(&[20.0, 14.0, 14.0]);
    let mut sheet = writer.add_worksheet_with("Sales", &options)?;

    sheet.add_row((Styled("Product", header), Styled("Date", header), Styled("Revenue", header)))?;
    sheet.add_row(("Widget", chrono::NaiveDate::from_ymd_opt(2024, 5, 1).unwrap(), Styled(1234.5, money)))?;
    sheet.add_row(("Gadget", chrono::NaiveDate::from_ymd_opt(2024, 5, 2).unwrap(), Styled(99.99, money)))?;
    sheet.write("Total").skip_cells(1).write_styled(Formula("SUM(C2:C3)"), money).end_row()?;

    writer.finish()?;
    Ok(())
}
```

Cell methods (`write`, `write_styled`, `skip_cells`) are infallible and chainable. Problems at
the cell level are reported by the next `end_row` / `add_row` / `finish`, for example text over
32,767 characters or more than 16,384 columns. The offending cell is left empty, so the file
stays valid.

## Serde records

```rust
#[derive(serde::Serialize)]
struct Order {
    #[serde(rename = "Order #")]
    id: u32,
    customer: String,
    #[serde(serialize_with = "hypaxel::records::excel_date")]   // native, sortable Excel date
    placed: chrono::NaiveDate,
    amount: f64,
    note: Option<String>,                                       // None → empty cell
}

// New worksheet: styled + frozen header, auto filter, estimated column widths.
writer.write_sheet_records("Orders", &orders)?;

// Or append to an existing sheet with per-column formats:
use hypaxel::records::RecordOptions;
sheet.write_records(&orders, &RecordOptions::new().header(false).column_style("amount", money))?;
```

Structs, maps, tuples and scalars all work. Unit enum variants are written by name. Without
`excel_date`, chrono values serialize as ISO strings (text cells). With other serializers such as
JSON, the `excel_date` fields still produce ordinary ISO strings.

## axum

```rust
use axum::{Router, routing::get};
use hypaxel_axum::Xlsx;

// Iterator → worksheet, generated on the blocking pool and streamed while it is produced.
async fn orders() -> Xlsx {
    Xlsx::from_records("Orders", load_orders()).filename("orders.xlsx")
}

// Async source (e.g. sqlx): rows go from the database to the client without being collected.
async fn export(State(pool): State<PgPool>) -> Xlsx {
    let rows = sqlx::query_as::<_, Order>("SELECT ...").fetch(&pool);
    Xlsx::from_stream("Orders", rows).filename("export.xlsx")
}

// Full control with synchronous code ...
async fn report() -> Xlsx {
    Xlsx::from_fn(|w| {
        let mut sheet = w.add_worksheet("Summary")?;
        sheet.add_row(("Generated", chrono::Utc::now()))?;
        w.write_sheet_records("Orders", load_orders())
    })
}

// ... or with async code.
async fn live() -> Xlsx {
    Xlsx::from_async_fn(|mut w| async move {
        let mut sheet = w.add_worksheet("Live")?;
        while let Some(tick) = next_tick().await {
            sheet.add_row_async((tick.at, tick.value)).await?;
        }
        Ok(w)
    })
}
```

`Xlsx` sets `Content-Type`, `Content-Disposition` (RFC 6266/5987 encoded) and `Cache-Control: no-store`.
- **Backpressure:** generation is limited by a bounded channel, so a slow client slows the
  producer down instead of filling memory.
- **Disconnects:** if the client hangs up, the producer's next write fails and generation stops.
- **Errors:** if the producer fails after streaming has started, the body ends with an error, so
  the client sees an aborted download instead of a silently truncated file.

Run the example: `cargo run -p hypaxel-axum --example server`, then open
`http://localhost:3000/orders.xlsx?count=1000000`.

## Async writing (`tokio` feature)

Every flushing method has an `_async` twin for `tokio::io::AsyncWrite` destinations:
`add_row_async`, `end_row_async`, `write_records_async`, `write_records_stream`,
`write_sheet_stream`, `finish_async`.

## Features (milestone 1)

| Area | Supported |
| --- | --- |
| Values | `&str`/`String`/`Cow`, all integers, `f32`/`f64`, `bool`, `char`, `Option<T>`, `Formula`, `Blank`, `Styled<T>`. NaN/∞ → `#NUM!` |
| Dates | chrono `NaiveDate`, `NaiveDateTime`, `DateTime<Tz>`, `NaiveTime`, `TimeDelta`. 1900/1904 systems. Unstyled dates get the viewer's locale format. Dates before 1900 are written as ISO text |
| Styles | font (name, size, bold, italic, underline, strike, color), solid fill, 13 border styles, alignment, number formats; de-duplicated |
| Worksheet | multiple sheets, column widths/styles/hidden, row height/style, frozen panes, gridlines, tab color, hidden sheets |
| Ranges | merged cells, auto filter |
| Strings | inline (default) or shared string table. Full XML escaping, control characters as `_xHHHH_` |
| Rows | tuples (up to 16 elements), arrays, `Vec`, slices, serde records, async streams |
| I/O | any `io::Write` or tokio `AsyncWrite`. No `Seek` needed. ZIP64 |

## Performance

The same 10-column dataset as the .NET benchmark: int, 2× string, 2× float, datetime, bool, i64,
unique string, float. Output goes to a `Vec<u8>`. Linux, Xeon 2.1 GHz, 4 vCPUs, Rust 1.97,
`--release`.

| Library | 100k rows (Criterion) | 1M rows | File (1M) |
| --- | ---: | ---: | ---: |
| **hypaxel** (tuples) | **74 ms** | **~0.75 s** | 50 MB |
| **hypaxel** (serde records) | 75 ms | ~0.75 s | 50 MB |
| hypaxel (shared strings) | 100 ms | ~1.4 s | 53 MB |
| hypaxel (deflate level 6) | 316 ms | ~3.1 s | 31 MB |
| rust_xlsxwriter 0.102 (constant memory) | 796 ms | ~8.7 s | 57 MB |
| rust_xlsxwriter 0.102 | 1,019 ms | ~11.0 s | 59 MB |

About half of the remaining time is deflate (zlib-rs, level 1). The other half is generating
about 330 MB of XML at roughly 35 ns per cell.

```bash
cargo bench -p hypaxel                          # Criterion, 10k and 100k rows
HYPAXEL_QUICK=1000000 cargo bench -p hypaxel    # stopwatch run, 1M rows
```

### Why it's fast

- **Hand-written XML.** Markup is appended as byte literals to one reused buffer, with no XML library.
- **Compact XML.** Cell references (`r="B7"`) are only written after a gap. The format allows this,
  and it removes about 20% of the XML.
- **Cheap number formatting.** `itoa` for integers, `zmij` for floats, an integer fast path for
  integral floats, and integer-only arithmetic for date serials.
- **Table-driven escaping.** Clean text is copied in one `memcpy`.
- **Fast deflate.** zlib-rs at level 1, plus a hand-rolled streaming ZIP that uses data
  descriptors, so nothing is ever patched after it is written.
- **Bounded buffering.** Compressed bytes are buffered up to `flush_threshold` (64 KiB), then
  written in one call (sync or async).

## Options

```rust
Options::new()
    .compression_level(1)                     // 0–9; 6 ≈ 40% smaller files at ~4x the CPU
    .string_storage(StringStorage::Inline)    // or Shared for repetitive text
    .flush_threshold(64 * 1024)               // bytes buffered before a write
    .cell_references(false)                   // true = r="A1" on every cell
    .date_1904(false);
```

## Constraints

- Rows are written top to bottom, and cells left to right. Use `skip_cells`, `skip_rows` and
  `begin_row_at` to leave gaps.
- One worksheet is open at a time. Adding the next worksheet completes the previous one.
- Column definitions and frozen panes go in `SheetOptions` when you create the sheet, because they
  come before the cell data in the file. Merges and filters can be added at any time before the
  sheet completes.
- Formulas are stored without cached results. Excel recalculates them when the file opens.

## Testing

```bash
cargo test --workspace --all-features
```

The tests read files back with calamine and inspect the raw parts with the `zip` crate. The axum
tests drive real routers, including error propagation and client disconnects.
