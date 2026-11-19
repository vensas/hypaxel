# Changelog

All notable changes to Hypaxel are recorded here. Versions apply to every package (Rust crates,
Python, Node.js, .NET, Java, Go and PHP).

## 0.3.0 (unreleased)

### Added

- **Streaming XLSX reader (Rust, Python).** Rows are read one at a time, with memory bounded by
  the shared string table plus one row.
  - Rust: `XlsxReader::open` / `new` / `with_options`, `sheets()` (name, visibility, kind),
    `worksheet(name)` / `worksheet_at(index)` → `Rows::next_row()` → `Row` (`number`, `cells`,
    `get`, `values`). `Value` covers strings, numbers, booleans, errors and `ExcelDateTime` for
    date, time and duration formats in both date systems, with chrono conversions.
    `with_source` shares the parsed workbook across handles; `ReadOptions` caps the shared string
    table.
  - Python: `open_workbook` / `Reader` (paths, bytes, binary file objects), `iter_rows` /
    `read_rows`, `iter_records` / `read_records` and `read_dataframe` (pandas or polars). Values
    are `str`, `int`, `float`, `bool`, `datetime`, `time`, `timedelta`, error strings and `None`.
  - Accepts Strict Open XML, prefixed namespaces, cells without references, rich and inline text
    and ISO 8601 dates. Rejects DTDs, oversized tokens and ZIP entries that do not match their
    declared size or CRC. Names legacy `.xls`, encrypted and non-spreadsheet files in the error.
- Read benchmarks: `rust/hypaxel/examples/read_bench.rs` (against calamine) and
  `python/benchmarks/read_bench.py` (against openpyxl, python-calamine, pandas and fastexcel).

### Fixed

- Rust, .NET: with the 1904 date system, 9999-12-31 is written as a date instead of text.

## 0.2.0 (unreleased)

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

## 0.1.0

- First version: forward-only, constant-memory XLSX writer in Rust and .NET, with Python and
  Node.js packages built on the Rust core, and native Java, Go and PHP implementations.
