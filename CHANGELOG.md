# Changelog

All notable changes to Hypaxel are recorded here. Versions apply to every package (Rust crates,
Python, Node.js, .NET, Java, Go and PHP).

## 0.4.0 (unreleased)

### Added

- **Streaming XLSX reader for Node.js and browsers.** The `hypaxel` npm package reads workbooks on
  the Rust core reader, natively in Node.js and on WebAssembly in browsers, with the same API.
  `openWorkbook` (path, `file:` URL, bytes, `Blob`/`File`, `Response`, web or Node.js stream) and
  `openWorkbookSync` return a `Workbook` with `sheets` (name, visibility, kind), `sheetNames`,
  `date1904`, `rows(sheet, { skipEmpty })` and `records(sheet, { headerRow })`; helpers
  `iterRows` / `readRows` and `iterRecords` / `readRecords`. Rows are arrays and iterate with
  `for...of` or `for await...of`; in Node.js the async form parses on the libuv thread pool so the
  event loop stays free. Values are `string`, `number`, `boolean`, `Date` (dates, date-times and
  times of day, in local time or UTC like the writer's `dates` option), elapsed milliseconds for
  `[h]:mm` durations, error strings and `null`. Rows cross into JavaScript in binary batches, and
  repeated shared strings are decoded once. Same row and record semantics as Python. Read
  benchmark against ExcelJS's streaming reader and SheetJS (`node/benchmarks/read_bench.mjs`).
- **Readers return tables, row properties and column widths (.NET, Rust, Python, Node.js and
  browsers, Go, Java)** ([#1](https://github.com/vensas/hypaxel/issues/1)). A file written with
  Hypaxel can now be checked with Hypaxel alone. Same semantics everywhere:
  - Tables are returned as the writer's own table type, so a table that was read can be compared
    with, or passed to, `add_table`: name, range, column names, style, header row, filter buttons,
    banded rows and columns, first and last column highlight. Table parts are read only on
    request; reading values does not touch them. They are returned from top to bottom, then left
    to right, whatever order the file lists them in (Excel lists them in reverse). A custom
    (non-built-in) style reads as "no style", and a table written without a header row reads
    back without filter buttons.
  - The table type gains a totals-row flag (Rust `Table::totals_row`, .NET
    `ExcelTable.HasTotalsRow`, Go `Table.TotalsRow`, Java `ExcelTable.totalsRow()`, Python
    `totals_row`, Node.js `totalsRow`), set by the reader when the last row of the range is a
    totals row. Totals rows cannot be written yet: `add_table` rejects a table that has the flag
    set, because Excel repairs a totals row whose columns do not declare their functions.
  - Each row has its outline level (0–7), hidden and collapsed flags and its height (absent when
    the row stores none).
  - Rows that the file stores without any value (a spacer row with a height, an empty hidden
    row) are still skipped by default. A new reader option returns them with their number and
    properties and no cells: Rust `ReadOptions::include_empty_rows`, .NET
    `XlsxReaderOptions.IncludeEmptyRows`, Go `WithEmptyRows`, Java
    `ReadOptions.Builder.includeEmptyRows`. Python (`include_empty_rows=`) and Node.js
    (`includeEmptyRows`) already return rows without values as empty lists; there the option
    adds their properties and returns such rows after the last value too, and `skip_empty` /
    `skipEmpty` still drops them.
  - Each worksheet has its column widths (runs of columns with their hidden flag, explicit and
    auto-fitted alike), lookups by column for the width and the hidden flag,
    `outline_summary_above` and the declared row outline levels. They precede the rows in the
    file and are available before the first row is read.
  - Rust: `XlsxReader::tables(name)` / `tables_at(index)` → `Vec<Table>`; `Row::outline_level`,
    `is_hidden`, `is_collapsed`, `height`; `Rows::layout()` → `SheetLayout` (`column_widths`,
    `column_width`, `is_column_hidden`, `outline_summary_above`, `row_outline_levels`) with
    `ColumnWidth`; `TableStyle::name()` is now public.
  - Python: `Reader.tables(sheet)` → dicts with `add_table`'s keys; on the row iterator
    `row_number`, `outline_level`, `hidden`, `collapsed`, `height`, `column_widths`,
    `column_width(col)`, `column_hidden(col)`, `outline_summary_above`, `row_outline_levels`.
  - Node.js and browsers: `Workbook.tables(sheet)` → `ReadTable` (`firstRow`, `firstColumn`,
    `lastRow`, `lastColumn`, `options`); on `SheetRows` `rowNumber`, `outlineLevel`, `hidden`,
    `collapsed`, `height`, `layout()` / `layoutAsync()` → `SheetLayout` (`columnWidths`,
    `columnWidth`, `columnHidden`, `outlineSummaryAbove`, `rowOutlineLevels`) with
    `ColumnWidth`. The layout and row properties travel in the row batches, identically on both
    engines.
  - .NET: `XlsxReader.GetTables(name or index)` → `IReadOnlyList<ExcelTable>`;
    `XlsxRow.OutlineLevel`, `Hidden`, `Collapsed`, `Height`; `WorksheetReader.ColumnWidths`,
    `GetColumnWidth`, `IsColumnHidden`, `OutlineSummaryAbove`, `RowOutlineLevels`;
    `ColumnWidth`.
  - Go: `Reader.Tables(name)` / `TablesAt(index)` → `[]Table`; `Row.OutlineLevel`, `Hidden`,
    `Collapsed`, `Height`; `Rows.Layout()` → `*SheetLayout` (`ColumnWidths`,
    `ColumnWidth(column)`, `ColumnHidden(column)`, `OutlineSummaryAbove`, `RowOutlineLevels`)
    with `ColumnWidth`.
  - Java: `XlsxReader.tables(name or index)` → `List<ExcelTable>` (which gains `equals`,
    `hashCode` and `toString`); `Row.outlineLevel`, `hidden`, `collapsed`, `height`;
    `SheetReader.columnWidths`, `columnWidth`, `columnHidden`, `outlineSummaryAbove`,
    `rowOutlineLevels`; `ColumnWidth`.
  - Checked against the same workbook saved by Excel for Mac and by LibreOffice Calc, and against
    tables with a totals row saved by Excel and downloaded from Google Sheets, and a sheet
    exported by Apple Numbers (`testdata/reader/`), in every implementation.
  - Go: `TableStyleMedium(2)` is now the zero `TableStyle`, so a table written with it compares
    equal to the table read back.
  - Limits for untrusted input: at most 4,096 tables per sheet, 16,384 columns per table, 64 MiB
    of table and column names per sheet, 16,384 column ranges per sheet and 65,536
    relationships per part (links to external targets do not count). A table part that a
    sheet references several times is read once.
  - Integer attributes (row numbers, outline levels, column ranges) are read as plain digits in
    every implementation; the Rust core no longer accepts a leading `+`.
  - When the rows of a worksheet end, the readers read the rest of its ZIP entry, so a damaged
    entry is reported even when the damage lies after the rows or the sheet has none (Rust, .NET,
    Java, and through the core Python and Node.js; Go already did). A failure while reading the
    layout is reported again by every later layout call and by the next row call, in every
    implementation; in Node.js this includes a sheet that cannot be opened, which used to end
    silently after the first error.
  - Table names are decoded like column names, so a name that looks like an Excel escape (such
    as `T_x0041_`, which Excel stores as `T_x005f_x0041_`) reads back as written.
  - .NET: `ExcelTable` compares `Columns` by their names, so a table read back equals the one
    that was written.
  - Asking for the layout of a worksheet reader that was closed before the layout was read is an
    error instead of an empty result (.NET `ObjectDisposedException`, Go `ErrRowsClosed`, Java
    `INVALID_STATE`, Node.js `XlsxError`).
- Benchmark results as JSON: every table in an implementation's Performance section is published
  in `<implementation>/benchmarks/results/<id>.json`, and the root README's "At a glance" figures
  in `benchmarks/highlights.json` (format in `benchmarks/README.md`). Memory metrics name what they
  measure (`allocated`, `peakRss`, `peakHeap`, `wasmMemory`). `scripts/check_benchmarks.py`, run in
  CI, checks the files against the README tables.
- Rust: peak memory for writing 1M rows, against rust_xlsxwriter, with a `write_bench` example
  that runs each case in its own process.
- README "At a glance": memory figures next to the write and read timings.
- `benchmarks/highlights.json`: each entry links to the results files behind it with `suite`, and
  with `comparisonSuite` and `memorySuite` when the comparison or memory figure comes from another
  table. The check fails on a link to a missing file or to another language's file.
- .NET: peak RSS for writing 100k and 1M rows, against SpreadCheetah and ClosedXML, with a
  `--write-rss` mode that runs each case in its own process and writes to a file.

### Changed

- Benchmarks: every published figure was re-measured, one implementation after another, on the
  same Apple M1 Max (macOS 26.6) on an otherwise idle machine. Rust and Python writing were
  previously measured on a Linux Xeon, and some figures came from runs on a busy machine. Python
  also gains 1M-row write and read tables. The comparison ratios in the READMEs follow the new
  figures; notably rust_xlsxwriter is ~8–9x slower than Hypaxel on this machine (was ~11–14x on
  the Xeon).
- .NET: the `--read-quick` read benchmark also prints the CPU time of the best run, summed over all
  threads.
- .NET README: the background decompression table lists time and CPU time in separate columns;
  the figures for several concurrent readers moved to the text.
- README "At a glance" and highlights: comparisons from another scale than the timing say so (.NET
  against ClosedXML and PHP against PhpSpreadsheet are from 100k rows), the browser comparison
  uses the 300k-row figures only (~14x faster than SheetJS), and paired read memory figures name both files ("peak 49 MB inline / 141 MB shared").
- `dotnet-read-background.json` names what it compares with, and the check requires every suite to
  list its compared versions.
- PHP: `vensas/hypaxel` is prepared for Packagist. A workflow publishes `php/` to a generated,
  read-only repository with `git subtree split`, fast-forwarding its `main` and creating release
  tags without ever moving a published one (`docs/publishing-php.md`). The Composer archive leaves
  out tests, benchmarks, examples and tool configuration.
- Releases: one `Release` workflow publishes a release tag to Packagist, to nuget.org, crates.io,
  npm and PyPI through Trusted Publishing, to Maven Central, and to the Go module proxy by creating the `go/vX.Y.Z` tag that Go needs for
  a module in a subdirectory. It first checks that every manifest declares the tag's version
  (`docs/releasing.md`). A manual dry run builds, tests and smoke-tests everything without
  publishing. The PHP split workflow now only keeps the split repository's `main` in step.
- Rust: `hypaxel` and `hypaxel-axum` can be published to crates.io (they were marked
  `publish = false`). The README lists the `cargo add` commands, and its links work on crates.io.
- Node.js: the npm package is released with the native addon for all five targets (Linux x64 and
  arm64 linked against glibc 2.17, macOS x64 and arm64, Windows x64) and the WebAssembly build.
  Each release installs the packed tarball on every target with Node.js 22 before publishing it.
  The README's links work on npmjs.com.
- Python: releases publish wheels for CPython 3.10–3.14 on Linux x86_64 and aarch64 (manylinux and
  musllinux), macOS x86_64 and arm64 and Windows x64, plus an sdist that builds from source. Each
  release installs the wheels with Python 3.10 and 3.14 on every platform before uploading them.
  The README's links work on PyPI.
- Java: a `release` Maven profile builds the sources and javadoc jars, signs every artifact and
  publishes `de.vensas:hypaxel` to Maven Central through the Central Portal. The README's
  coordinates name the current version.
- .NET: both NuGet packages carry the README, a symbol package (`.snupkg`) and a project URL, and CI
  builds are deterministic with Source Link. `Hypaxel.AspNetCore` gains its package README. The
  README lists the `dotnet add package` commands, and its links work on nuget.org.

## 0.3.0 (2026-11-20)

### Added

- **Streaming XLSX reader (.NET, Rust, Python, Go, Java).** Rows are read one at a time, with
  memory bounded by the shared string table plus one row. Every implementation has its own
  dependency-free parser and the same semantics.
  - Rust: `XlsxReader::open` / `new` / `with_options`, `sheets()` (name, visibility, kind),
    `worksheet(name)` / `worksheet_at(index)` → `Rows::next_row()` → `Row` (`number`, `cells`,
    `get`, `values`). `Value` covers strings, numbers, booleans, errors and `ExcelDateTime` for
    date, time and duration formats in both date systems, with chrono conversions.
    `with_source` shares the parsed workbook across handles; `ReadOptions` caps the shared string
    table.
  - Python: `open_workbook` / `Reader` (paths, bytes, binary file objects), `iter_rows` /
    `read_rows`, `iter_records` / `read_records` and `read_dataframe` (pandas or polars). Values
    are `str`, `int`, `float`, `bool`, `datetime`, `time`, `timedelta`, error strings and `None`.
  - .NET: `XlsxReader.Open` / `new XlsxReader(stream)`, `OpenWorksheet(name or index)` →
    `WorksheetReader.Read()` / `Row` with allocation-free `TryGet…` accessors and `ExcelValue`
    structs; `XlsxReadException` with `Kind` and `Part`.
  - Go: `OpenReader` / `NewReader(io.ReaderAt, size)`, `Rows(name)` / `RowsAt(index)` with
    `Next`/`Row`/`Err` or `Rows.All()` (`iter.Seq2`); `ReadError` with sentinel errors.
  - Java: `XlsxReader.open(Path | SeekableByteChannel | byte[] | InputStream)`,
    `worksheet(name or index)` → `SheetReader.nextRow()`, iterator or `stream()`; `XlsxException`
    gains kinds and the failing part.
  - Accepts Strict Open XML, prefixed namespaces, cells without references, rich and inline text
    and ISO 8601 dates. Rejects DTDs, oversized tokens and ZIP entries that do not match their
    declared size or CRC. Names legacy `.xls`, encrypted and non-spreadsheet files in the error.
- **Browser support for the Node.js package (WebAssembly).** The `hypaxel` npm package now runs in
  browsers and other runtimes without native modules on the Rust core compiled to WebAssembly
  (171 KB gzipped), with the same API as in Node.js. Bundlers pick the build through the `browser` export
  condition; `hypaxel/wasm` selects it explicitly. Adds `init()` / `initSync()` to load the
  module, `toBlob()` for downloads, and a pull-based `webStream()`. In Node.js `init()` is a no-op
  and nothing else changes. Both engines share one Rust crate (`node/engine`) and produce
  byte-identical files. Browser benchmarks against SheetJS and ExcelJS in headless Chrome.
- Read benchmarks against each ecosystem's readers: calamine (Rust); openpyxl, python-calamine,
  pandas and fastexcel (Python); Sylvan.Data.Excel, ExcelDataReader and MiniExcel (.NET);
  excelize, xlsxreader and tealeg/xlsx (Go); FastExcel and Apache POI (Java).

### Fixed

- Rust, .NET: with the 1904 date system, 9999-12-31 is written as a date instead of text.

## 0.2.0 (2026-11-19)

### Added

- **Excel tables:** `Sheet::add_table` (Rust), `Worksheet.add_table` (Python), `addTable` (Node.js,
  Java, PHP), `Worksheet.AddTable` (.NET) and `Sheet.AddTable` (Go) turn a range into a named,
  styled table with filter buttons and banded rows. Built-in Light/Medium/Dark styles, optional header row, workbook-unique names,
  validation of names, column names and overlaps with other tables, merged cells and the worksheet
  auto filter.
- **Row outline levels (grouping):** `RowOptions` with height, style, outline level 0–7, hidden and
  collapsed; the sheet declares its deepest level up front (`row_outline_levels` /
  `RowOutlineLevels`), and `outline_summary_above` places group buttons above each group.
- **Auto-fit column widths:** `SheetOptions::autofit_columns` / `autofit(AutoFit)` and the
  equivalents in every binding. The sheet's output is held back for a sample (default 1,000 rows,
  at most 8 MiB of XML), widths are estimated from text, number formats and font sizes, and
  streaming resumes. Memory stays bounded; explicit widths take precedence.

### Changed

- A row started explicitly with `begin_row*` / `BeginRow` is filled by the next whole-row write
  (`add_row`, `write_row`, records, objects) instead of a new row being started after it. Rows that
  already contain cells are unaffected.
- Python: `write_row` accepts `height`, `outline_level`, `hidden` and `collapsed`; `begin_row`
  accepts `outline_level`, `hidden` and `collapsed`.
- Node.js: the native `beginRow` takes an options object.
- .NET: `BeginRow` validates all arguments before ending the open row, and worksheet options are
  validated before the worksheet's part is opened. `sheet.BeginRow(null)` with a literal `null` no
  longer compiles because it is ambiguous with the new `BeginRow(RowOptions)` overload.

## 0.1.0 (2026-11-18)

- First version: forward-only, constant-memory XLSX writer in Rust and .NET, with Python and
  Node.js packages built on the Rust core, and native Java, Go and PHP implementations.
