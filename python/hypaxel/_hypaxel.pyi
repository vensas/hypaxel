from os import PathLike
from typing import IO, Any, Iterable, Literal, Mapping, Optional, Sequence, Union, final

CONTENT_TYPE: str
MAX_ROWS: int
MAX_COLUMNS: int

BorderName = Literal[
    "none", "thin", "medium", "dashed", "dotted", "thick", "double", "hair", "medium_dashed",
    "dash_dot", "medium_dash_dot", "dash_dot_dot", "medium_dash_dot_dot", "slant_dash_dot",
]
HAlignName = Literal["general", "left", "center", "right", "fill", "justify", "center_continuous", "distributed"]
VAlignName = Literal["bottom", "top", "center", "justify", "distributed"]

class XlsxError(Exception): ...

@final
class Style:
    """A cell format created by :meth:`Writer.add_style`."""

@final
class Formula:
    formula: str
    def __init__(self, formula: str) -> None: ...

@final
class Styled:
    value: Any
    style: Style
    def __init__(self, value: Any, style: Style) -> None: ...

@final
class Writer:
    def __init__(
        self,
        dest: Union[str, PathLike[str], IO[bytes], None] = None,
        *,
        compression_level: int = 1,
        shared_strings: bool = False,
        flush_threshold: int = 65536,
        date_1904: bool = False,
    ) -> None: ...
    def add_style(
        self,
        *,
        bold: bool = False,
        italic: bool = False,
        underline: bool = False,
        strikethrough: bool = False,
        font_name: Optional[str] = None,
        font_size: Optional[float] = None,
        font_color: Optional[str] = None,
        fill: Optional[str] = None,
        border: Optional[BorderName] = None,
        border_color: Optional[str] = None,
        border_bottom: Optional[BorderName] = None,
        align: Optional[HAlignName] = None,
        valign: Optional[VAlignName] = None,
        wrap: bool = False,
        indent: int = 0,
        rotation: int = 0,
        num_format: Optional[str] = None,
    ) -> Style: ...
    def add_worksheet(
        self,
        name: str,
        *,
        freeze_rows: int = 0,
        freeze_columns: int = 0,
        column_widths: Optional[Sequence[float]] = None,
        hidden: bool = False,
        show_grid_lines: bool = True,
        tab_color: Optional[str] = None,
        autofit: bool = False,
        autofit_sample_rows: int = 1000,
        autofit_max_width: float = 255.0,
        row_outline_levels: int = 0,
        outline_summary_above: bool = False,
    ) -> Worksheet: ...
    def drain(self) -> bytes: ...
    def finish(self) -> Optional[bytes]: ...
    @property
    def finished(self) -> bool: ...
    def __enter__(self) -> Writer: ...
    def __exit__(self, exc_type: Any = None, exc: Any = None, tb: Any = None) -> bool: ...

@final
class Worksheet:
    @property
    def name(self) -> str: ...
    @property
    def row(self) -> int: ...
    def write_row(
        self,
        values: Iterable[Any],
        style: Optional[Style] = None,
        *,
        height: Optional[float] = None,
        outline_level: int = 0,
        hidden: bool = False,
        collapsed: bool = False,
    ) -> None: ...
    def write_rows(self, rows: Iterable[Iterable[Any]], style: Optional[Style] = None) -> int: ...
    def write_columns(self, columns: Sequence[list[Any]], styles: Optional[Sequence[Optional[Style]]] = None, nan_as_empty: bool = True) -> int: ...
    def write_dicts(self, records: Iterable[Mapping[str, Any]], keys: Sequence[str], styles: Optional[Sequence[Optional[Style]]] = None) -> int: ...
    def write(self, value: Any, style: Optional[Style] = None) -> None: ...
    def end_row(self) -> None: ...
    def begin_row(
        self,
        height: Optional[float] = None,
        style: Optional[Style] = None,
        *,
        outline_level: int = 0,
        hidden: bool = False,
        collapsed: bool = False,
    ) -> None: ...
    def skip_cells(self, count: int = 1) -> None: ...
    def skip_rows(self, count: int = 1) -> None: ...
    def merge_cells(self, first_row: int, first_col: int, last_row: int, last_col: int) -> None: ...
    def autofilter(self, first_row: int, first_col: int, last_row: int, last_col: int) -> None: ...
    def add_table(
        self,
        first_row: int,
        first_col: int,
        last_row: int,
        last_col: int,
        *,
        columns: Optional[Sequence[str]] = None,
        name: Optional[str] = None,
        style: Optional[str] = "TableStyleMedium2",
        header_row: bool = True,
        autofilter: bool = True,
        row_stripes: bool = True,
        column_stripes: bool = False,
        first_column: bool = False,
        last_column: bool = False,
    ) -> None: ...

def column_name(column: int) -> str: ...
