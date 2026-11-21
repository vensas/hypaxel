"""Hypaxel: high-performance, constant-memory streaming XLSX writer and reader.

Reading::

    for row in hypaxel.iter_rows("report.xlsx", "Data"):
        ...                                  # lists: str, int, float, bool, datetime, None

    records = hypaxel.read_records("report.xlsx")        # first row is the header
    df = hypaxel.read_dataframe("report.xlsx")           # pandas (or library="polars")

Writing, low-level API (native, see ``_hypaxel.pyi``)::

    with hypaxel.Writer("report.xlsx") as wb:
        bold = wb.add_style(bold=True)
        ws = wb.add_worksheet("Data", freeze_rows=1)
        ws.write_row(["Name", "Total"], style=bold)
        ws.write_rows([("Alice", 10), ("Bob", 20)])

High-level writing helpers (this module): :func:`to_xlsx`, :func:`write_records`, :func:`write_dataframe`
and the streaming generators :func:`stream_rows`, :func:`stream_records`, :func:`stream_dataframe`,
:func:`astream_rows`, :func:`astream_records` for web responses.
"""

from __future__ import annotations

import dataclasses
import itertools
import operator
import os
from collections.abc import AsyncIterable, Callable, Iterable, Iterator, Mapping, Sequence
from typing import IO, Any, AsyncIterator, Optional, Union

from ._hypaxel import (
    CONTENT_TYPE,
    MAX_COLUMNS,
    MAX_ROWS,
    Formula,
    Reader,
    RowIterator,
    Style,
    Styled,
    Worksheet,
    Writer,
    XlsxError,
    column_name,
)

__all__ = [
    "CONTENT_TYPE",
    "MAX_COLUMNS",
    "MAX_ROWS",
    "Formula",
    "Reader",
    "RowIterator",
    "Style",
    "Styled",
    "Worksheet",
    "Writer",
    "XlsxError",
    "astream_records",
    "astream_rows",
    "column_name",
    "iter_records",
    "iter_rows",
    "open_workbook",
    "read_dataframe",
    "read_records",
    "read_rows",
    "stream_dataframe",
    "stream_records",
    "stream_rows",
    "to_xlsx",
    "write_dataframe",
    "write_records",
]

Destination = Union[str, "os.PathLike[str]", IO[bytes], None]
Source = Union[str, "os.PathLike[str]", bytes, IO[bytes]]
SheetRef = Union[str, int]

_BATCH_ROWS = 2_000


# --------------------------------------------------------------------------------------------------
# Helpers


def _header_style(writer: Writer) -> Style:
    return writer.add_style(bold=True, fill="#D9D9D9", border_bottom="thin")


def _estimate_widths(headers: Sequence[Any]) -> list[float]:
    return [float(min(max(len(str(h)) + 3, 12), 60)) for h in headers]


def _column_styles(writer: Writer, headers: Sequence[str], column_formats: Optional[Mapping[str, str]]) -> Optional[list[Optional[Style]]]:
    if not column_formats:
        return None
    unknown = set(column_formats) - set(headers)
    if unknown:
        raise KeyError(f"column_formats refers to unknown columns: {sorted(unknown)}")
    return [writer.add_style(num_format=column_formats[h]) if h in column_formats else None for h in headers]


class _Records:
    """Classifies a record iterable: header names plus a fast way to turn records into rows."""

    def __init__(self, records: Iterable[Any]):
        it = iter(records)
        try:
            first = next(it)
        except StopIteration:
            self.headers: list[str] = []
            self.dicts = False
            self.rows: Iterator[Any] = iter(())
            return

        self.dicts = isinstance(first, Mapping)
        rest = itertools.chain([first], it)
        if self.dicts:
            self.headers = [str(k) for k in first.keys()]
            self.rows = rest
        elif hasattr(first, "_fields"):  # namedtuple: already a tuple
            self.headers = list(first._fields)
            self.rows = rest
        elif isinstance(first, (tuple, list)):
            self.headers = [f"Column{i + 1}" for i in range(len(first))]
            self.rows = rest
        else:
            names = _attribute_names(first)
            getter = operator.attrgetter(*names)
            self.headers = names
            if len(names) == 1:
                self.rows = ((getter(r),) for r in rest)
            else:
                self.rows = map(getter, rest)


def _attribute_names(obj: Any) -> list[str]:
    if dataclasses.is_dataclass(obj):
        return [f.name for f in dataclasses.fields(obj)]
    model_fields = getattr(type(obj), "model_fields", None)  # pydantic v2
    if isinstance(model_fields, Mapping):
        return list(model_fields)
    fields = getattr(type(obj), "__fields__", None)  # pydantic v1
    if isinstance(fields, Mapping):
        return list(fields)
    if hasattr(obj, "__dict__"):
        return [k for k in vars(obj) if not k.startswith("_")]
    slots = getattr(type(obj), "__slots__", None)
    if slots:
        return [s for s in ([slots] if isinstance(slots, str) else slots) if not s.startswith("_")]
    raise TypeError(f"cannot derive columns from {type(obj).__name__!r}; pass dicts, dataclasses, named tuples, pydantic models or tuples")


def _write_record_rows(ws: Worksheet, records: _Records, styles: Optional[list[Optional[Style]]], limit: Optional[int] = None) -> int:
    rows: Iterable[Any] = records.rows if limit is None else itertools.islice(records.rows, limit)
    if records.dicts:
        return ws.write_dicts(rows, records.headers, styles)
    if styles is None:
        return ws.write_rows(rows)
    # Per-column styles: wrap values that need one.
    return ws.write_rows(tuple(v if s is None else Styled(v, s) for v, s in zip(row, styles)) for row in rows)


# --------------------------------------------------------------------------------------------------
# Records


def write_records(
    writer: Writer,
    sheet_name: str,
    records: Iterable[Any],
    *,
    header: bool = True,
    header_style: Optional[Style] = None,
    column_formats: Optional[Mapping[str, str]] = None,
    autofilter: bool = True,
) -> Worksheet:
    """Adds a worksheet with one row per record.

    Records may be dicts, dataclasses, named tuples, pydantic models, plain objects or tuples; the
    first record defines the columns. With ``header`` the sheet gets a styled, frozen header row
    and (optionally) an auto filter. ``column_formats`` maps column names to Excel number formats,
    e.g. ``{"amount": "#,##0.00", "created": "yyyy-mm-dd"}``.
    """
    recs = _Records(records)
    ws = writer.add_worksheet(
        sheet_name,
        freeze_rows=1 if header and recs.headers else 0,
        column_widths=_estimate_widths(recs.headers),
    )
    if header and recs.headers:
        ws.write_row(recs.headers, style=header_style or _header_style(writer))
    styles = _column_styles(writer, recs.headers, column_formats)
    _write_record_rows(ws, recs, styles)
    if header and autofilter and recs.headers:
        ws.autofilter(1, 1, max(ws.row, 1), len(recs.headers))
    return ws


# --------------------------------------------------------------------------------------------------
# Data frames (pandas / polars)


def _frame_kind(df: Any) -> str:
    module = type(df).__module__
    if module.startswith("polars"):
        return "polars"
    if module.startswith("pandas"):
        return "pandas"
    raise TypeError(f"expected a pandas or polars DataFrame, got {type(df).__name__!r}")


def _pandas_column(series: Any) -> list[Any]:
    import pandas as pd

    dtype = series.dtype
    if dtype == object or isinstance(dtype, pd.api.extensions.ExtensionDtype) and not isinstance(dtype, pd.DatetimeTZDtype):
        # Nullable / extension / object columns may contain pd.NA: map every missing value to None.
        series = series.astype(object).where(series.notna(), None)
    return series.tolist()


def _frame_chunks(df: Any, index: bool, chunk_size: int) -> tuple[list[str], Iterator[list[list[Any]]]]:
    kind = _frame_kind(df)
    if kind == "pandas":
        if index:
            df = df.reset_index()
        headers = [str(c) for c in df.columns]

        def pandas_chunks() -> Iterator[list[list[Any]]]:
            for start in range(0, len(df), chunk_size):
                part = df.iloc[start : start + chunk_size]
                yield [_pandas_column(part.iloc[:, i]) for i in range(part.shape[1])]

        return headers, pandas_chunks()

    headers = [str(c) for c in df.columns]

    def polars_chunks() -> Iterator[list[list[Any]]]:
        for part in df.iter_slices(n_rows=chunk_size):
            yield [s.to_list() for s in part.get_columns()]

    return headers, polars_chunks()


def write_dataframe(
    writer: Writer,
    sheet_name: str,
    df: Any,
    *,
    index: bool = False,
    header: bool = True,
    header_style: Optional[Style] = None,
    column_formats: Optional[Mapping[str, str]] = None,
    autofilter: bool = True,
    chunk_size: int = 10_000,
) -> Worksheet:
    """Adds a worksheet containing a pandas or polars DataFrame.

    Data is converted column-wise in chunks of ``chunk_size`` rows, so memory stays bounded for
    large frames. NaN / None / NaT / pd.NA become empty cells; datetimes become native Excel dates.
    """
    headers, chunks = _frame_chunks(df, index, chunk_size)
    ws = writer.add_worksheet(sheet_name, freeze_rows=1 if header else 0, column_widths=_estimate_widths(headers))
    if header:
        ws.write_row(headers, style=header_style or _header_style(writer))
    styles = _column_styles(writer, headers, column_formats)
    for columns in chunks:
        ws.write_columns(columns, styles)
    if header and autofilter and headers:
        ws.autofilter(1, 1, max(ws.row, 1), len(headers))
    return ws


# --------------------------------------------------------------------------------------------------
# One-shot


def to_xlsx(data: Any, dest: Destination = None, *, sheet_name: str = "Sheet1", **options: Any) -> Optional[bytes]:
    """Writes a DataFrame or an iterable of records to ``dest`` (path or binary file); returns the
    bytes when ``dest`` is None. Extra keyword arguments go to :func:`write_dataframe` /
    :func:`write_records`."""
    writer = Writer(dest)
    try:
        if type(data).__module__.startswith(("pandas", "polars")):
            write_dataframe(writer, sheet_name, data, **options)
        else:
            write_records(writer, sheet_name, data, **options)
    except BaseException:
        if not writer.finished:
            writer.finish()
        raise
    return writer.finish()


# --------------------------------------------------------------------------------------------------
# Streaming (sync generators: Django StreamingHttpResponse, Flask, Starlette threadpool)


def _stream(build: Callable[[Writer], Iterator[None]], writer_options: Mapping[str, Any]) -> Iterator[bytes]:
    writer = Writer(None, **writer_options)
    for _ in build(writer):
        chunk = writer.drain()
        if chunk:
            yield chunk
    yield writer.finish() or b""


def stream_rows(
    rows: Iterable[Iterable[Any]],
    *,
    sheet_name: str = "Sheet1",
    header: Optional[Sequence[str]] = None,
    column_widths: Optional[Sequence[float]] = None,
    **writer_options: Any,
) -> Iterator[bytes]:
    """Yields an XLSX file in chunks while consuming ``rows`` lazily.

    Pass the result to ``StreamingResponse`` / ``StreamingHttpResponse`` / ``Response``. Memory
    stays constant regardless of the number of rows.
    """

    def build(writer: Writer) -> Iterator[None]:
        widths = list(column_widths) if column_widths else (_estimate_widths(header) if header else None)
        ws = writer.add_worksheet(sheet_name, freeze_rows=1 if header else 0, column_widths=widths)
        if header:
            ws.write_row(header, style=_header_style(writer))
        it = iter(rows)
        while True:
            written = ws.write_rows(itertools.islice(it, _BATCH_ROWS))
            yield
            if written < _BATCH_ROWS:
                break
        if header:
            ws.autofilter(1, 1, max(ws.row, 1), len(header))

    return _stream(build, writer_options)


def stream_records(
    records: Iterable[Any],
    *,
    sheet_name: str = "Sheet1",
    column_formats: Optional[Mapping[str, str]] = None,
    **writer_options: Any,
) -> Iterator[bytes]:
    """Like :func:`write_records`, but yields the file in chunks while consuming ``records`` lazily."""

    def build(writer: Writer) -> Iterator[None]:
        recs = _Records(records)
        ws = writer.add_worksheet(sheet_name, freeze_rows=1 if recs.headers else 0, column_widths=_estimate_widths(recs.headers))
        if recs.headers:
            ws.write_row(recs.headers, style=_header_style(writer))
        styles = _column_styles(writer, recs.headers, column_formats)
        while _write_record_rows(ws, recs, styles, _BATCH_ROWS) == _BATCH_ROWS:
            yield
        if recs.headers:
            ws.autofilter(1, 1, max(ws.row, 1), len(recs.headers))
        yield

    return _stream(build, writer_options)


def stream_dataframe(df: Any, *, sheet_name: str = "Sheet1", index: bool = False, column_formats: Optional[Mapping[str, str]] = None, **writer_options: Any) -> Iterator[bytes]:
    """Yields a DataFrame as an XLSX file in chunks."""

    def build(writer: Writer) -> Iterator[None]:
        headers, chunks = _frame_chunks(df, index, _BATCH_ROWS)
        ws = writer.add_worksheet(sheet_name, freeze_rows=1, column_widths=_estimate_widths(headers))
        ws.write_row(headers, style=_header_style(writer))
        styles = _column_styles(writer, headers, column_formats)
        for columns in chunks:
            ws.write_columns(columns, styles)
            yield
        ws.autofilter(1, 1, max(ws.row, 1), len(headers))
        yield

    return _stream(build, writer_options)


# --------------------------------------------------------------------------------------------------
# Async streaming (FastAPI / Starlette, async database drivers)


async def _abatches(source: Union[AsyncIterable[Any], Iterable[Any]], size: int) -> AsyncIterator[list[Any]]:
    batch: list[Any] = []
    if isinstance(source, AsyncIterable):
        async for item in source:
            batch.append(item)
            if len(batch) >= size:
                yield batch
                batch = []
    else:
        for item in source:
            batch.append(item)
            if len(batch) >= size:
                yield batch
                batch = []
    if batch:
        yield batch


async def astream_rows(
    rows: Union[AsyncIterable[Iterable[Any]], Iterable[Iterable[Any]]],
    *,
    sheet_name: str = "Sheet1",
    header: Optional[Sequence[str]] = None,
    column_widths: Optional[Sequence[float]] = None,
    **writer_options: Any,
) -> AsyncIterator[bytes]:
    """Async variant of :func:`stream_rows`; ``rows`` may be an async iterable (e.g. a database
    cursor). Work happens in small batches so the event loop is never blocked for long."""
    writer = Writer(None, **writer_options)
    widths = list(column_widths) if column_widths else (_estimate_widths(header) if header else None)
    ws = writer.add_worksheet(sheet_name, freeze_rows=1 if header else 0, column_widths=widths)
    if header:
        ws.write_row(header, style=_header_style(writer))
    async for batch in _abatches(rows, _BATCH_ROWS):
        ws.write_rows(batch)
        chunk = writer.drain()
        if chunk:
            yield chunk
    if header:
        ws.autofilter(1, 1, max(ws.row, 1), len(header))
    yield writer.finish() or b""


async def astream_records(
    records: Union[AsyncIterable[Any], Iterable[Any]],
    *,
    sheet_name: str = "Sheet1",
    column_formats: Optional[Mapping[str, str]] = None,
    **writer_options: Any,
) -> AsyncIterator[bytes]:
    """Async variant of :func:`stream_records` (dicts, dataclasses, pydantic models, ...)."""
    writer = Writer(None, **writer_options)
    ws: Optional[Worksheet] = None
    recs: Optional[_Records] = None
    styles: Optional[list[Optional[Style]]] = None
    async for batch in _abatches(records, _BATCH_ROWS):
        if recs is None:
            recs = _Records(batch)
            ws = writer.add_worksheet(sheet_name, freeze_rows=1 if recs.headers else 0, column_widths=_estimate_widths(recs.headers))
            if recs.headers:
                ws.write_row(recs.headers, style=_header_style(writer))
            styles = _column_styles(writer, recs.headers, column_formats)
        else:
            recs = _rebind(recs, batch)
        assert ws is not None
        _write_record_rows(ws, recs, styles)
        chunk = writer.drain()
        if chunk:
            yield chunk
    if ws is None:
        ws = writer.add_worksheet(sheet_name)
    elif recs is not None and recs.headers:
        ws.autofilter(1, 1, max(ws.row, 1), len(recs.headers))
    yield writer.finish() or b""


def _rebind(recs: _Records, batch: list[Any]) -> _Records:
    """Re-uses the column layout of the first batch for the next batch of records."""
    fresh = _Records(batch)
    if fresh.headers != recs.headers:
        if recs.dicts:
            # Dict records are looked up by the original keys; extra/missing keys are fine.
            fresh.headers = recs.headers
        else:
            raise XlsxError("all records must have the same fields")
    return fresh


# --------------------------------------------------------------------------------------------------
# Reading


def open_workbook(source: Source, *, max_shared_strings_bytes: int = 1 << 30, include_empty_rows: bool = False) -> Reader:
    """Opens an XLSX workbook for reading.

    ``source`` is a path, ``bytes`` or a binary file object (which is read into memory). Rows are
    streamed: memory stays bounded by the shared string table plus one row, however long the
    sheet. Use it as a context manager::

        with hypaxel.open_workbook("report.xlsx") as wb:
            print(wb.sheet_names)
            for row in wb.rows("Data"):
                ...

    Rows that the file stores without any value (a spacer row with a height, an empty hidden row)
    read as ``[]`` like missing rows. With ``include_empty_rows`` the row iterator also reports
    their ``outline_level``, ``hidden``, ``collapsed`` and ``height``, and such rows after the last
    value are returned too.
    """
    return Reader(source, max_shared_strings_bytes=max_shared_strings_bytes, include_empty_rows=include_empty_rows)


def iter_rows(source: Source, sheet: SheetRef = 0, *, skip_empty: bool = False, **options: Any) -> Iterator[list[Any]]:
    """Yields the rows of a sheet (name or 0-based index) as lists.

    ``row[i]`` is the value in column ``i + 1``; rows end at their last non-empty cell. Values are
    ``str``, ``int`` (integral numbers), ``float``, ``bool``, ``datetime.datetime`` (dates),
    ``datetime.time``, ``datetime.timedelta`` (``[h]:mm`` durations), error strings such as
    ``"#N/A"`` and ``None``. Formulas yield their cached result. Missing rows are yielded as ``[]``
    so that ``enumerate(rows, 1)`` gives Excel row numbers, unless ``skip_empty`` is set.
    """
    with Reader(source, **options) as reader:
        yield from reader.rows(sheet, skip_empty=skip_empty)


def read_rows(source: Source, sheet: SheetRef = 0, *, skip_empty: bool = False, **options: Any) -> list[list[Any]]:
    """Reads all rows of a sheet into a list. See :func:`iter_rows`."""
    return list(iter_rows(source, sheet, skip_empty=skip_empty, **options))


def _header_names(row: Sequence[Any]) -> list[str]:
    names: list[str] = []
    taken: set[str] = set()
    suffixes: dict[str, int] = {}
    for i, value in enumerate(row, 1):
        base = f"column_{i}" if value is None or value == "" else str(value)
        name = base
        if name in taken:
            suffix = suffixes.get(base, 1)
            while name in taken:
                suffix += 1
                name = f"{base}_{suffix}"
            suffixes[base] = suffix
        taken.add(name)
        names.append(name)
    return names


def _extend_names(names: list[str], width: int) -> None:
    taken = set(names)
    for i in range(len(names) + 1, width + 1):
        name = f"column_{i}"
        while name in taken:
            name += "_"
        taken.add(name)
        names.append(name)


def iter_records(source: Source, sheet: SheetRef = 0, *, header_row: int = 1, **options: Any) -> Iterator[dict[str, Any]]:
    """Yields one dict per row, keyed by the header row (default: row 1).

    Empty header cells are named ``column_<n>``, repeated names get a ``_2``, ``_3``, ... suffix
    and cells beyond the header get ``column_<n>`` keys. Rows without values are skipped; missing
    cells are ``None``.
    """
    if header_row < 1:
        raise ValueError("header_row is 1-based")
    names: Optional[list[str]] = None
    for number, row in enumerate(iter_rows(source, sheet, **options), 1):
        if number < header_row:
            continue
        if names is None:
            names = _header_names(row)
            continue
        if not row:
            continue
        if len(row) > len(names):
            _extend_names(names, len(row))
        record = dict(zip(names, row))
        for name in names[len(row):]:
            record[name] = None
        yield record


def read_records(source: Source, sheet: SheetRef = 0, *, header_row: int = 1, **options: Any) -> list[dict[str, Any]]:
    """Reads all rows below the header row as dicts. See :func:`iter_records`."""
    return list(iter_records(source, sheet, header_row=header_row, **options))


def read_dataframe(source: Source, sheet: SheetRef = 0, *, header: bool = True, library: str = "pandas", **options: Any) -> Any:
    """Reads a sheet into a pandas (default) or polars DataFrame.

    With ``header`` the first row supplies the column names (see :func:`iter_records` for how
    empty and repeated names are handled); otherwise columns are named ``column_1``, ... Rows
    without values are skipped. polars needs one type per column: a column mixing types (e.g.
    text and dates) becomes a text column.
    """
    if library not in ("pandas", "polars"):
        raise ValueError("library must be 'pandas' or 'polars'")
    rows = iter_rows(source, sheet, skip_empty=True, **options)
    names: list[str] = []
    if header:
        first = next(rows, None)
        names = _header_names(first) if first is not None else []
    data = list(rows)
    width = max([len(names), *map(len, data)])
    _extend_names(names, width)
    for row in data:
        if len(row) < width:
            row.extend([None] * (width - len(row)))
    if library == "pandas":
        import pandas as pd

        return pd.DataFrame(data, columns=names)
    import polars as pl

    columns = []
    for i, name in enumerate(names):
        values = [row[i] for row in data]
        try:
            columns.append(pl.Series(name, values, strict=False))
        except (TypeError, ValueError, pl.exceptions.PolarsError):
            # Mixed types (e.g. text and dates) cannot share a polars dtype.
            columns.append(pl.Series(name, [None if v is None else str(v) for v in values], dtype=pl.String))
    return pl.DataFrame(columns)
