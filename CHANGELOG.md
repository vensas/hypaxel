# Changelog

All notable changes to Hypaxel are recorded here. Versions apply to every package (Rust crates,
Python, Node.js, .NET, Java, Go and PHP).

## 0.3.0 (unreleased)

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
- Benchmark results as JSON: every table in an implementation's Performance section is published
  in `<implementation>/benchmarks/results/<id>.json`, and the root README's "At a glance" figures
  in `benchmarks/highlights.json` (format in `benchmarks/README.md`). Memory metrics name what they
  measure (`allocated`, `peakRss`, `peakHeap`, `wasmMemory`). `scripts/check_benchmarks.py`, run in
  CI, checks the files against the README tables.
- Rust: peak memory for writing 1M rows, against rust_xlsxwriter, with a `write_bench` example
  that runs each case in its own process.
- README "At a glance": memory figures next to the write and read timings.

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
