//! Python bindings for the hypaxel XLSX writer.
//!
//! The Python-facing API lives in `python/hypaxel/`; this module provides the native core:
//! `Writer`, `Worksheet`, `Style`, `Formula`, `Styled` and fast row/column writers that convert
//! Python values to cells without intermediate allocations.

use std::fs::File;
use std::io::{self, BufWriter, Write};

use chrono::{NaiveDate, NaiveTime, TimeDelta};
use hypaxel::{
    Alignment, AutoFit, Blank, Border, BorderLine, BorderStyle, Color, HAlign, Options, RowOptions, Sheet, SheetOptions, StringStorage,
    Style as RsStyle, StyleId, Table, TableStyle, VAlign, XlsxWriter,
};
mod reader;

use pyo3::create_exception;
use pyo3::exceptions::{PyException, PyTypeError, PyValueError};
use pyo3::prelude::*;
use pyo3::types::{
    PyBool, PyBytes, PyDate, PyDateAccess, PyDateTime, PyDelta, PyDeltaAccess, PyDict, PyFloat, PyInt, PyList, PyString, PyTime,
    PyTimeAccess, PyTuple,
};

create_exception!(_hypaxel, XlsxError, PyException, "Raised when a workbook cannot be written.");

fn to_py(e: hypaxel::Error) -> PyErr {
    XlsxError::new_err(e.to_string())
}

// ------------------------------------------------------------------------------------------------
// Destinations

enum Sink {
    File(BufWriter<File>),
    Python(Py<PyAny>),
    Buffer(Vec<u8>),
}

impl Write for Sink {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            Sink::File(f) => f.write(buf),
            Sink::Buffer(v) => {
                v.extend_from_slice(buf);
                Ok(buf.len())
            }
            Sink::Python(obj) => Python::attach(|py| {
                obj.call_method1(py, "write", (PyBytes::new(py, buf),)).map(|_| buf.len()).map_err(|e| io::Error::other(e.to_string()))
            }),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self {
            Sink::File(f) => f.flush(),
            Sink::Buffer(_) => Ok(()),
            Sink::Python(obj) => Python::attach(|py| {
                if obj.bind(py).hasattr("flush").unwrap_or(false) {
                    obj.call_method0(py, "flush").map_err(|e| io::Error::other(e.to_string()))?;
                }
                Ok(())
            }),
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Value types

/// A cell format created by :meth:`Writer.add_style`. Only valid for the writer that created it.
#[pyclass(module = "hypaxel", frozen, from_py_object)]
#[derive(Clone, Copy)]
struct Style {
    id: StyleId,
}

#[pymethods]
impl Style {
    fn __repr__(&self) -> String {
        format!("<hypaxel.Style {}>", self.id.index())
    }
}

/// A formula such as ``Formula("SUM(A1:A9)")`` (leading ``=`` optional). Excel computes it on open.
#[pyclass(module = "hypaxel", frozen)]
struct Formula {
    #[pyo3(get)]
    formula: String,
}

#[pymethods]
impl Formula {
    #[new]
    fn new(formula: String) -> Self {
        Formula { formula }
    }

    fn __repr__(&self) -> String {
        format!("Formula({:?})", self.formula)
    }
}

/// A value with its own style, e.g. ``Styled(1234.5, money)``.
#[pyclass(module = "hypaxel", frozen)]
struct Styled {
    #[pyo3(get)]
    value: Py<PyAny>,
    #[pyo3(get)]
    style: Style,
}

#[pymethods]
impl Styled {
    #[new]
    fn new(value: Py<PyAny>, style: Style) -> Self {
        Styled { value, style }
    }
}

// ------------------------------------------------------------------------------------------------
// Python value → cell

#[derive(Clone, Copy)]
struct Conv {
    nan_as_empty: bool,
}

#[inline]
fn write_value(sheet: &mut Sheet<'_, Sink>, v: &Bound<'_, PyAny>, style: StyleId, conv: Conv) -> PyResult<()> {
    // Exact-type checks first: they are pointer comparisons.
    if v.is_none() {
        sheet.write_styled(Blank, style);
    } else if let Ok(s) = v.cast_exact::<PyString>() {
        sheet.write_styled(s.to_str()?, style);
    } else if let Ok(f) = v.cast_exact::<PyFloat>() {
        let f = f.value();
        if f.is_nan() && conv.nan_as_empty {
            sheet.write_styled(Blank, style);
        } else {
            sheet.write_styled(f, style);
        }
    } else if v.is_exact_instance_of::<PyBool>() {
        sheet.write_styled(v.is_truthy()?, style);
    } else if let Ok(i) = v.cast_exact::<PyInt>() {
        write_int(sheet, i, style)?;
    } else if let Ok(dt) = v.cast_exact::<PyDateTime>() {
        write_datetime(sheet, dt, style)?;
    } else if let Ok(d) = v.cast_exact::<PyDate>() {
        sheet.write_styled(naive_date(d)?, style);
    } else {
        write_value_slow(sheet, v, style, conv)?;
    }
    Ok(())
}

#[cold]
fn write_value_slow(sheet: &mut Sheet<'_, Sink>, v: &Bound<'_, PyAny>, style: StyleId, conv: Conv) -> PyResult<()> {
    if let Ok(styled) = v.cast::<Styled>() {
        let styled = styled.get();
        return write_value(sheet, styled.value.bind(v.py()), styled.style.id, conv);
    }
    if let Ok(formula) = v.cast::<Formula>() {
        sheet.write_styled(hypaxel::Formula(&formula.get().formula), style);
        return Ok(());
    }
    // Subclasses (str enums, pandas Timestamp / NaT, numpy.float64, ...).
    if let Ok(b) = v.cast::<PyBool>() {
        sheet.write_styled(b.is_true(), style);
    } else if let Ok(dt) = v.cast::<PyDateTime>() {
        // NaT compares unequal to itself, like NaN.
        if v.ne(v)? {
            sheet.write_styled(Blank, style);
        } else {
            write_datetime(sheet, dt, style)?;
        }
    } else if let Ok(d) = v.cast::<PyDate>() {
        sheet.write_styled(naive_date(d)?, style);
    } else if let Ok(t) = v.cast::<PyTime>() {
        sheet.write_styled(naive_time(t)?, style);
    } else if let Ok(d) = v.cast::<PyDelta>() {
        let delta = TimeDelta::days(d.get_days() as i64)
            + TimeDelta::seconds(d.get_seconds() as i64)
            + TimeDelta::microseconds(d.get_microseconds() as i64);
        sheet.write_styled(delta, style);
    } else if let Ok(s) = v.cast::<PyString>() {
        sheet.write_styled(s.to_str()?, style);
    } else if let Ok(i) = v.cast::<PyInt>() {
        write_int(sheet, i, style)?;
    } else if let Ok(f) = v.cast::<PyFloat>() {
        let f = f.value();
        if f.is_nan() && conv.nan_as_empty {
            sheet.write_styled(Blank, style);
        } else {
            sheet.write_styled(f, style);
        }
    } else if v.hasattr("__index__")? {
        // numpy integers and other integer-likes.
        let i = v.call_method0("__index__")?;
        write_int(sheet, i.cast::<PyInt>()?, style)?;
    } else if v.hasattr("__float__")? && !v.is_instance_of::<PyBytes>() {
        // decimal.Decimal, numpy floats, fractions.Fraction, ...
        let f: f64 = v.call_method0("__float__")?.extract()?;
        if f.is_nan() && conv.nan_as_empty {
            sheet.write_styled(Blank, style);
        } else {
            sheet.write_styled(f, style);
        }
    } else if v.is_instance_of::<PyList>() || v.is_instance_of::<PyTuple>() || v.is_instance_of::<PyDict>() {
        return Err(PyTypeError::new_err(format!("cannot write a {} into a single cell", v.get_type().name()?)));
    } else {
        let text = v.str()?;
        sheet.write_styled(text.to_str()?, style);
    }
    Ok(())
}

#[inline]
fn write_int(sheet: &mut Sheet<'_, Sink>, i: &Bound<'_, PyInt>, style: StyleId) -> PyResult<()> {
    match i.extract::<i64>() {
        Ok(n) => sheet.write_styled(n, style),
        // Beyond i64: Excel stores doubles anyway.
        Err(_) => sheet.write_styled(i.extract::<f64>()?, style),
    };
    Ok(())
}

fn naive_date(d: &impl PyDateAccess) -> PyResult<NaiveDate> {
    NaiveDate::from_ymd_opt(d.get_year(), d.get_month() as u32, d.get_day() as u32).ok_or_else(|| PyValueError::new_err("invalid date"))
}

fn naive_time(t: &impl PyTimeAccess) -> PyResult<NaiveTime> {
    NaiveTime::from_hms_micro_opt(t.get_hour() as u32, t.get_minute() as u32, t.get_second() as u32, t.get_microsecond())
        .ok_or_else(|| PyValueError::new_err("invalid time"))
}

/// Timezone-aware values are written using their local clock time (Excel has no time zones).
#[inline]
fn write_datetime(sheet: &mut Sheet<'_, Sink>, dt: &Bound<'_, PyDateTime>, style: StyleId) -> PyResult<()> {
    let date = naive_date(dt)?;
    let time = naive_time(dt)?;
    sheet.write_styled(date.and_time(time), style);
    Ok(())
}

// ------------------------------------------------------------------------------------------------
// Writer

/// Forward-only XLSX writer.
///
/// ``dest`` is a file path, a binary file-like object (anything with ``write(bytes)``), or ``None``
/// to keep output in memory and take it in chunks with :meth:`drain` (for streaming responses).
#[pyclass(module = "hypaxel")]
struct Writer {
    inner: Option<XlsxWriter<Sink>>,
    buffered: bool,
}

impl Writer {
    fn inner(&mut self) -> PyResult<&mut XlsxWriter<Sink>> {
        self.inner.as_mut().ok_or_else(|| XlsxError::new_err("the workbook is already finished"))
    }
}

fn parse_color(s: &str) -> PyResult<Color> {
    Color::hex(s).ok_or_else(|| PyValueError::new_err(format!("invalid color {s:?}; expected '#RRGGBB' or '#AARRGGBB'")))
}

/// Maps a style name as stored in the file: built-in names such as ``"TableStyleMedium2"``, any
/// other name as a custom style; ``None``, ``""`` or ``"none"`` for no table style.
fn parse_table_style(s: Option<&str>) -> PyResult<TableStyle> {
    Ok(match s {
        None => TableStyle::None,
        Some(name) if name.eq_ignore_ascii_case("none") => TableStyle::None,
        Some(name) => TableStyle::from_name(name),
    })
}

fn row_options(height: Option<f64>, style: StyleId, outline_level: u8, hidden: bool, collapsed: bool) -> RowOptions {
    RowOptions { height, style, outline_level, hidden, collapsed }
}

fn parse_border(s: &str) -> PyResult<BorderStyle> {
    Ok(match s {
        "none" => BorderStyle::None,
        "thin" => BorderStyle::Thin,
        "medium" => BorderStyle::Medium,
        "dashed" => BorderStyle::Dashed,
        "dotted" => BorderStyle::Dotted,
        "thick" => BorderStyle::Thick,
        "double" => BorderStyle::Double,
        "hair" => BorderStyle::Hair,
        "medium_dashed" => BorderStyle::MediumDashed,
        "dash_dot" => BorderStyle::DashDot,
        "medium_dash_dot" => BorderStyle::MediumDashDot,
        "dash_dot_dot" => BorderStyle::DashDotDot,
        "medium_dash_dot_dot" => BorderStyle::MediumDashDotDot,
        "slant_dash_dot" => BorderStyle::SlantDashDot,
        other => return Err(PyValueError::new_err(format!("unknown border style {other:?}"))),
    })
}

#[pymethods]
impl Writer {
    #[new]
    #[pyo3(signature = (dest=None, *, compression_level=1, shared_strings=false, flush_threshold=65536, date_1904=false))]
    fn new(
        py: Python<'_>,
        dest: Option<Bound<'_, PyAny>>,
        compression_level: u32,
        shared_strings: bool,
        flush_threshold: usize,
        date_1904: bool,
    ) -> PyResult<Self> {
        let sink = match dest {
            None => Sink::Buffer(Vec::with_capacity(flush_threshold * 2)),
            Some(d) if d.hasattr("write")? => Sink::Python(d.unbind()),
            Some(d) => {
                let path: std::path::PathBuf = d.extract()?;
                let file = py.detach(|| File::create(&path))?;
                Sink::File(BufWriter::with_capacity(1 << 16, file))
            }
        };
        let buffered = matches!(sink, Sink::Buffer(_));
        let options = Options::new()
            .compression_level(compression_level)
            .string_storage(if shared_strings { StringStorage::Shared } else { StringStorage::Inline })
            .flush_threshold(flush_threshold)
            .date_1904(date_1904);
        Ok(Writer { inner: Some(XlsxWriter::with_options(sink, options)), buffered })
    }

    /// Registers a cell format and returns a :class:`Style`. Identical formats share one style.
    #[pyo3(signature = (*, bold=false, italic=false, underline=false, strikethrough=false, font_name=None, font_size=None,
        font_color=None, fill=None, border=None, border_color=None, border_bottom=None, align=None, valign=None,
        wrap=false, indent=0, rotation=0, num_format=None))]
    #[allow(clippy::too_many_arguments)]
    fn add_style(
        &mut self,
        bold: bool,
        italic: bool,
        underline: bool,
        strikethrough: bool,
        font_name: Option<String>,
        font_size: Option<f64>,
        font_color: Option<&str>,
        fill: Option<&str>,
        border: Option<&str>,
        border_color: Option<&str>,
        border_bottom: Option<&str>,
        align: Option<&str>,
        valign: Option<&str>,
        wrap: bool,
        indent: u8,
        rotation: u8,
        num_format: Option<String>,
    ) -> PyResult<Style> {
        let mut style = RsStyle::new();
        if bold || italic || underline || strikethrough || font_name.is_some() || font_size.is_some() || font_color.is_some() {
            let mut font = hypaxel::Font { bold, italic, underline, strikethrough, ..Default::default() };
            if let Some(name) = font_name {
                font.name = name.into();
            }
            if let Some(size) = font_size {
                font = font.size(size);
            }
            if let Some(c) = font_color {
                font.color = Some(parse_color(c)?);
            }
            style = style.font(font);
        }
        if let Some(c) = fill {
            style = style.fill(parse_color(c)?);
        }
        let color = border_color.map(parse_color).transpose()?;
        if border.is_some() || border_bottom.is_some() {
            let mut b = match border {
                Some(s) => Border::all(parse_border(s)?, color),
                None => Border::default(),
            };
            if let Some(s) = border_bottom {
                b.bottom = BorderLine { style: parse_border(s)?, color };
            }
            style = style.border(b);
        }
        if align.is_some() || valign.is_some() || wrap || indent != 0 || rotation != 0 {
            let horizontal = match align.unwrap_or("general") {
                "general" => HAlign::General,
                "left" => HAlign::Left,
                "center" => HAlign::Center,
                "right" => HAlign::Right,
                "fill" => HAlign::Fill,
                "justify" => HAlign::Justify,
                "center_continuous" => HAlign::CenterContinuous,
                "distributed" => HAlign::Distributed,
                other => return Err(PyValueError::new_err(format!("unknown align {other:?}"))),
            };
            let vertical = match valign.unwrap_or("bottom") {
                "bottom" => VAlign::Bottom,
                "top" => VAlign::Top,
                "center" => VAlign::Center,
                "justify" => VAlign::Justify,
                "distributed" => VAlign::Distributed,
                other => return Err(PyValueError::new_err(format!("unknown valign {other:?}"))),
            };
            style = style.alignment(Alignment { horizontal, vertical, wrap_text: wrap, shrink_to_fit: false, indent, rotation });
        }
        if let Some(code) = num_format {
            style = style.num_format(code);
        }
        Ok(Style { id: self.inner()?.add_style(&style) })
    }

    /// Adds a worksheet and makes it the current one; the previous worksheet is completed.
    #[pyo3(signature = (name, *, freeze_rows=0, freeze_columns=0, column_widths=None, hidden=false, show_grid_lines=true, tab_color=None,
        autofit=false, autofit_sample_rows=1000, autofit_max_width=255.0, row_outline_levels=0, outline_summary_above=false))]
    #[allow(clippy::too_many_arguments)]
    fn add_worksheet(
        slf: Bound<'_, Self>,
        name: &str,
        freeze_rows: u32,
        freeze_columns: u32,
        column_widths: Option<Vec<f64>>,
        hidden: bool,
        show_grid_lines: bool,
        tab_color: Option<&str>,
        autofit: bool,
        autofit_sample_rows: u32,
        autofit_max_width: f64,
        row_outline_levels: u8,
        outline_summary_above: bool,
    ) -> PyResult<Worksheet> {
        let mut options =
            SheetOptions::new().freeze_rows(freeze_rows).freeze_columns(freeze_columns).row_outline_levels(row_outline_levels);
        if autofit {
            options = options.autofit(AutoFit::new().sample_rows(autofit_sample_rows).max_width(autofit_max_width));
        }
        if outline_summary_above {
            options = options.outline_summary_above();
        }
        if let Some(widths) = column_widths {
            options = options.column_widths(&widths);
        }
        if hidden {
            options = options.hidden();
        }
        if !show_grid_lines {
            options = options.hide_grid_lines();
        }
        if let Some(c) = tab_color {
            options = options.tab_color(parse_color(c)?);
        }
        let index = {
            let mut this = slf.borrow_mut();
            let inner = this.inner()?;
            inner.add_worksheet_with(name, &options).map_err(to_py)?;
            inner.worksheet_count()
        };
        Ok(Worksheet { writer: slf.unbind(), index, name: name.to_owned() })
    }

    /// Returns (and removes) the compressed bytes produced so far. Only for in-memory writers
    /// (``dest=None``); chunks are produced roughly every ``flush_threshold`` bytes.
    fn drain<'py>(&mut self, py: Python<'py>) -> PyResult<Bound<'py, PyBytes>> {
        if !self.buffered {
            return Err(XlsxError::new_err("drain() is only available for in-memory writers (dest=None)"));
        }
        let Sink::Buffer(buf) = self.inner()?.get_mut() else { unreachable!() };
        let bytes = PyBytes::new(py, buf);
        buf.clear();
        Ok(bytes)
    }

    /// Completes the workbook. In-memory writers return the remaining bytes; otherwise ``None``.
    fn finish<'py>(&mut self, py: Python<'py>) -> PyResult<Option<Bound<'py, PyBytes>>> {
        let inner = self.inner.take().ok_or_else(|| XlsxError::new_err("the workbook is already finished"))?;
        match inner.finish().map_err(to_py)? {
            Sink::Buffer(buf) => Ok(Some(PyBytes::new(py, &buf))),
            Sink::File(mut f) => {
                f.flush()?;
                Ok(None)
            }
            Sink::Python(_) => Ok(None),
        }
    }

    /// True once :meth:`finish` has been called.
    #[getter]
    fn finished(&self) -> bool {
        self.inner.is_none()
    }

    fn __enter__(slf: Py<Self>) -> Py<Self> {
        slf
    }

    #[pyo3(signature = (exc_type=None, _exc=None, _tb=None))]
    fn __exit__(
        &mut self,
        py: Python<'_>,
        exc_type: Option<Bound<'_, PyAny>>,
        _exc: Option<Bound<'_, PyAny>>,
        _tb: Option<Bound<'_, PyAny>>,
    ) -> PyResult<bool> {
        if exc_type.is_none() && self.inner.is_some() {
            if self.buffered {
                return Err(XlsxError::new_err("in-memory writers must be finished explicitly with finish() to obtain the bytes"));
            }
            self.finish(py)?;
        } else {
            self.inner = None;
        }
        Ok(false)
    }
}

// ------------------------------------------------------------------------------------------------
// Worksheet

/// The worksheet being written. Obtained from :meth:`Writer.add_worksheet`; becomes read-only once
/// another worksheet is added.
#[pyclass(module = "hypaxel")]
struct Worksheet {
    writer: Py<Writer>,
    index: usize,
    #[pyo3(get)]
    name: String,
}

impl Worksheet {
    fn with_sheet<R>(&self, py: Python<'_>, f: impl FnOnce(&mut Sheet<'_, Sink>) -> PyResult<R>) -> PyResult<R> {
        let mut writer = self.writer.bind(py).try_borrow_mut()?;
        let inner = writer.inner()?;
        if inner.worksheet_count() != self.index {
            return Err(XlsxError::new_err(format!("worksheet {:?} is complete; worksheets are written one at a time", self.name)));
        }
        let mut sheet = inner.current_worksheet().ok_or_else(|| XlsxError::new_err("no open worksheet"))?;
        f(&mut sheet)
    }
}

fn style_of(style: Option<&Bound<'_, Style>>) -> StyleId {
    style.map_or(StyleId::DEFAULT, |s| s.get().id)
}

#[pymethods]
impl Worksheet {
    /// 1-based number of the last written row (0 before the first row).
    #[getter]
    fn row(&self, py: Python<'_>) -> PyResult<u32> {
        self.with_sheet(py, |s| Ok(s.row()))
    }

    /// Writes one row. ``values`` is any iterable; ``style`` applies to every cell without its own.
    /// ``height``, ``outline_level``, ``hidden`` and ``collapsed`` set row properties.
    #[pyo3(signature = (values, style=None, *, height=None, outline_level=0, hidden=false, collapsed=false))]
    #[allow(clippy::too_many_arguments)]
    fn write_row(
        &self,
        py: Python<'_>,
        values: &Bound<'_, PyAny>,
        style: Option<&Bound<'_, Style>>,
        height: Option<f64>,
        outline_level: u8,
        hidden: bool,
        collapsed: bool,
    ) -> PyResult<()> {
        let style = style_of(style);
        let conv = Conv { nan_as_empty: false };
        let row = row_options(height, StyleId::DEFAULT, outline_level, hidden, collapsed);
        self.with_sheet(py, |s| {
            if row != RowOptions::default() {
                s.begin_row_with(&row).map_err(to_py)?;
            }
            write_iterable(s, values, style, conv)?;
            s.end_row().map_err(to_py)
        })
    }

    /// Writes many rows; each item is an iterable of cell values. Returns the number of rows written.
    #[pyo3(signature = (rows, style=None))]
    fn write_rows(&self, py: Python<'_>, rows: &Bound<'_, PyAny>, style: Option<&Bound<'_, Style>>) -> PyResult<usize> {
        let style = style_of(style);
        let conv = Conv { nan_as_empty: false };
        self.with_sheet(py, |s| {
            let mut count = 0;
            for row in rows.try_iter()? {
                write_iterable(s, &row?, style, conv)?;
                s.end_row().map_err(to_py)?;
                count += 1;
            }
            Ok(count)
        })
    }

    /// Writes rows from column lists (``columns[c][r]``) — the fast path for data frames. ``styles``
    /// optionally gives one :class:`Style` (or ``None``) per column. NaN becomes an empty cell unless
    /// ``nan_as_empty=False``.
    #[pyo3(signature = (columns, styles=None, nan_as_empty=true))]
    fn write_columns(
        &self,
        py: Python<'_>,
        columns: Vec<Bound<'_, PyList>>,
        styles: Option<Vec<Option<Bound<'_, Style>>>>,
        nan_as_empty: bool,
    ) -> PyResult<usize> {
        let rows = columns.first().map_or(0, |c| c.len());
        if columns.iter().any(|c| c.len() != rows) {
            return Err(PyValueError::new_err("all columns must have the same length"));
        }
        let styles: Vec<StyleId> = match styles {
            Some(list) => {
                if list.len() != columns.len() {
                    return Err(PyValueError::new_err("styles must have one entry per column"));
                }
                list.iter().map(|s| style_of(s.as_ref())).collect()
            }
            None => vec![StyleId::DEFAULT; columns.len()],
        };
        let conv = Conv { nan_as_empty };
        self.with_sheet(py, |s| {
            for r in 0..rows {
                for (column, &style) in columns.iter().zip(&styles) {
                    let value = column.get_item(r)?;
                    write_value(s, &value, style, conv)?;
                }
                s.end_row().map_err(to_py)?;
            }
            Ok(rows)
        })
    }

    /// Writes one row per mapping, taking values for ``keys`` in order (missing keys → empty cells).
    #[pyo3(signature = (records, keys, styles=None))]
    fn write_dicts(
        &self,
        py: Python<'_>,
        records: &Bound<'_, PyAny>,
        keys: Vec<Bound<'_, PyString>>,
        styles: Option<Vec<Option<Bound<'_, Style>>>>,
    ) -> PyResult<usize> {
        let styles: Vec<StyleId> = match styles {
            Some(list) => list.iter().map(|s| style_of(s.as_ref())).collect(),
            None => vec![StyleId::DEFAULT; keys.len()],
        };
        let conv = Conv { nan_as_empty: false };
        self.with_sheet(py, |s| {
            let mut count = 0;
            for record in records.try_iter()? {
                let record = record?;
                let dict = record.cast::<PyDict>().map_err(|_| PyTypeError::new_err("write_dicts expects dict records"))?;
                for (key, &style) in keys.iter().zip(styles.iter().chain(std::iter::repeat(&StyleId::DEFAULT))) {
                    match dict.get_item(key)? {
                        Some(value) => write_value(s, &value, style, conv)?,
                        None => {
                            s.skip_cells(1);
                        }
                    }
                }
                s.end_row().map_err(to_py)?;
                count += 1;
            }
            Ok(count)
        })
    }

    /// Writes a single value as the next cell of the current row (starting a row if needed).
    #[pyo3(signature = (value, style=None))]
    fn write(&self, py: Python<'_>, value: &Bound<'_, PyAny>, style: Option<&Bound<'_, Style>>) -> PyResult<()> {
        let style = style_of(style);
        self.with_sheet(py, |s| write_value(s, value, style, Conv { nan_as_empty: false }))
    }

    /// Ends the current row (or leaves an empty row if none is open).
    fn end_row(&self, py: Python<'_>) -> PyResult<()> {
        self.with_sheet(py, |s| s.end_row().map_err(to_py))
    }

    /// Starts the next row explicitly, e.g. to set its height (points), row style, outline level or
    /// hidden state. A following :meth:`write_row` fills this row rather than starting another.
    #[pyo3(signature = (height=None, style=None, *, outline_level=0, hidden=false, collapsed=false))]
    fn begin_row(
        &self,
        py: Python<'_>,
        height: Option<f64>,
        style: Option<&Bound<'_, Style>>,
        outline_level: u8,
        hidden: bool,
        collapsed: bool,
    ) -> PyResult<()> {
        let row = row_options(height, style_of(style), outline_level, hidden, collapsed);
        self.with_sheet(py, |s| s.begin_row_with(&row).map(drop).map_err(to_py))
    }

    /// Leaves ``count`` cells of the current row empty.
    #[pyo3(signature = (count=1))]
    fn skip_cells(&self, py: Python<'_>, count: u32) -> PyResult<()> {
        self.with_sheet(py, |s| {
            s.skip_cells(count);
            Ok(())
        })
    }

    /// Leaves ``count`` empty rows.
    #[pyo3(signature = (count=1))]
    fn skip_rows(&self, py: Python<'_>, count: u32) -> PyResult<()> {
        self.with_sheet(py, |s| {
            s.skip_rows(count);
            Ok(())
        })
    }

    /// Merges a range (1-based, inclusive).
    fn merge_cells(&self, py: Python<'_>, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> PyResult<()> {
        self.with_sheet(py, |s| s.merge_cells(first_row, first_col, last_row, last_col).map(drop).map_err(to_py))
    }

    /// Adds filter drop-downs to a range whose first row is the header (1-based, inclusive).
    fn autofilter(&self, py: Python<'_>, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> PyResult<()> {
        self.with_sheet(py, |s| s.autofilter(first_row, first_col, last_row, last_col).map(drop).map_err(to_py))
    }

    /// Turns a range (1-based, inclusive) into an Excel table. With a header row, ``columns`` must
    /// list the header texts exactly as written in the range's first row. ``totals_row`` exists so
    /// that a table from :meth:`Reader.tables` can be passed back; totals rows cannot be written.
    #[pyo3(signature = (first_row, first_col, last_row, last_col, *, columns=None, name=None, style=Some("TableStyleMedium2"),
        header_row=true, autofilter=true, row_stripes=true, column_stripes=false, first_column=false, last_column=false,
        totals_row=false))]
    #[allow(clippy::too_many_arguments)]
    fn add_table(
        &self,
        py: Python<'_>,
        first_row: u32,
        first_col: u32,
        last_row: u32,
        last_col: u32,
        columns: Option<Vec<String>>,
        name: Option<String>,
        style: Option<&str>,
        header_row: bool,
        autofilter: bool,
        row_stripes: bool,
        column_stripes: bool,
        first_column: bool,
        last_column: bool,
        totals_row: bool,
    ) -> PyResult<()> {
        let mut table = Table::new(first_row, first_col, last_row, last_col)
            .columns(columns.unwrap_or_default())
            .style(parse_table_style(style)?)
            .header_row(header_row)
            .autofilter(autofilter)
            .row_stripes(row_stripes)
            .column_stripes(column_stripes)
            .highlight_first_column(first_column)
            .highlight_last_column(last_column);
        if let Some(name) = name {
            table = table.name(name);
        }
        table.totals_row = totals_row;
        self.with_sheet(py, |s| s.add_table(&table).map(drop).map_err(to_py))
    }

    fn __repr__(&self) -> String {
        format!("<hypaxel.Worksheet {:?}>", self.name)
    }
}

#[inline]
fn write_iterable(s: &mut Sheet<'_, Sink>, values: &Bound<'_, PyAny>, style: StyleId, conv: Conv) -> PyResult<()> {
    // Lists and tuples are indexed directly; anything else goes through the iterator protocol.
    if let Ok(list) = values.cast_exact::<PyList>() {
        for v in list.iter() {
            write_value(s, &v, style, conv)?;
        }
    } else if let Ok(tuple) = values.cast_exact::<PyTuple>() {
        for v in tuple.iter() {
            write_value(s, &v, style, conv)?;
        }
    } else if values.is_instance_of::<PyString>() || values.is_instance_of::<PyBytes>() {
        return Err(PyTypeError::new_err("a row must be an iterable of values, not a string"));
    } else {
        for v in values.try_iter()? {
            write_value(s, &v?, style, conv)?;
        }
    }
    Ok(())
}

/// Returns the column letters for a 1-based column index (1 → "A", 27 → "AA").
#[pyfunction]
fn column_name(column: u32) -> PyResult<String> {
    if !(1..=hypaxel::MAX_COLUMNS).contains(&column) {
        return Err(PyValueError::new_err("column must be within 1..=16384"));
    }
    Ok(hypaxel::column_name(column))
}

#[pymodule]
fn _hypaxel(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_class::<Writer>()?;
    m.add_class::<Worksheet>()?;
    m.add_class::<Style>()?;
    m.add_class::<Formula>()?;
    m.add_class::<Styled>()?;
    m.add_class::<reader::Reader>()?;
    m.add_class::<reader::RowIterator>()?;
    m.add_function(wrap_pyfunction!(column_name, m)?)?;
    m.add("XlsxError", m.py().get_type::<XlsxError>())?;
    m.add("CONTENT_TYPE", hypaxel::CONTENT_TYPE)?;
    m.add("MAX_ROWS", hypaxel::MAX_ROWS)?;
    m.add("MAX_COLUMNS", hypaxel::MAX_COLUMNS)?;
    Ok(())
}
