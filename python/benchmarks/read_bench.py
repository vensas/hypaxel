"""Read benchmark against the established Python XLSX readers.

Usage: python benchmarks/read_bench.py [rows] [--inline]      (default 100_000)

The file holds the same 10 mixed columns as the write benchmark (int, 2x str, 2x float, datetime,
bool, int, unique str, float) plus a header row. Like files saved by Excel it uses a shared string
table, unless ``--inline`` is given. Every reader runs in a fresh process, so peak RSS (the whole
process, including native memory) is measured per library. Each run reports the number of
non-empty cells it produced, so the readers can be checked against each other.
"""

from __future__ import annotations

import datetime as dt
import os
import platform
import resource
import subprocess
import sys
import tempfile
import time

CATEGORIES = ["Hardware", "Software", "Services", "Consulting", "Support", "Training"]
HEADER = ["id", "name", "category", "price", "amount", "created", "active", "quantity", "comment", "ratio"]


def generate(path: str, n: int, shared: bool) -> None:
    import hypaxel

    start = dt.datetime(2024, 1, 1)
    rows = (
        (i, f"Customer {i % 5000}", CATEGORIES[i % 6], i * 1.25, i * 0.01, start + dt.timedelta(minutes=i), i % 2 == 0, i * 3, f"Unique comment #{i}", i / 7)
        for i in range(n)
    )
    with open(path, "wb") as f:
        w = hypaxel.Writer(f, shared_strings=shared)
        ws = w.add_worksheet("Data")
        ws.write_row(HEADER)
        ws.write_rows(rows)
        w.finish()


def count_rows(rows) -> int:
    return sum(1 for row in rows for v in row if v is not None)


def count_frame(df) -> int:
    if hasattr(df, "null_count"):
        return int(df.height * df.width - sum(df.null_count().row(0)))
    return int(df.notna().sum().sum())


def hypaxel_rows(path):
    import hypaxel

    return count_rows(hypaxel.iter_rows(path))


def hypaxel_pandas(path):
    import hypaxel

    return count_frame(hypaxel.read_dataframe(path)) + len(HEADER)


def hypaxel_polars(path):
    import hypaxel

    return count_frame(hypaxel.read_dataframe(path, library="polars")) + len(HEADER)


def openpyxl_rows(path):
    import openpyxl

    wb = openpyxl.load_workbook(path, read_only=True)
    n = count_rows(wb.worksheets[0].iter_rows(values_only=True))
    wb.close()
    return n


def calamine_rows(path):
    from python_calamine import CalamineWorkbook

    return count_rows(CalamineWorkbook.from_path(path).get_sheet_by_index(0).iter_rows())


def pandas_openpyxl(path):
    import pandas as pd

    return count_frame(pd.read_excel(path, engine="openpyxl")) + len(HEADER)


def pandas_calamine(path):
    import pandas as pd

    return count_frame(pd.read_excel(path, engine="calamine")) + len(HEADER)


def fastexcel_polars(path):
    import fastexcel

    return count_frame(fastexcel.read_excel(path).load_sheet(0).to_polars()) + len(HEADER)


READERS = {
    "hypaxel iter_rows": hypaxel_rows,
    "openpyxl read_only": openpyxl_rows,
    "python-calamine iter_rows": calamine_rows,
    "hypaxel read_dataframe (pandas)": hypaxel_pandas,
    "pandas.read_excel (openpyxl)": pandas_openpyxl,
    "pandas.read_excel (calamine)": pandas_calamine,
    "hypaxel read_dataframe (polars)": hypaxel_polars,
    "fastexcel to_polars": fastexcel_polars,
}


IMPORTS = {
    "hypaxel": ["hypaxel"],
    "openpyxl": ["openpyxl"],
    "python-calamine": ["python_calamine"],
    "pandas": ["pandas"],
    "polars": ["polars"],
    "fastexcel": ["fastexcel", "polars", "pyarrow"],
}


def child(name: str, path: str) -> None:
    # Imports are not timed: pandas, polars and pyarrow alone take hundreds of milliseconds.
    import importlib

    for key, modules in IMPORTS.items():
        if key in name or (key == "pandas" and "calamine" in name and "read_excel" in name):
            for module in modules:
                importlib.import_module(module)
    started = time.perf_counter()
    cells = READERS[name](path)
    elapsed = time.perf_counter() - started
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    peak_bytes = peak if sys.platform == "darwin" else peak * 1024
    print(f"{elapsed}\t{peak_bytes}\t{cells}")


def version(module: str) -> str:
    from importlib.metadata import PackageNotFoundError, version as v

    try:
        return v(module)
    except PackageNotFoundError:
        return "-"


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "--child":
        child(sys.argv[2], sys.argv[3])
        return
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 100_000
    shared = "--inline" not in sys.argv

    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "bench.xlsx")
        generate(path, n, shared)
        size = os.path.getsize(path) / 1e6
        print(f"{n:,} rows x 10 columns, {size:.1f} MB, {'shared' if shared else 'inline'} strings")
        print(f"Python {platform.python_version()}, {platform.machine()}, " + ", ".join(
            f"{m} {version(m)}" for m in ("hypaxel", "openpyxl", "python-calamine", "fastexcel", "pandas", "polars")
        ))
        print(f"{'reader':34} {'seconds':>8} {'peak RSS':>10} {'cells':>12}")
        for name in READERS:
            if n > 200_000 and "openpyxl" in name:
                print(f"{name:34} {'skipped (too slow at this size)':>32}")
                continue
            out = subprocess.run([sys.executable, __file__, "--child", name, path], capture_output=True, text=True)
            if out.returncode != 0:
                print(f"{name:34} failed: {out.stderr.strip().splitlines()[-1] if out.stderr else out.returncode}")
                continue
            seconds, peak, cells = out.stdout.split()
            print(f"{name:34} {float(seconds):8.2f} {int(peak) / 1e6:8.0f} MB {int(cells):12,}")


if __name__ == "__main__":
    main()
