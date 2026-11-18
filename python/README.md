# Hypaxel for Python

**High-performance, constant-memory XLSX (Excel) writer for Python, powered by Rust.**

Hypaxel writes `.xlsx` files as a forward-only stream. Rows are converted, serialized and compressed
in native code as you write them, so memory stays flat whether you export 10 rows or 10 million.
It can stream a download to the browser while your query is still running.

- **Fast:** writes 100k rows × 10 columns in 0.1 s, about **50x faster than XlsxWriter** and
  **95x faster than openpyxl**. DataFrames are about **30x faster** than `pandas.to_excel` and
  `polars.write_excel`, with 10–30x less memory.
- **Streaming:** sync and async generators that plug into FastAPI/Starlette, Django and Flask
  streaming responses.
- **Data you already have:** tuples and lists, dicts, dataclasses, named tuples, pydantic models,
  pandas and polars DataFrames, sync and async iterables (e.g. async database cursors).
- **Real Excel types:** `date`, `datetime` (timezone-aware ones are written as local time), `time`,
  `timedelta`, `Decimal`, numpy scalars, `bool`, and huge ints. NaN, None, NaT and pd.NA become
  empty cells.
- **Styles and layout:** fonts, fills, borders, alignment, number formats, merged cells, frozen
  panes, auto filters, formulas, hidden sheets, tab colors.
- **Valid output:** checked with the Office Open XML schema validator and read back with openpyxl.

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
| `Writer.add_worksheet(name, *, freeze_rows=, freeze_columns=, column_widths=, hidden=, show_grid_lines=, tab_color=)` | returns a `Worksheet`. The previous sheet is completed |
| `Writer.drain()` / `Writer.finish()` | in-memory chunks / complete the workbook |
| `Worksheet.write_row(values, style=None)`, `write_rows(rows)`, `write_columns(columns)`, `write_dicts(records, keys)` | bulk writers, with the loops running in Rust |
| `Worksheet.write(value)`, `skip_cells(n)`, `end_row()`, `begin_row(height=, style=)`, `skip_rows(n)` | cell-by-cell control |
| `Worksheet.merge_cells(r1, c1, r2, c2)`, `autofilter(r1, c1, r2, c2)` | ranges (1-based, inclusive) |
| `Formula("SUM(A1:A9)")`, `Styled(value, style)` | special cell values |

## Performance

The same 10-column dataset as the .NET and Rust benchmarks (int, 2× str, 2× float, datetime,
bool, int, unique str, float). Python 3.13, Linux, Xeon 2.1 GHz. Run it yourself with
`python benchmarks/bench.py 100000`.

| 100,000 rows | seconds | rows/s | peak Python memory |
| --- | ---: | ---: | ---: |
| **hypaxel** `write_rows` | **0.10** | **~1,000,000** | 5 MB (the output itself) |
| **hypaxel** `stream_rows` | **0.09** | **~1,080,000** | 11 MB |
| xlsxwriter (constant_memory) | 5.15 | 19,400 | 7 MB |
| openpyxl (write_only) | 9.48 | 10,500 | 7 MB |
| **hypaxel** pandas DataFrame* | **0.27** | 371,000 | 16 MB |
| pandas.to_excel (xlsxwriter)* | 8.68 | 11,500 | 147 MB |
| **hypaxel** polars DataFrame* | **0.21** | 475,000 | 8 MB |
| polars.write_excel* | 6.15 | 16,300 | 265 MB |

\* includes building the DataFrame from the row list.

Hypaxel writes 1M rows in about 0.9 s (`write_rows`) and 2.5–3.4 s from a DataFrame, scaling
linearly.

## Notes and limits

- **Write order:** rows are written top to bottom, and one worksheet at a time. Column widths
  and frozen panes are set in `add_worksheet`.
- **Formulas:** they are stored without cached results; Excel recalculates them when the file
  opens.
- **Long text:** text over 32,767 characters raises `XlsxError`. The offending cell is left
  empty and the file stays valid.
- **GIL:** the GIL is held while writing (values are read from Python objects), so use threads
  for parallel exports of separate files.

## Development

```bash
cd python
uv venv && uv pip install maturin pytest pytest-asyncio pandas polars numpy openpyxl xlsxwriter fastapi httpx pydantic
.venv/bin/maturin develop --release
.venv/bin/python -m pytest
```
