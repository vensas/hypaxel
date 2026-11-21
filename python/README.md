# Hypaxel for Python

**High-performance, constant-memory XLSX (Excel) writer and streaming reader for Python, powered by Rust.**

Hypaxel writes `.xlsx` files as a forward-only stream. Rows are converted, serialized and compressed
in native code as you write them, so memory stays flat whether you export 10 rows or 10 million.
It can stream a download to the browser while your query is still running.

- **Fast:** writes 100k rows × 10 columns in 0.07 s, about **50x faster than XlsxWriter** and
  **90x faster than openpyxl**. DataFrames are about **30x faster** than `pandas.to_excel` and
  `polars.write_excel`, with 9–34x less memory.
- **Streaming:** sync and async generators that plug into FastAPI/Starlette, Django and Flask
  streaming responses.
- **Data you already have:** tuples and lists, dicts, dataclasses, named tuples, pydantic models,
  pandas and polars DataFrames, sync and async iterables (e.g. async database cursors).
- **Real Excel types:** `date`, `datetime` (timezone-aware ones are written as local time), `time`,
  `timedelta`, `Decimal`, numpy scalars, `bool`, and huge ints. NaN, None, NaT and pd.NA become
  empty cells.
- **Styles and layout:** fonts, fills, borders, alignment, number formats, merged cells, frozen
  panes, auto filters, Excel tables, row grouping, auto-fit columns, formulas, hidden sheets, tab colors.
- **Valid output:** checked with the Office Open XML schema validator and read back with openpyxl.
- **Fast reading:** `iter_rows` reads 100k rows × 10 columns in 0.13 s, about **26x faster than
  openpyxl** and 3.5x faster than python-calamine, in bounded memory. `read_dataframe` builds a
  pandas DataFrame 4–4.5x faster than `pandas.read_excel(engine="calamine")`, and a polars one
  faster than fastexcel. See [Reading](#reading).

Hypaxel is built on the [`hypaxel` Rust crate](../rust/README.md). The [.NET version](../dotnet/README.md) uses the same design.

## Install

```bash
pip install hypaxel                 # not published yet: build from source, see "Development"
pip install "hypaxel[pandas]"       # extras: pandas, polars, starlette
```

## Quick start

```python
import datetime as dt
import hypaxel
from hypaxel import Formula, Styled

with hypaxel.Writer("report.xlsx") as wb:
    header = wb.add_style(bold=True, font_color="#FFFFFF", fill="#305496", align="center")
    money = wb.add_style(num_format="#,##0.00")

    ws = wb.add_worksheet("Sales", freeze_rows=1, column_widths=[20, 12, 14])
    ws.write_row(["Product", "Date", "Revenue"], style=header)
    ws.write_rows([
        ("Widget", dt.date(2024, 5, 1), Styled(1234.5, money)),
        ("Gadget", dt.date(2024, 5, 2), Styled(99.99, money)),
    ])
    ws.write_row(["Total", None, Styled(Formula("SUM(C2:C3)"), money)])
```

One-liners:

```python
hypaxel.to_xlsx(df, "orders.xlsx")                       # pandas or polars DataFrame
data: bytes = hypaxel.to_xlsx(orders)                    # list of dicts / dataclasses / models
hypaxel.to_xlsx(df, "out.xlsx", column_formats={"amount": "#,##0.00"})
```

## Reading

```python
import hypaxel

for row in hypaxel.iter_rows("orders.xlsx", "Orders"):      # sheet name or 0-based index
    print(row)                                               # ['Widget', 3, 9.99, datetime(...)]

records = hypaxel.read_records("orders.xlsx")                # [{"Product": "Widget", ...}, ...]
df = hypaxel.read_dataframe("orders.xlsx")                   # pandas; library="polars" for polars

with hypaxel.open_workbook(upload_bytes) as wb:             # path, bytes or binary file object
    print(wb.sheet_names, wb.sheets, wb.date_1904)
    for row in wb.rows("Orders", skip_empty=True):
        ...
```

- **Rows** are lists: `row[i]` is column `i + 1`, and a row ends at its last non-empty cell.
  Missing rows come back as `[]`, so `enumerate(rows, 1)` gives Excel row numbers (pass
  `skip_empty=True` to drop them).
- **Values** are `str`, `int` (integral numbers, as openpyxl returns them), `float`, `bool`,
  `datetime.datetime` for dates, `datetime.time` for times of day, `datetime.timedelta` for
  `[h]:mm` durations, error strings such as `"#N/A"`, and `None`. Formulas yield their cached
  result. Dates are recognised from the cell's number format, in both date systems; times are
  rounded to the millisecond.
- **Records:** `iter_records` / `read_records` use a header row (`header_row=1`). Empty header
  cells become `column_<n>`, repeated names get `_2`, `_3`, ... and rows without values are
  skipped.
- **DataFrames:** `read_dataframe(source, sheet=0, *, header=True, library="pandas")`. polars needs
  one type per column, so a column mixing text and dates arrives as text.
- **Memory:** rows are streamed. A path is read straight from disk; bytes and file objects are held
  in memory once. Beyond that, memory is bounded by the workbook's shared string table and one
  row. `max_shared_strings_bytes=` (default 1 GiB) caps the table for untrusted uploads.
- **Errors:** `FileNotFoundError` for missing files and `XlsxError` for everything else. Messages
  name the problem, e.g. a legacy `.xls` or encrypted workbook, a sheet that does not exist (with
  the sheets that do), or a damaged part.

## Records and DataFrames

```python
@dataclass
class Order:
    id: int
    customer: str
    amount: float
    placed: dt.date

wb = hypaxel.Writer("orders.xlsx")
hypaxel.write_records(wb, "Orders", orders, column_formats={"amount": "#,##0.00", "placed": "yyyy-mm-dd"})
hypaxel.write_dataframe(wb, "Summary", summary_df, index=True)
wb.finish()
```

The first record defines the columns. `write_records` accepts dicts, dataclasses, named tuples,
pydantic models, objects with `__dict__` or `__slots__`, and tuples. Every table gets a bold,
frozen header row, an auto filter, and column widths estimated from the headers.

DataFrames are converted column-wise in chunks, so memory stays bounded even for very large
frames.

## Streaming web responses

**FastAPI / Starlette**

```python
from hypaxel.starlette import XlsxResponse

@app.get("/orders.xlsx")
def orders():                                  # sync generator: runs in Starlette's thread pool
    return XlsxResponse(hypaxel.stream_records(load_orders()), filename="orders.xlsx")

@app.get("/live.xlsx")
async def live():                              # async source, e.g. an async DB cursor
    rows = await conn.stream("SELECT id, customer, amount FROM orders")
    return XlsxResponse(hypaxel.astream_rows(rows, header=["Id", "Customer", "Amount"]), filename="live.xlsx")
```

**Django**

```python
from django.http import StreamingHttpResponse

def export(request):
    response = StreamingHttpResponse(
        hypaxel.stream_records(Order.objects.values("id", "customer", "amount").iterator()),
        content_type=hypaxel.CONTENT_TYPE,
    )
    response["Content-Disposition"] = 'attachment; filename="orders.xlsx"'
    return response
```

**Flask:** `Response(hypaxel.stream_rows(rows, header=[...]), mimetype=hypaxel.CONTENT_TYPE)`

| Generator | Source |
| --- | --- |
| `stream_rows(rows, header=...)` | iterable of rows |
| `stream_records(records)` | iterable of dicts, dataclasses, models, ... |
| `stream_dataframe(df)` | pandas or polars DataFrame |
| `astream_rows(rows, header=...)` | async or sync iterable of rows |
| `astream_records(records)` | async or sync iterable of records |

**Custom layouts:** for full control (several sheets, styles, merges), use an in-memory writer and
`drain()`:

```python
def export():
    wb = hypaxel.Writer()                      # dest=None → in memory
    ws = wb.add_worksheet("Data")
    for batch in batches():
        ws.write_rows(batch)
        if chunk := wb.drain():                # roughly every 64 KiB of compressed output
            yield chunk
    yield wb.finish()
```

## API overview

| | |
| --- | --- |
| `Writer(dest=None, *, compression_level=1, shared_strings=False, flush_threshold=65536, date_1904=False)` | `dest`: path, binary file object, or `None` (in memory) |
| `Writer.add_style(bold=, italic=, underline=, strikethrough=, font_name=, font_size=, font_color=, fill=, border=, border_color=, border_bottom=, align=, valign=, wrap=, indent=, rotation=, num_format=)` | returns a `Style`. Identical styles are shared |
| `Writer.add_worksheet(name, *, freeze_rows=, freeze_columns=, column_widths=, hidden=, show_grid_lines=, tab_color=, autofit=, autofit_sample_rows=, autofit_max_width=, row_outline_levels=, outline_summary_above=)` | returns a `Worksheet`. The previous sheet is completed |
| `Writer.drain()` / `Writer.finish()` | in-memory chunks / complete the workbook |
| `Worksheet.write_row(values, style=None, *, height=, outline_level=, hidden=, collapsed=)`, `write_rows(rows)`, `write_columns(columns)`, `write_dicts(records, keys)` | bulk writers, with the loops running in Rust |
| `Worksheet.write(value)`, `skip_cells(n)`, `end_row()`, `begin_row(height=, style=, *, outline_level=, hidden=, collapsed=)`, `skip_rows(n)` | cell-by-cell control. A begun row is filled by the next write |
| `Worksheet.merge_cells(r1, c1, r2, c2)`, `autofilter(r1, c1, r2, c2)` | ranges (1-based, inclusive) |
| `Worksheet.add_table(r1, c1, r2, c2, *, columns=, name=, style="TableStyleMedium2", header_row=, autofilter=, row_stripes=, column_stripes=, first_column=, last_column=)` | Excel table; `columns` must equal the header texts |
| `Formula("SUM(A1:A9)")`, `Styled(value, style)` | special cell values |
| `open_workbook(source, *, max_shared_strings_bytes=1 GiB)` → `Reader` | `source`: path, bytes or binary file object; a context manager |
| `Reader.sheet_names`, `Reader.sheets`, `Reader.date_1904`, `Reader.rows(sheet=0, *, skip_empty=False)`, `Reader.close()` | `rows` iterates lists; several iterators can be open at once |
| `iter_rows` / `read_rows`, `iter_records` / `read_records`, `read_dataframe` | one-call readers; see [Reading](#reading) |

### Tables, row grouping and auto-fit

```python
ws = wb.add_worksheet("Regions", autofit=True, row_outline_levels=1, outline_summary_above=True)
ws.write_row(["Region", "Revenue"])
ws.write_row(["North", 300])
ws.write_row(["Store A", 120], outline_level=1)
ws.write_row(["Store B", 180], outline_level=1, hidden=True)
ws.add_table(1, 1, ws.row, 2, columns=["Region", "Revenue"], name="Regions", style="TableStyleMedium9")
```

- `autofit=True` holds back the sheet's output until `autofit_sample_rows` rows (default 1,000,
  at most 8 MiB of XML) have been measured, then writes the widths and streams as usual. Explicit
  `column_widths` win; formulas and merged cells are not measured.
- `row_outline_levels` declares the deepest grouping level up front, because Excel reads it before
  the rows.
- Table names are unique in the workbook; tables cannot overlap each other, merged cells or an
  `autofilter` range.

## Performance

The same 10-column dataset as the .NET and Rust benchmarks (int, 2× str, 2× float, datetime,
bool, int, unique str, float). Python 3.13, macOS 26.6, Apple M1 Max, XlsxWriter 3.3.3, openpyxl
3.1.5, pandas 3.1.1, polars 2.1.0. Peak Python memory is measured with `tracemalloc` in a separate,
untimed run; the workbook is written to memory, so the figures include the output. Run it yourself
with `python benchmarks/bench.py 100000`.

| 100,000 rows | seconds | rows/s | peak Python memory |
| --- | ---: | ---: | ---: |
| **hypaxel** `write_rows` | **0.07** | **~1,380,000** | 5 MB (the output itself) |
| **hypaxel** `stream_rows` | **0.08** | **~1,310,000** | 11 MB |
| xlsxwriter (constant_memory) | 3.74 | 26,700 | 7 MB |
| openpyxl (write_only) | 6.35 | 15,800 | 7 MB |
| **hypaxel** pandas DataFrame* | **0.20** | 509,000 | 16 MB |
| pandas.to_excel (xlsxwriter)* | 6.82 | 14,700 | 147 MB |
| **hypaxel** polars DataFrame* | **0.17** | 581,000 | 8 MB |
| polars.write_excel* | 4.86 | 20,600 | 268 MB |

\* includes building the DataFrame from the row list.

| 1,000,000 rows | seconds | rows/s | peak Python memory |
| --- | ---: | ---: | ---: |
| **hypaxel** `write_rows` | **0.74** | **~1,360,000** | 53 MB (the output itself) |
| **hypaxel** `stream_rows` | **0.73** | **~1,370,000** | 106 MB |
| xlsxwriter (constant_memory) | 37.94 | 26,400 | 60 MB |
| openpyxl (write_only) | 63.97 | 15,600 | 66 MB |
| **hypaxel** pandas DataFrame* | **2.03** | 493,000 | 163 MB |
| pandas.to_excel (xlsxwriter)* | 69.87 | 14,300 | 1,446 MB |
| **hypaxel** polars DataFrame* | **2.22** | 450,000 | 53 MB |
| polars.write_excel* | 50.77 | 19,700 | 2,550 MB |

Hypaxel's own memory stays flat: what grows is the output buffer (53 MB at 1M rows), which a
streaming response sends instead of keeping.

### Reading

The same 10 columns plus a header row, saved with a shared string table as Excel does. Every reader
runs in its own process; peak RSS covers the whole process (Python, modules and native memory);
imports are not timed. All readers return the same non-empty cells. Python 3.13, macOS 26.6,
Apple M1 Max, openpyxl 3.1.5, python-calamine 0.9.1, fastexcel 0.21.0, pandas 3.1.1, polars 2.1.0.
Run it yourself with `python benchmarks/read_bench.py 100000`.

| 100,000 rows (5.5 MB) | seconds | peak RSS |
| --- | ---: | ---: |
| **hypaxel** `iter_rows` | **0.13** | **27 MB** |
| python-calamine `iter_rows` | 0.45 | 105 MB |
| openpyxl (read_only) | 3.39 | 63 MB |
| **hypaxel** `read_dataframe` (pandas) | **0.17** | 179 MB |
| pandas.read_excel (calamine) | 0.71 | 314 MB |
| pandas.read_excel (openpyxl) | 4.16 | 198 MB |
| **hypaxel** `read_dataframe` (polars) | **0.18** | 142 MB |
| fastexcel `to_polars` | 0.23 | 187 MB |

| 1,000,000 rows (55 MB) | seconds | peak RSS |
| --- | ---: | ---: |
| **hypaxel** `iter_rows` | **1.30** | **68 MB** |
| python-calamine `iter_rows` | 4.46 | 862 MB |
| openpyxl (read_only) | not run | not run |
| **hypaxel** `read_dataframe` (pandas) | **1.79** | 920 MB |
| pandas.read_excel (calamine) | 8.10 | 2,266 MB |
| pandas.read_excel (openpyxl) | not run | not run |
| **hypaxel** `read_dataframe` (polars) | **1.84** | 799 MB |
| fastexcel `to_polars` | 2.45 | 928 MB |

openpyxl is skipped at 1M rows because it is too slow at that size.

## Notes and limits

- **Write order:** rows are written top to bottom, and one worksheet at a time. Column widths
  and frozen panes are set in `add_worksheet`.
- **Formulas:** they are stored without cached results; Excel recalculates them when the file
  opens.
- **Long text:** text over 32,767 characters raises `XlsxError`. The offending cell is left
  empty and the file stays valid.
- **GIL:** the GIL is held while writing (values are read from Python objects), so use threads
  for parallel exports of separate files.
- **Reading:** cached formula results are read, not formula text. Styles (other than date
  detection), merged ranges, comments and hyperlinks are not read.

## Development

```bash
cd python
uv venv && uv pip install maturin pytest pytest-asyncio pandas polars numpy openpyxl xlsxwriter fastapi httpx pydantic
# read benchmark extras: uv pip install python-calamine fastexcel pyarrow
.venv/bin/maturin develop --release
.venv/bin/python -m pytest
```
