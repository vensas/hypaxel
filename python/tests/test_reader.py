import datetime as dt
import io
import os
import sys

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


def test_generated_header_names_never_collide():
    def fill(wb):
        ws = wb.add_worksheet("Data")
        ws.write_row(["name", "name_2", "name", "column_5", None, "name_3", "name"])
        ws.write_row([1, 2, 3, 4, 5, 6, 7])

    assert hypaxel.read_records(build(fill)) == [
        {"name": 1, "name_2": 2, "name_3": 3, "column_5": 4, "column_5_2": 5, "name_3_2": 6, "name_4": 7}
    ]


def test_reads_the_opened_file_after_its_path_is_replaced_or_deleted(tmp_path):
    def fill(wb):
        ws = wb.add_worksheet("Data")
        for i in range(1000):
            ws.write_row([i, f"row {i}"])

    path = tmp_path / "book.xlsx"
    path.write_bytes(build(fill, shared_strings=True))
    with hypaxel.open_workbook(path) as book:
        expected = list(book.rows())
        replacement = tmp_path / "other.xlsx"
        replacement.write_bytes(b"not a workbook")
        replacement.replace(path)
        assert list(book.rows()) == expected
        path.unlink()
        assert list(book.rows()) == expected


@pytest.mark.skipif(sys.platform == "win32", reason="counts descriptors in /dev/fd")
def test_close_releases_the_file(tmp_path):
    path = tmp_path / "book.xlsx"
    path.write_bytes(build(lambda wb: wb.add_worksheet("Data").write_row([1])))
    before = len(os.listdir("/dev/fd"))
    book = hypaxel.open_workbook(path)
    assert len(os.listdir("/dev/fd")) == before + 1
    book.close()
    assert len(os.listdir("/dev/fd")) == before
    with pytest.raises(ValueError, match="closed"):
        book.rows()


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


def test_reads_back_tables_row_properties_and_layout():
    def fill(wb):
        ws = wb.add_worksheet("Data", column_widths=[12.5, 30], row_outline_levels=2, outline_summary_above=True)
        ws.write_row(["Product", "Units & more", "Line\nbreak"])
        ws.write_row(["Widget", 3], outline_level=1)
        ws.write_row(["Gadget", 5], outline_level=2, hidden=True)
        ws.write_row(["Gizmo"], outline_level=1, collapsed=True, height=20.5)
        ws.skip_rows(2)
        ws.write_rows([[1, 2], [3, 4], [5, 6]])
        ws.add_table(1, 1, 4, 3, columns=["Product", "Units & more", "Line\nbreak"], name="Orders", style="TableStyleDark3",
                     row_stripes=False, column_stripes=True, first_column=True, last_column=True)
        ws.add_table(7, 1, 9, 2, header_row=False, style=None)
        wb.add_worksheet("Plain").write_row(["x"])

    data = build(fill)
    with hypaxel.open_workbook(data) as wb:
        assert wb.tables() == wb.tables("data") == [
            {"name": "Orders", "first_row": 1, "first_col": 1, "last_row": 4, "last_col": 3,
             "columns": ["Product", "Units & more", "Line\nbreak"], "style": "TableStyleDark3", "header_row": True,
             "autofilter": True, "row_stripes": False, "column_stripes": True, "first_column": True, "last_column": True,
             "totals_row": False},
            {"name": "Table2", "first_row": 7, "first_col": 1, "last_row": 9, "last_col": 2,
             "columns": ["Column1", "Column2"], "style": None, "header_row": False,
             "autofilter": False, "row_stripes": True, "column_stripes": False, "first_column": False, "last_column": False,
             "totals_row": False},
        ]
        assert wb.tables(1) == []
        with pytest.raises(XlsxError, match="no worksheet"):
            wb.tables("Missing")

        rows = wb.rows("Data")
        assert rows.column_widths == [(1, 1, 12.5, False), (2, 2, 30.0, False)]
        assert (rows.column_width(2), rows.column_width(3)) == (30.0, None)
        assert rows.column_hidden(2) is False and rows.column_hidden(3) is False
        assert rows.outline_summary_above is True and rows.row_outline_levels == 2
        assert (rows.row_number, rows.outline_level, rows.hidden, rows.collapsed, rows.height) == (0, 0, False, False, None)
        seen = [(rows.row_number, len(row), rows.outline_level, rows.hidden, rows.collapsed, rows.height) for row in rows]
        assert seen == [
            (1, 3, 0, False, False, None),
            (2, 2, 1, False, False, None),
            (3, 2, 2, True, False, None),
            (4, 1, 1, False, True, 20.5),
            (5, 0, 0, False, False, None),
            (6, 0, 0, False, False, None),
            (7, 2, 0, False, False, None),
            (8, 2, 0, False, False, None),
            (9, 2, 0, False, False, None),
        ]

        compact = wb.rows("Data", skip_empty=True)
        assert [compact.row_number for _ in compact] == [1, 2, 3, 4, 7, 8, 9]

        plain = wb.rows("Plain")
        assert plain.column_widths == [] and plain.outline_summary_above is False and plain.row_outline_levels == 0

    # A table read from a workbook can be written again with the same keys.
    table = hypaxel.open_workbook(data).tables()[0]
    position = [table.pop(key) for key in ("first_row", "first_col", "last_row", "last_col")]

    def copy(wb):
        ws = wb.add_worksheet("Copy")
        ws.write_rows([["Product", "Units & more", "Line\nbreak"], [1, 2, 3], [4, 5, 6], [7, 8, 9]])
        ws.add_table(*position, **table)

    assert hypaxel.open_workbook(build(copy)).tables()[0] == {**table, **dict(zip(("first_row", "first_col", "last_row", "last_col"), position))}


def test_reports_rows_without_values_on_request():
    def fill(wb):
        ws = wb.add_worksheet("Data", row_outline_levels=1)
        ws.write_row(["first"])
        ws.begin_row(height=40)
        ws.end_row()
        ws.skip_rows(2)
        ws.write_row([5])
        ws.begin_row(outline_level=1, hidden=True)
        ws.end_row()

    data = build(fill)

    def scan(**options):
        with hypaxel.open_workbook(data, include_empty_rows=options.pop("include", False)) as wb:
            rows = wb.rows(**options)
            return [(rows.row_number, row, rows.outline_level, rows.hidden, rows.height) for row in rows]

    assert scan() == [(1, ["first"], 0, False, None), (2, [], 0, False, None), (3, [], 0, False, None),
                      (4, [], 0, False, None), (5, [5], 0, False, None)]
    assert scan(include=True) == [(1, ["first"], 0, False, None), (2, [], 0, False, 40.0), (3, [], 0, False, None),
                                  (4, [], 0, False, None), (5, [5], 0, False, None), (6, [], 1, True, None)]
    assert [number for number, *_ in scan(include=True, skip_empty=True)] == [1, 5]
    assert hypaxel.read_rows(data, include_empty_rows=True)[-1] == []
    assert hypaxel.read_records(data, header_row=5, include_empty_rows=True) == []
    assert len(hypaxel.read_dataframe(data, header=False, include_empty_rows=True)) == 2


TESTDATA = os.path.join(os.path.dirname(__file__), "..", "..", "testdata", "reader")


@pytest.mark.parametrize("producer", ["excel", "libreoffice"])
def test_reads_tables_rows_and_layout_saved_by_other_applications(producer):
    styled = producer == "excel"
    # LibreOffice drops table styles and stores a height on every row.
    default_height = None if styled else 15.0
    with hypaxel.open_workbook(os.path.join(TESTDATA, f"{producer}.xlsx"), include_empty_rows=True) as wb:
        assert wb.sheet_names == ["Orders", "Plain"]
        assert wb.tables("Orders") == [
            {"name": "Orders", "first_row": 1, "first_col": 1, "last_row": 4, "last_col": 3,
             "columns": ["Product", "Units & more", "Price"], "style": "TableStyleDark3" if styled else None,
             "header_row": True, "autofilter": True, "row_stripes": False, "column_stripes": styled,
             "first_column": styled, "last_column": styled, "totals_row": False},
            {"name": "Table2", "first_row": 7, "first_col": 1, "last_row": 8, "last_col": 2,
             "columns": ["Column1", "Column2"], "style": None, "header_row": False, "autofilter": False,
             "row_stripes": styled, "column_stripes": False, "first_column": False, "last_column": False, "totals_row": False},
        ]
        assert wb.tables("Plain") == []

        rows = wb.rows("Orders", skip_empty=False)
        assert rows.column_widths == [(1, 1, 14.0, False), (2, 2, 22.5, False), (4, 4, 6.0, True)]
        assert rows.column_hidden(4) is True
        assert rows.outline_summary_above is True and rows.row_outline_levels == 2
        seen = [(rows.row_number, len(row), rows.outline_level, rows.hidden, rows.collapsed, rows.height) for row in rows]
        assert seen == [
            (1, 3, 0, False, False, default_height),
            (2, 3, 1, False, styled, default_height),
            (3, 3, 2, True, False, default_height),
            (4, 3, 1, False, True, 30.0),
            (5, 0, 0, False, False, None),
            (6, 0, 0, False, False, 45.0),
            (7, 2, 0, False, False, default_height),
            (8, 2, 0, False, False, default_height),
        ]


def test_tables_with_a_totals_row_cannot_be_written():
    wb = Writer()
    ws = wb.add_worksheet("Data")
    ws.write_rows([["a"], [1], [2]])
    with pytest.raises((XlsxError, ValueError), match="totals row"):
        ws.add_table(1, 1, 3, 1, columns=["a"], totals_row=True)


def test_reads_a_table_with_a_totals_row_saved_by_excel():
    with hypaxel.open_workbook(os.path.join(TESTDATA, "excel-totals.xlsx")) as wb:
        assert wb.sheet_names == ["Inventory"]
        assert wb.tables() == [{
            "name": "Table1", "first_row": 1, "first_col": 1, "last_row": 1002, "last_col": 11,
            "columns": ["SKU", "Item", "Group", "Vendor", "Cost", "Price",
                        "Opening", "Sold", "Closing", "Reorder", "Status"],
            "style": "TableStyleMedium2", "header_row": True, "autofilter": True, "row_stripes": True,
            "column_stripes": False, "first_column": False, "last_column": False, "totals_row": True,
        }]

        rows = wb.rows()
        widths = rows.column_widths
        assert len(widths) == 11
        assert (widths[0], widths[-1]) == ((1, 1, 11.6640625, False), (11, 11, 13.1640625, False))
        assert rows.outline_summary_above is False
        data = []
        for row in rows:
            assert (rows.outline_level, rows.hidden, rows.height) == (0, False, None)
            data.append(row)
        assert len(data) == 1002
        assert data[-1] == ["Total", None, None, None, 21.085, 33.736, None, 99696, None, 34500, 1000]


def test_reads_a_table_downloaded_from_google_sheets():
    with hypaxel.open_workbook(os.path.join(TESTDATA, "google-sheets.xlsx")) as wb:
        assert wb.sheet_names == ["Inventory"]
        # The file uses a custom table style and has no filter buttons.
        assert wb.tables() == [{
            "name": "Table1", "first_row": 1, "first_col": 1, "last_row": 903, "last_col": 11,
            "columns": ["SKU", "Item", "Group", "Vendor", "Cost", "Price", "Opening", "Sold", "Closing", "Reorder", "Status"],
            "style": "Inventory-style", "header_row": True, "autofilter": False, "row_stripes": True,
            "column_stripes": False, "first_column": True, "last_column": True, "totals_row": True,
        }]

        rows = wb.rows()
        widths = rows.column_widths
        assert len(widths) == 12
        assert (widths[0], widths[-1]) == ((1, 1, 11.63, False), (12, 26, 8.88, False))
        assert rows.column_width(20) == 8.88
        assert rows.outline_summary_above is False
        data = []
        for row in rows:
            assert (rows.outline_level, rows.hidden) == (0, False)
            assert rows.height == (None if rows.row_number == 1 else 15.75)
            data.append(row)
        assert len(data) == 903
        assert data[1][6] == 250 and type(data[1][6]) is int
        assert data[-1] == ["Total", None, None, None, 21.20421754, 33.92674806, None, 90058, None, 31060, 901]


def test_reads_a_sheet_exported_by_numbers():
    path = os.path.join(TESTDATA, "numbers.xlsx")
    with hypaxel.open_workbook(path, include_empty_rows=True) as wb:
        assert wb.sheet_names == ["Table and Chart"]
        # Numbers writes its table as plain cells, not as an Excel table.
        assert wb.tables() == []
        rows = wb.rows()
        assert rows.column_widths == [(1, 1, 24.6719, False), (2, 2, 18.3672, False), (3, 5, 18.7188, False), (6, 16384, 27.75, False)]
        assert (rows.outline_summary_above, rows.row_outline_levels) == (False, 0)
        seen = [(rows.row_number, rows.height, row) for row in rows]
        assert seen == [
            (1, 311.6, []),
            (2, 27.65, ["Table 1"]),
            (3, 20.25, [None, "Category A", "Category B"]),
            (4, 20.25, ["Item 1", 5, 7]),
            (5, 20.05, ["Item 2", 10, 8]),
            (6, 20.05, ["Item 3", 9, 15]),
            (7, 20.05, ["Item 4", 7, 12]),
            (8, 20.05, ["Item 5", 16, 21]),
        ]
    assert hypaxel.read_rows(path, skip_empty=True)[0] == ["Table 1"]


def test_table_names_that_look_like_excel_escapes_round_trip():
    def fill(wb):
        ws = wb.add_worksheet("S")
        ws.write_rows([["a_x0042_"], [1]])
        ws.add_table(1, 1, 2, 1, name="T_x0041_", columns=["a_x0042_"])

    table = hypaxel.open_workbook(build(fill)).tables()[0]
    assert (table["name"], table["columns"]) == ("T_x0041_", ["a_x0042_"])


def test_round_trips_custom_table_styles():
    def fill(wb):
        ws = wb.add_worksheet("S")
        ws.write_rows([["a", None, "b"], [1, None, 2]])
        ws.add_table(1, 1, 2, 1, columns=["a"], name="Branded", style="Brand & Co")
        ws.add_table(1, 3, 2, 3, columns=["b"], name="Plain", style="none")

    with hypaxel.open_workbook(build(fill)) as wb:
        assert [t["style"] for t in wb.tables()] == ["Brand & Co", None]


def _sheet_package(sheet_xml: str) -> bytes:
    import zipfile

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("_rels/.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr("xl/workbook.xml", '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
        z.writestr("xl/worksheets/sheet1.xml", sheet_xml)
    return buf.getvalue()


def test_reports_hidden_columns_without_a_width():
    data = _sheet_package('<worksheet><cols><col min="1" max="1" width="9"/><col min="2" max="3" hidden="1"/><col min="4" max="4" style="1"/></cols><sheetData/></worksheet>')
    with hypaxel.open_workbook(data) as wb:
        rows = wb.rows()
        assert rows.column_widths == [(1, 1, 9.0, False), (2, 3, None, True)]
        assert (rows.column_width(2), rows.column_hidden(3), rows.column_hidden(4)) == (None, True, False)


def test_applies_configured_limits():
    text = "x" * 100_000
    data = _sheet_package(f'<worksheet><sheetData><row r="1"><c t="inlineStr"><is><t>{text}</t></is></c><c t="inlineStr"><is><t>{text}</t></is></c></row></sheetData></worksheet>')
    assert len(hypaxel.read_rows(data)[0]) == 2
    with pytest.raises(XlsxError, match="max_token_bytes"):
        hypaxel.read_rows(data, max_token_bytes=1)
    with pytest.raises(XlsxError, match="max_row_text_bytes"):
        hypaxel.read_rows(data, max_row_text_bytes=199_999)
    for option in ["max_tables", "max_table_bytes", "max_token_bytes", "max_row_text_bytes"]:
        for value in [0, -1]:
            with pytest.raises(ValueError, match=f"{option} must be at least 1"):
                hypaxel.open_workbook(data, **{option: value})

    def fill(wb):
        ws = wb.add_worksheet("S")
        ws.write_rows([["a", None, "b"], [1, None, 2]])
        ws.add_table(1, 1, 2, 1, columns=["a"])
        ws.add_table(1, 3, 2, 3, columns=["b"])

    tables = build(fill)
    with hypaxel.open_workbook(tables, max_tables=1) as wb, pytest.raises(XlsxError, match="max_tables"):
        wb.tables()
    with hypaxel.open_workbook(tables, max_table_bytes=10) as wb, pytest.raises(XlsxError, match="max_table_bytes"):
        wb.tables()
