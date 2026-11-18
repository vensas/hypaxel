import dataclasses
import datetime as dt
import decimal
import enum
import io
import math
import typing
import zipfile

import numpy as np
import openpyxl
import pandas as pd
import polars as pl
import pydantic
import pytest

import hypaxel
from hypaxel import Formula, Styled, Writer, XlsxError


def load(data: bytes) -> openpyxl.Workbook:
    return openpyxl.load_workbook(io.BytesIO(data))


def rows(ws) -> list[tuple]:
    return list(ws.iter_rows(values_only=True))


def part(data: bytes, name: str) -> str:
    return zipfile.ZipFile(io.BytesIO(data)).read(name).decode()


# --------------------------------------------------------------------------------------------- values


class Color(str, enum.Enum):
    RED = "red"


class Plain:
    def __str__(self) -> str:
        return "plain object"


def test_value_types():
    tz = dt.timezone(dt.timedelta(hours=2))
    w = Writer()
    ws = w.add_worksheet("Values")
    ws.write_row(["text", 42, 2**70, 3.25, True, False, None, "after-none"])
    ws.write_row([dt.date(2024, 2, 29), dt.datetime(2024, 3, 15, 13, 45, 30, 123000), dt.datetime(2024, 1, 1, 8, 0, tzinfo=tz), dt.time(6, 30), dt.timedelta(hours=36)])
    ws.write_row([decimal.Decimal("1.25"), np.int64(7), np.float64(0.5), np.bool_(True), Color.RED, Plain(), Formula("=SUM(B1:B1)"), math.nan, math.inf])
    wb = load(w.finish())
    r = rows(wb["Values"])
    assert r[0] == ("text", 42, float(2**70), 3.25, True, False, None, "after-none", None)
    assert r[1][:5] == (
        dt.datetime(2024, 2, 29),
        dt.datetime(2024, 3, 15, 13, 45, 30, 123000),
        dt.datetime(2024, 1, 1, 8, 0),  # local clock time of the aware value
        dt.time(6, 30),
        dt.timedelta(hours=36),
    )
    assert r[2][:8] == (1.25, 7, 0.5, True, "red", "plain object", "=SUM(B1:B1)", "#NUM!")
    assert r[2][8] == "#NUM!"


def test_escaping_and_unicode():
    tricky = 'a & b < c > "q" ünïcødé 😀 \t tab\nnewline'
    w = Writer()
    w.add_worksheet("Esc & <Sheet>").write_row([tricky, "  padded  ", ""])
    wb = load(w.finish())
    assert wb.sheetnames == ["Esc & <Sheet>"]
    assert rows(wb.active)[0][:2] == (tricky, "  padded  ")


def test_styles_and_sheet_options():
    w = Writer()
    money = w.add_style(num_format="#,##0.00", bold=True, font_color="#FF0000", fill="#FFFF00", border="thin", align="center", wrap=True)
    assert w.add_style(num_format="#,##0.00", bold=True, font_color="#FF0000", fill="#FFFF00", border="thin", align="center", wrap=True) is not None
    ws = w.add_worksheet("S", freeze_rows=1, freeze_columns=1, column_widths=[30, 10], tab_color="#0000FF", show_grid_lines=False)
    ws.write_row(["Header"], style=w.add_style(bold=True))
    ws.write_row(["x", Styled(1234.5, money)])
    ws.merge_cells(3, 1, 3, 2)
    ws.autofilter(1, 1, 2, 2)
    w.add_worksheet("Hidden", hidden=True).write_row(["secret"])
    wb = load(w.finish())
    s = wb["S"]
    assert s.freeze_panes == "B2"
    assert s.column_dimensions["A"].width == 30
    assert s["A1"].font.b
    cell = s["B2"]
    assert cell.value == 1234.5 and cell.number_format == "#,##0.00" and cell.font.b and cell.fill.fgColor.rgb == "FFFFFF00"
    assert cell.alignment.horizontal == "center" and cell.alignment.wrap_text and cell.border.left.style == "thin"
    assert [str(r) for r in s.merged_cells.ranges] == ["A3:B3"]
    assert s.auto_filter.ref == "A1:B2"
    assert wb["Hidden"].sheet_state == "hidden"
    assert s.sheet_properties.tabColor.rgb == "FF0000FF"


def test_cell_by_cell_and_gaps():
    w = Writer()
    ws = w.add_worksheet("S")
    ws.write("a")
    ws.skip_cells(2)
    ws.write("d")
    ws.end_row()
    ws.skip_rows(2)
    ws.begin_row(height=30)
    ws.write("row 4")  # rows 2 and 3 were skipped
    ws.end_row()
    assert ws.row == 4
    wb = load(w.finish())
    assert wb.active["D1"].value == "d"
    assert wb.active["A4"].value == "row 4"
    assert wb.active.row_dimensions[4].height == 30


def test_write_columns_with_nan():
    w = Writer()
    ws = w.add_worksheet("C")
    assert ws.write_columns([[1, 2, 3], ["a", None, "c"], [1.5, math.nan, 2.5]]) == 3
    with pytest.raises(ValueError):
        ws.write_columns([[1], [1, 2]])
    wb = load(w.finish())
    assert rows(wb.active) == [(1, "a", 1.5), (2, None, None), (3, "c", 2.5)]


def test_shared_strings_and_compression_options():
    w = Writer(shared_strings=True, compression_level=6)
    w.add_worksheet("S").write_rows([("same", i) for i in range(100)])
    data = w.finish()
    assert 'count="100" uniqueCount="1"' in part(data, "xl/sharedStrings.xml")
    assert rows(load(data).active)[99] == ("same", 99)


# --------------------------------------------------------------------------------------------- errors


def test_errors():
    w = Writer()
    with pytest.raises(XlsxError, match="invalid worksheet name"):
        w.add_worksheet("a/b")
    first = w.add_worksheet("First")
    w.add_worksheet("Second")
    with pytest.raises(XlsxError, match="complete"):
        first.write_row([1])
    with pytest.raises(XlsxError, match="already exists"):
        w.add_worksheet("first")
    second = w.add_worksheet("Third")
    with pytest.raises(XlsxError, match="32767"):
        second.write_row(["x" * 40_000])
    with pytest.raises(TypeError, match="single cell"):
        second.write_row([[1, 2]])
    with pytest.raises(TypeError, match="not a string"):
        second.write_row("abc")
    with pytest.raises(ValueError, match="invalid color"):
        w.add_style(fill="nope")
    w.finish()
    with pytest.raises(XlsxError, match="finished"):
        w.add_worksheet("Late")


def test_drain_only_for_memory_writers(tmp_path):
    w = Writer(tmp_path / "x.xlsx")
    with pytest.raises(XlsxError, match="in-memory"):
        w.drain()
    w.finish()


# --------------------------------------------------------------------------------------------- destinations


def test_destinations(tmp_path):
    path = tmp_path / "ctx.xlsx"
    with Writer(path) as w:
        w.add_worksheet("S").write_row(["file"])
    assert load(path.read_bytes()).active["A1"].value == "file"

    buf = io.BytesIO()
    with Writer(buf) as w:
        w.add_worksheet("S").write_row(["file-like"])
    assert load(buf.getvalue()).active["A1"].value == "file-like"

    data = hypaxel.to_xlsx([{"a": 1}])
    assert load(data).active["A2"].value == 1
    assert hypaxel.to_xlsx([{"a": 1}], tmp_path / "one.xlsx") is None
    assert load((tmp_path / "one.xlsx").read_bytes()).active["A1"].value == "a"


def test_empty_workbook_gets_a_sheet():
    assert load(Writer().finish()).sheetnames == ["Sheet1"]


# --------------------------------------------------------------------------------------------- records


@dataclasses.dataclass
class Order:
    id: int
    customer: str
    amount: float
    placed: dt.date


class OrderTuple(typing.NamedTuple):
    id: int
    customer: str


class OrderModel(pydantic.BaseModel):
    id: int
    customer: str
    note: typing.Optional[str] = None


class Slotted:
    __slots__ = ("id", "name")

    def __init__(self, id, name):
        self.id = id
        self.name = name


@pytest.mark.parametrize(
    "records, expected",
    [
        ([{"id": 1, "name": "a"}, {"name": "b", "id": 2}, {"id": 3}], [("id", "name"), (1, "a"), (2, "b"), (3, None)]),
        ([OrderTuple(1, "a"), OrderTuple(2, "b")], [("id", "customer"), (1, "a"), (2, "b")]),
        ([OrderModel(id=1, customer="a"), OrderModel(id=2, customer="b", note="n")], [("id", "customer", "note"), (1, "a", None), (2, "b", "n")]),
        ([Slotted(1, "a")], [("id", "name"), (1, "a")]),
        ([(1, "a"), (2, "b")], [("Column1", "Column2"), (1, "a"), (2, "b")]),
    ],
)
def test_record_kinds(records, expected):
    w = Writer()
    hypaxel.write_records(w, "R", records)
    assert rows(load(w.finish())["R"]) == expected


def test_records_with_formats():
    orders = [Order(i, f"Customer {i}", i * 1.5, dt.date(2024, 1, i)) for i in range(1, 6)]
    w = Writer()
    ws = hypaxel.write_records(w, "Orders", orders, column_formats={"amount": "#,##0.00", "placed": "yyyy-mm-dd"})
    assert ws.row == 6
    wb = load(w.finish())
    s = wb["Orders"]
    assert rows(s)[0] == ("id", "customer", "amount", "placed")
    assert s["C3"].value == 3.0 and s["C3"].number_format == "#,##0.00"
    assert s["D3"].value == dt.datetime(2024, 1, 2) and s["D3"].number_format == "yyyy-mm-dd"
    assert s.freeze_panes == "A2" and s.auto_filter.ref == "A1:D6" and s["A1"].font.b
    with pytest.raises(KeyError):
        hypaxel.write_records(Writer(), "X", orders, column_formats={"nope": "0"})


# --------------------------------------------------------------------------------------------- data frames


def test_pandas_dataframe():
    df = pd.DataFrame(
        {
            "int": [1, 2, 3],
            "float": [1.5, np.nan, 3.5],
            "text": ["a", None, "c"],
            "when": pd.to_datetime(["2024-01-01 10:00", None, "2024-01-03 00:00"]),
            "aware": pd.to_datetime(["2024-01-01 10:00", "2024-01-02 00:00", "2024-01-03 00:00"]).tz_localize("Europe/Berlin"),
            "nullable": pd.array([1, None, 3], dtype="Int64"),
            "cat": pd.Categorical(["x", "y", "x"]),
            "flag": [True, False, True],
        },
        index=pd.Index(["r1", "r2", "r3"], name="key"),
    )
    w = Writer()
    hypaxel.write_dataframe(w, "DF", df, index=True, column_formats={"float": "0.0"})
    r = rows(load(w.finish())["DF"])
    assert r[0] == ("key", "int", "float", "text", "when", "aware", "nullable", "cat", "flag")
    assert r[1] == ("r1", 1, 1.5, "a", dt.datetime(2024, 1, 1, 10), dt.datetime(2024, 1, 1, 10), 1, "x", True)
    assert r[2] == ("r2", 2, None, None, None, dt.datetime(2024, 1, 2), None, "y", False)


def test_polars_dataframe():
    df = pl.DataFrame(
        {
            "id": [1, 2, None],
            "name": ["a", "b", None],
            "day": [dt.date(2024, 5, 1), None, dt.date(2024, 5, 3)],
            "ts": [dt.datetime(2024, 5, 1, 12), dt.datetime(2024, 5, 2), None],
            "score": [0.5, None, 1.5],
        }
    )
    data = hypaxel.to_xlsx(df, sheet_name="PL")
    r = rows(load(data)["PL"])
    assert r[0] == ("id", "name", "day", "ts", "score")
    assert r[1] == (1, "a", dt.datetime(2024, 5, 1), dt.datetime(2024, 5, 1, 12), 0.5)
    assert r[3] == (None, None, dt.datetime(2024, 5, 3), None, 1.5)


def test_large_dataframe_in_chunks():
    n = 25_000
    df = pd.DataFrame({"i": np.arange(n), "s": [f"row {i}" for i in range(n)]})
    w = Writer()
    hypaxel.write_dataframe(w, "Big", df, chunk_size=4_000)
    ws = load(w.finish())["Big"]
    assert ws.max_row == n + 1
    assert ws.cell(n + 1, 2).value == f"row {n - 1}"


# --------------------------------------------------------------------------------------------- streaming


def test_stream_rows_yields_many_chunks():
    chunks = list(hypaxel.stream_rows(((i, f"row {i}", i / 3) for i in range(60_000)), header=["i", "s", "f"], sheet_name="Stream"))
    assert len(chunks) > 5, len(chunks)
    ws = load(b"".join(chunks))["Stream"]
    assert ws.max_row == 60_001
    assert ws.cell(60_001, 2).value == "row 59999"


def test_stream_records_and_dataframe():
    data = b"".join(hypaxel.stream_records(({"id": i, "v": i * 2} for i in range(5_000)), column_formats={"v": "0.00"}))
    ws = load(data).active
    assert ws.max_row == 5_001 and ws["B5001"].value == 9998 and ws["B2"].number_format == "0.00"

    data = b"".join(hypaxel.stream_dataframe(pl.DataFrame({"a": list(range(3_000))})))
    assert load(data).active.max_row == 3_001


def test_stream_of_nothing():
    assert rows(load(b"".join(hypaxel.stream_records([]))).active) == []


async def test_async_streams():
    async def source():
        for i in range(7_000):
            yield (i, f"row {i}")

    data = b"".join([c async for c in hypaxel.astream_rows(source(), header=["i", "s"])])
    ws = load(data).active
    assert ws.max_row == 7_001 and ws["B7001"].value == "row 6999"

    async def models():
        for i in range(4_500):
            yield OrderModel(id=i, customer=f"c{i}")

    data = b"".join([c async for c in hypaxel.astream_records(models(), sheet_name="M")])
    ws = load(data)["M"]
    assert rows(ws)[0] == ("id", "customer", "note")
    assert ws.max_row == 4_501 and ws["B4501"].value == "c4499"


# --------------------------------------------------------------------------------------------- FastAPI


def test_fastapi_streaming_response():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from hypaxel.starlette import XlsxResponse

    app = FastAPI()

    @app.get("/sync.xlsx")
    def sync_export():
        return XlsxResponse(hypaxel.stream_records({"id": i, "name": f"n{i}"} for i in range(20_000)), filename="Bericht März")

    @app.get("/async.xlsx")
    async def async_export():
        async def rows():
            for i in range(10_000):
                yield (i, i * i)

        return XlsxResponse(hypaxel.astream_rows(rows(), header=["n", "square"]), filename="squares.xlsx")

    client = TestClient(app)
    r = client.get("/sync.xlsx")
    assert r.status_code == 200
    assert r.headers["content-type"] == hypaxel.CONTENT_TYPE
    assert r.headers["content-disposition"] == "attachment; filename=\"Bericht M_rz.xlsx\"; filename*=UTF-8''Bericht%20M%C3%A4rz.xlsx"
    assert load(r.content).active.max_row == 20_001

    r = client.get("/async.xlsx")
    assert load(r.content).active["B10001"].value == 9999 * 9999
