# Hypaxel for Rust

**High-performance, constant-memory Excel (XLSX) writer and streaming reader for Rust, built for web services.**

Hypaxel writes `.xlsx` files as a forward-only stream. Each cell is serialized to UTF-8 XML the moment
you write it and compressed straight into a small buffer, which is flushed to any `io::Write` or tokio
`AsyncWrite`. Memory stays flat whether you export 10 rows or 10 million, and the first bytes reach
the HTTP client while your query is still running.

- **Fast:** writes 1M rows × 10 mixed columns in about 0.7 s, roughly 8–9x faster than
  rust_xlsxwriter (see the benchmarks below).
- **Constant memory:** about 200 KiB of buffers per workbook. The example axum server peaked at
  **8.5 MB RSS for the whole process** after three 3M-row downloads, and at 15.5 MB with 8
  concurrent downloads (macOS, M1 Max).
- **Streams anywhere:** no `Seek` is needed. The writer has its own streaming ZIP implementation
  with automatic ZIP64 for worksheets over 4 GB.
- **axum integration:** `hypaxel-axum` turns iterators, closures and async `Stream`s (e.g. `sqlx`)
  into download responses, with backpressure and cancellation when the client disconnects.
- **Ergonomic:** rows from tuples, arrays and slices, and any `T: Serialize` as records (serde
  field names become headers). Supports chrono dates, styles, merged cells, frozen panes, auto
  filters, Excel tables, row grouping, auto-fit columns and formulas.
- **Valid output:** every feature is checked against the Office Open XML schema validator, and
  the files are read back with calamine, openpyxl and LibreOffice.
- **Streaming reader:** `XlsxReader` reads any `Read + Seek` source one row at a time, about
  about 2.8x faster than calamine's cell reader. Dates are recognised from number formats, and damaged
  or hostile files fail with a clear error. See [Reading](#reading).
- **Safe Rust:** `#![deny(unsafe_code)]`.

> Status: **creating Excel files** (milestone 1) and **streaming reads**. The .NET version lives in [`dotnet/`](../dotnet/README.md).

## Crates

| Crate | Purpose |
| --- | --- |
| `hypaxel` | Core writer and reader. Features: `chrono` (default), `serde` (default), `tokio` |
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

## Reading

```rust
use hypaxel::{Value, XlsxReader};

fn main() -> hypaxel::Result<()> {
    let mut reader = XlsxReader::open("orders.xlsx")?;
    println!("{:?}", reader.sheet_names().collect::<Vec<_>>());

    let mut rows = reader.worksheet("Orders")?;          // or worksheet_at(0)
    while let Some(row) = rows.next_row()? {
        // row.number() is the 1-based Excel row; rows without values are skipped.
        for cell in row.cells() {
            match cell.value() {
                Value::String(s) => println!("{}: {s}", hypaxel::cell_ref(row.number(), cell.column())),
                Value::Number(n) => println!("{n}"),
                Value::DateTime(d) => println!("{:?}", d.to_naive_datetime()),
                Value::Bool(b) => println!("{b}"),
                Value::Error(e) => println!("error {e}"),
                _ => {}
            }
        }
        let amount = row.get(3).as_f64();                   // by 1-based column
        let dense: Vec<Value> = row.values().collect();     // columns 1..=last, Empty for gaps
    }
    Ok(())
}
```

- **Memory:** rows are decompressed and parsed as a stream. What stays in memory is the shared
  string table (text that Excel stores once per workbook) plus the current row. Reading 1M rows
  peaks at 7 MB RSS for a file with inline strings and 57 MB for one whose 1M unique strings are
  in the shared table.
- **Values:** `String`, `Number`, `Bool`, `Error` (`#N/A`, ...), `Empty` and `DateTime` for numbers
  whose built-in or custom number format shows a date, time or duration (`ExcelDateTime`: serial,
  `kind()`, `to_naive_datetime()`, `to_naive_date()`, `to_naive_time()`, `to_duration()`, in the
  1900 and 1904 systems). Times are rounded to the millisecond. Formulas yield their cached
  result; a formula that was never calculated has no value.
- **Compatibility:** files from Excel, LibreOffice, openpyxl, rust_xlsxwriter and other
  producers: package relationships instead of fixed paths, Strict Open XML, prefixed namespaces,
  cells without references, rich text, inline strings and ISO 8601 dates.
- **Safety:** DTDs are rejected (no entity expansion), every XML token and row is bounded, ZIP
  entries must match their declared size and CRC, and `ReadOptions::max_shared_strings_bytes`
  (default 1 GiB) caps the shared string table. A sheet may have at most 4,096 tables holding
  64 MiB of names, and 16,384 column ranges. Legacy `.xls` files, encrypted workbooks, CSV
  and Word files are reported as `Error::InvalidFile`; damaged parts as `Error::Malformed`.
- **Several sheets at once:** `reader.with_source(File::open(path)?)` creates a second reader that
  shares the parsed workbook and string table, e.g. for another thread; `into_worksheet` gives
  rows that own their source.
- **Tables:** `reader.tables("Orders")?` (or `tables_at(0)`) returns the sheet's Excel tables as
  the same `Table` values that `add_table` accepts: name, range, column names, style and the
  display options, from top to bottom, then left to right. Tables are stored in their own parts
  and are read only when you ask, so reading values costs the same as before. `autofilter` tells
  whether the file has filter buttons, and a custom (non-built-in) table style reads as
  `TableStyle::None`. `totals_row` tells whether the last row of the range is a totals row; such
  a table can be read but not written.
- **Rows and columns:** each `Row` has `outline_level()`, `is_hidden()`, `is_collapsed()` and
  `height()` (`None` when the row stores no height). `rows.layout()?` gives the `SheetLayout`:
  `column_widths()` (runs of columns with a first column, last column, width and hidden flag,
  explicit and auto-fitted alike), `column_width(column)`, `is_column_hidden(column)`,
  `outline_summary_above()` and
  `row_outline_levels()`. These precede the rows in the file, so they are available before the
  first `next_row()`.
- **Rows without values** (a spacer row with a height, an empty hidden row) are skipped by
  default. Open the workbook with `ReadOptions::new().include_empty_rows(true)` to get every row
  the file stores; such a row has no cells (`row.is_empty()`) but its number and properties.

  ```rust
  let table = &reader.tables("Orders")?[0];               // table.name, table.columns, table.style, ...
  let mut rows = reader.worksheet("Orders")?;
  let width = rows.layout()?.column_width(2);             // None = default width
  while let Some(row) = rows.next_row()? {
      let (level, hidden) = (row.outline_level(), row.is_hidden());
  }
  ```

Not read (yet): formulas' text, styles beyond date detection, merged ranges, comments and
hyperlinks.

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

## Tables, row grouping and auto-fit

```rust
use hypaxel::{AutoFit, RowOptions, SheetOptions, Table, TableStyle};

let options = SheetOptions::new()
    .autofit_columns()            // or .autofit(AutoFit::new().sample_rows(5_000).max_width(60.0))
    .row_outline_levels(1)        // deepest grouping level used on this sheet (1–7)
    .outline_summary_above();     // group buttons on the row above each group
let mut sheet = writer.add_worksheet_with("Regions", &options)?;

sheet.add_row(("Region", "Revenue"))?;
sheet.add_row(("North", 300))?;
sheet.begin_row_with(&RowOptions::new().outline_level(1))?.add_row(("Store A", 120))?;
sheet.begin_row_with(&RowOptions::new().outline_level(1).hidden())?.add_row(("Store B", 180))?;

let last = sheet.row();
sheet.add_table(&Table::new(1, 1, last, 2).name("Regions").columns(["Region", "Revenue"]).style(TableStyle::Medium(9)))?;
```

- **Auto-fit:** column widths come before the cell data in the file, so the writer holds back the
  sheet's output until `sample_rows` rows (default 1,000, at most 8 MiB of XML) have been
  measured, then inserts the widths and streams as usual. Widths are estimated from the text,
  number format and font size; formulas and merged cells are not measured. Explicit widths win.
  The first bytes of a sheet are sent after the sample instead of immediately.
- **Row grouping:** declare the deepest level in `SheetOptions` (Excel reads it before the rows).
  `begin_row_with` / `begin_row` start a row that the next `add_row` (or cell writes) fills.
- **Tables:** with a header row, `columns` must equal the header texts as written. Table names are
  unique in the workbook. Tables cannot overlap each other, merged cells or the worksheet auto
  filter. The range may include rows written later.

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
| Worksheet | multiple sheets, column widths/styles/hidden, auto-fit columns, row height/style/hidden, row outline levels, frozen panes, gridlines, tab color, hidden sheets |
| Ranges | merged cells, auto filter, Excel tables (built-in styles) |
| Strings | inline (default) or shared string table. Full XML escaping, control characters as `_xHHHH_` |
| Rows | tuples (up to 16 elements), arrays, `Vec`, slices, serde records, async streams |
| I/O | any `io::Write` or tokio `AsyncWrite`. No `Seek` needed. ZIP64 |

## Performance

The same 10-column dataset as the .NET benchmark: int, 2× string, 2× float, datetime, bool, i64,
unique string, float. Output goes to a `Vec<u8>`. macOS 26.6, Apple M1 Max, Rust 1.100,
rust_xlsxwriter 0.102, `--release`.

| Library | 100k rows (Criterion) | 1M rows | File (1M) |
| --- | ---: | ---: | ---: |
| **hypaxel** (tuples) | **67 ms** | **~0.69 s** | 50 MB |
| **hypaxel** (serde records) | 70 ms | ~0.70 s | 50 MB |
| hypaxel (shared strings) | 77 ms | ~0.87 s | 53 MB |
| hypaxel (deflate level 6) | 185 ms | ~1.87 s | 31 MB |
| rust_xlsxwriter 0.102 (constant memory) | 541 ms | ~5.3 s | 57 MB |
| rust_xlsxwriter 0.102 | 551 ms | ~6.1 s | 59 MB |

About two thirds of the time is deflate (zlib-rs, level 1): without compression 1M rows take
0.25 s. The rest is generating about 330 MB of XML at roughly 25 ns per cell.

```bash
cargo bench -p hypaxel                          # Criterion, 10k and 100k rows
HYPAXEL_QUICK=1000000 cargo bench -p hypaxel    # stopwatch run, 1M rows
```

**Peak memory, 1M rows.** Each case writes to a file in its own process, generating every row as
it is written, as a service streaming a database cursor would. Same machine, peak RSS from
`/usr/bin/time -l`, median of three runs (all three within 0.1 MB, or 0.6 MB with shared strings).
The first row only generates the rows, without writing them; that memory is included in every
other figure.

| Case | Peak RSS (1M) |
| --- | ---: |
| rows only, nothing written | 6.3 MB |
| **hypaxel** (tuples) | **7.1 MB** |
| **hypaxel** (serde records) | **7.2 MB** |
| hypaxel (shared strings) | 127 MB |
| hypaxel (deflate level 6) | 7.2 MB |
| rust_xlsxwriter 0.102 (constant memory) | 7.8 MB |
| rust_xlsxwriter 0.102 | 1,504 MB |

Hypaxel's memory does not grow with the row count. Shared strings grow with the number of distinct
strings, here one per row. rust_xlsxwriter's constant-memory mode stays low by writing the
worksheet to a temporary file and zipping it on `save`; its default mode keeps the worksheet in
memory.

```bash
cargo run --release -p hypaxel --example write_bench -- hypaxel 1000000 out.xlsx   # or baseline, hypaxel-serde, hypaxel-shared, hypaxel-level6, rust_xlsxwriter, rust_xlsxwriter-constant-memory
```

### Reading

The same dataset (1M rows × 10 columns, written by hypaxel) read cell by cell, with a checksum
over all values that every reader must match. Same machine, calamine 0.38, `--release`, each run in
its own process (best of three).

| Reader | Inline strings (49 MB) | Peak RSS | Shared strings (52 MB) | Peak RSS |
| --- | ---: | ---: | ---: | ---: |
| **hypaxel** `XlsxReader` | **0.75 s** | **7 MB** | **0.72 s** | **57 MB** |
| calamine `worksheet_cells_reader` | 2.14 s | 7 MB | 2.02 s | 63 MB |
| calamine `worksheet_range` | 2.35 s | 793 MB | 2.23 s | 849 MB |

About a quarter of the time is inflate. Cells of the shapes nearly every producer writes
(`<c r="B2" s="1"><v>42</v></c>`, `<c t="inlineStr"><is><t>…</t></is></c>`) and plain shared string
items are matched in a single pass over the buffer; anything else goes through the general pull
parser. Row buffers are reused, so reading allocates nothing per cell.

```bash
cargo run --release -p hypaxel --example read_bench -- generate data.xlsx 1000000 inline
cargo run --release -p hypaxel --example read_bench -- hypaxel data.xlsx     # or calamine, calamine-stream
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
- Column definitions, frozen panes and the row outline depth go in `SheetOptions` when you create
  the sheet, because they come before the cell data in the file. Merges, filters and tables can be
  added at any time before the sheet completes.
- Formulas are stored without cached results. Excel recalculates them when the file opens.

## Testing

```bash
cargo test --workspace --all-features
```

The writer tests read files back with calamine and inspect the raw parts with the `zip` crate.
The reader tests round-trip every value type, read files from rust_xlsxwriter and handcrafted
packages (Strict Open XML, unusual XML), check error messages, and feed thousands of randomly
damaged files to make sure they fail cleanly. The axum tests drive real routers, including error
propagation and client disconnects.
