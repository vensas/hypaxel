"""Write benchmark against the established Python XLSX writers.

Usage: python benchmarks/bench.py [rows]      (default 100_000)

Dataset: the same 10 mixed columns as the .NET and Rust benchmarks (int, 2x str, 2x float,
datetime, bool, int, unique str, float). Every library writes to an in-memory buffer; time
excludes building the input data. Time is measured without tracing; peak memory comes from a
separate tracemalloc run (tracemalloc slows pure-Python code a lot, so it must not be timed). It
counts Python allocations only; the Rust core's native buffers are a fixed ~200 KiB.
"""

from __future__ import annotations

import datetime as dt
import gc
import io
import sys
import time
import tracemalloc

import hypaxel

CATEGORIES = ["Hardware", "Software", "Services", "Consulting", "Support", "Training"]
HEADER = ["id", "name", "category", "price", "amount", "created", "active", "quantity", "comment", "ratio"]


def data(n: int) -> list[tuple]:
    start = dt.datetime(2024, 1, 1)
    return [
        (i, f"Customer {i % 5000}", CATEGORIES[i % 6], i * 1.25, i * 0.01, start + dt.timedelta(minutes=i), i % 2 == 0, i * 3, f"Unique comment #{i}", i / 7)
        for i in range(n)
    ]


def run_hypaxel(rows: list[tuple]) -> bytes:
    w = hypaxel.Writer()
    ws = w.add_worksheet("Data")
    ws.write_row(HEADER)
    ws.write_rows(rows)
    return w.finish()


def run_hypaxel_stream(rows: list[tuple]) -> bytes:
    return b"".join(hypaxel.stream_rows(rows, header=HEADER))


def run_xlsxwriter(rows: list[tuple]) -> bytes:
    import xlsxwriter

    buf = io.BytesIO()
    wb = xlsxwriter.Workbook(buf, {"constant_memory": True, "in_memory": False})
    ws = wb.add_worksheet("Data")
    date = wb.add_format({"num_format": "yyyy-mm-dd hh:mm"})
    ws.write_row(0, 0, HEADER)
    for r, row in enumerate(rows, start=1):
        ws.write_row(r, 0, row[:5])
        ws.write_datetime(r, 5, row[5], date)
        ws.write_row(r, 6, row[6:])
    wb.close()
    return buf.getvalue()


def run_openpyxl(rows: list[tuple]) -> bytes:
    import openpyxl

    wb = openpyxl.Workbook(write_only=True)
    ws = wb.create_sheet("Data")
    ws.append(HEADER)
    for row in rows:
        ws.append(row)
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def run_pandas(rows: list[tuple]) -> bytes:
    import pandas as pd

    df = pd.DataFrame(rows, columns=HEADER)
    buf = io.BytesIO()
    df.to_excel(buf, index=False, engine="xlsxwriter")
    return buf.getvalue()


def run_hypaxel_pandas(rows: list[tuple]) -> bytes:
    import pandas as pd

    df = pd.DataFrame(rows, columns=HEADER)
    return hypaxel.to_xlsx(df)


def run_polars(rows: list[tuple]) -> bytes:
    import polars as pl

    df = pl.DataFrame(rows, schema=HEADER, orient="row")
    buf = io.BytesIO()
    df.write_excel(buf)
    return buf.getvalue()


def run_hypaxel_polars(rows: list[tuple]) -> bytes:
    import polars as pl

    df = pl.DataFrame(rows, schema=HEADER, orient="row")
    return hypaxel.to_xlsx(df)


CASES = [
    ("hypaxel (write_rows)", run_hypaxel),
    ("hypaxel (stream_rows)", run_hypaxel_stream),
    ("xlsxwriter (constant_memory)", run_xlsxwriter),
    ("openpyxl (write_only)", run_openpyxl),
    ("hypaxel (pandas DataFrame)*", run_hypaxel_pandas),
    ("pandas.to_excel (xlsxwriter)*", run_pandas),
    ("hypaxel (polars DataFrame)*", run_hypaxel_polars),
    ("polars.write_excel*", run_polars),
]


def main() -> None:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 100_000
    rows = data(n)
    print(f"{n:,} rows x 10 columns, Python {sys.version.split()[0]}")
    print(f"{'library':32} {'seconds':>9} {'rows/s':>11} {'peak MB':>9} {'file MB':>8}")
    for name, fn in CASES:
        fn(rows[:1000])  # warm-up / imports
        gc.collect()
        t = time.perf_counter()
        out = fn(rows)
        elapsed = time.perf_counter() - t
        del out
        gc.collect()
        tracemalloc.start()
        out = fn(rows)
        _, peak = tracemalloc.get_traced_memory()
        tracemalloc.stop()
        print(f"{name:32} {elapsed:9.2f} {n / elapsed:11,.0f} {peak / 1e6:9.1f} {len(out) / 1e6:8.1f}")
    print("* includes building the DataFrame from the row list")


if __name__ == "__main__":
    main()
