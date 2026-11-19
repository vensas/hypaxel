import datetime as dt
import io

import openpyxl
import pandas as pd
import polars as pl
import pytest

import hypaxel
from hypaxel import Formula, Writer, XlsxError


def build(fill, **options) -> bytes:
    wb = Writer(**options)
    fill(wb)
    return wb.finish()


def test_reads_back_every_value_type():
    when = dt.datetime(2024, 3, 15, 13, 45, 30, 250_000)

    def fill(wb):
        ws = wb.add_worksheet("Data")
        ws.write_row(["text", 42, -7, 3.25, True, False, None, "after-gap"])
        ws.write_row([when, when.date(), dt.time(6, 30), dt.timedelta(hours=36, minutes=15)])
        ws.write_row([float("nan"), Formula("SUM(B1:C1)"), "", "<&>\"' 東京 😀\nline", 2**53, 0.1])

    for shared in (False, True):
        rows = hypaxel.read_rows(build(fill, shared_strings=shared))
        assert rows[0] == ["text", 42, -7, 3.25, True, False, None, "after-gap"]
        assert type(rows[0][1]) is int and type(rows[0][3]) is float
        assert rows[1] == [when, dt.datetime(2024, 3, 15), dt.time(6, 30), dt.timedelta(hours=36, minutes=15)]
        # NaN is written as an error cell; a formula without a cached value reads as None.
        assert rows[2] == ["#NUM!", None, "", "<&>\"' 東京 😀\nline", float(2**53), 0.1]


def test_sources_sheets_and_gaps(tmp_path):
    def fill(wb):
        wb.add_worksheet("First").write_row(["a"])
        ws = wb.add_worksheet("Second", hidden=True)
        ws.write_row(["header"])
        ws.skip_rows(2)
        ws.write_row([None, None, "C4"])

    data = build(fill)
    path = tmp_path / "book.xlsx"
    path.write_bytes(data)

    for source in (data, str(path), path, io.BytesIO(data)):
        with hypaxel.open_workbook(source) as wb:
            assert wb.sheet_names == ["First", "Second"]
            assert wb.sheets[1] == {"name": "Second", "visible": "hidden", "kind": "worksheet"}
            assert not wb.date_1904
            assert list(wb.rows()) == [["a"]]
            assert list(wb.rows("second")) == [["header"], [], [], [None, None, "C4"]]
            assert list(wb.rows(-1, skip_empty=True)) == [["header"], [None, None, "C4"]]

    # Two iterators over the same reader are independent.
    with hypaxel.open_workbook(path) as wb:
        a, b = wb.rows("First"), wb.rows("Second")
        assert next(b) == ["header"]
        assert next(a) == ["a"]


def test_matches_openpyxl_on_files_it_wrote(tmp_path):
    book = openpyxl.Workbook()
    ws = book.active
    ws.title = "Report"
    ws.append(["Name", "Amount", "When", "Flag", "Share"])
    ws.append(["Widget", 12.5, dt.datetime(2024, 5, 1, 8, 30), True, 0.25])
    ws.append(["Gadget", 7, dt.date(1999, 12, 31), False, None])
    ws["F5"] = "far away"
    ws["B6"] = "=1+1"
    ws["C2"].number_format = "yyyy-mm-dd hh:mm"
    path = tmp_path / "openpyxl.xlsx"
    book.save(path)

    ours = hypaxel.read_rows(path)
    theirs = [list(r) for r in openpyxl.load_workbook(path, read_only=True).active.iter_rows(values_only=True)]
    # openpyxl pads every row to the sheet width; we end rows at their last value.
    trimmed = []
    for row in theirs:
        while row and row[-1] is None:
            row.pop()
        trimmed.append(row)
    # openpyxl stores formulas without cached values; it reads back the formula text.
    assert trimmed[5] == [None, "=1+1"]
    assert len(ours) == 5
    assert ours == trimmed[:5]


def test_records_and_dataframes():
    def fill(wb):
        ws = wb.add_worksheet("S")
        ws.write_row(["id", "name", None, "name", "when"])
        ws.write_row([1, "Alice", "x", "dup", dt.date(2024, 1, 2)])
        ws.skip_rows(1)
        ws.write_row([2, "Bob"])
        ws.write_row([3, None, None, None, None, "extra"])

    data = build(fill)
    records = hypaxel.read_records(data)
    assert records == [
        {"id": 1, "name": "Alice", "column_3": "x", "name_2": "dup", "when": dt.datetime(2024, 1, 2)},
        {"id": 2, "name": "Bob", "column_3": None, "name_2": None, "when": None},
        {"id": 3, "name": None, "column_3": None, "name_2": None, "when": None, "column_6": "extra"},
    ]

    df = hypaxel.read_dataframe(data)
    assert list(df.columns) == ["id", "name", "column_3", "name_2", "when", "column_6"]
    assert df["id"].tolist() == [1, 2, 3]
    assert pd.api.types.is_datetime64_any_dtype(df["when"])

    frame = hypaxel.read_dataframe(data, library="polars", header=False)
    assert isinstance(frame, pl.DataFrame)
    assert frame.columns[:2] == ["column_1", "column_2"]
    assert frame.height == 4
    # A column mixing text and dates cannot be typed; polars gets it as text.
    assert frame["column_5"].to_list() == ["when", "2024-01-02 00:00:00", None, None]
    assert frame["column_1"].to_list() == ["id", "1", "2", "3"]

    with pytest.raises(ValueError):
        hypaxel.read_dataframe(data, library="duckdb")


def test_round_trips_a_dataframe():
    df = pd.DataFrame({"n": [1, 2, 3], "x": [0.5, None, 2.5], "s": ["a", "b", None], "d": pd.to_datetime(["2024-01-01", "2024-06-30", "2025-12-31"])})
    data = hypaxel.to_xlsx(df)
    back = hypaxel.read_dataframe(data)
    assert back["n"].tolist() == [1, 2, 3]
    assert back["s"].tolist()[:2] == ["a", "b"]
    assert back["d"].tolist() == df["d"].tolist()


def test_errors(tmp_path):
    with pytest.raises(FileNotFoundError):
        hypaxel.open_workbook(tmp_path / "missing.xlsx")
    with pytest.raises(XlsxError, match="not an XLSX workbook"):
        hypaxel.open_workbook(b"id,name\n1,Alice\n")
    with pytest.raises(XlsxError, match="legacy .xls"):
        hypaxel.open_workbook(bytes.fromhex("d0cf11e0a1b11ae1") + bytes(600))
    with pytest.raises(TypeError, match="binary mode"):
        hypaxel.open_workbook(io.StringIO("text"))

    data = build(lambda wb: wb.add_worksheet("Only").write_row([1]))
    with pytest.raises(XlsxError, match=r'no worksheet "Nope"; the workbook has \["Only"\]'):
        hypaxel.read_rows(data, "Nope")
    with pytest.raises(XlsxError, match="no worksheet at index 3"):
        hypaxel.read_rows(data, 3)

    wb = hypaxel.open_workbook(data)
    wb.close()
    with pytest.raises(ValueError, match="closed"):
        wb.rows()

    with pytest.raises(XlsxError, match="limit exceeded"):
        shared = build(lambda wb: wb.add_worksheet("S").write_rows([[f"string {i}"] for i in range(100)]), shared_strings=True)
        hypaxel.read_rows(shared, max_shared_strings_bytes=50)


def test_1904_dates():
    data = build(lambda wb: wb.add_worksheet("S").write_row([dt.date(2024, 2, 29)]), date_1904=True)
    with hypaxel.open_workbook(data) as wb:
        assert wb.date_1904
        assert list(wb.rows()) == [[dt.datetime(2024, 2, 29)]]
