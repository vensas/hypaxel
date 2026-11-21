//! Python bindings for the streaming XLSX reader: `Reader` and its row iterator.

use std::fs::File;
use std::io::{self, BufReader, Cursor, Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::Arc;

use hypaxel::{DateKind, ExcelDateTime, ReadOptions, Rows, SheetKind, SheetLayout, Value, Visibility, XlsxReader};
use pyo3::exceptions::{PyTypeError, PyValueError};
use pyo3::prelude::*;
use pyo3::types::{PyBytes, PyDateTime, PyDelta, PyDict, PyList, PyString, PyTime};

use crate::XlsxError;

/// 2^53: integral floats below this are exact and are returned as `int`.
const MAX_EXACT_INT: f64 = 9_007_199_254_740_992.0;

fn read_err(e: hypaxel::Error) -> PyErr {
    match e {
        hypaxel::Error::Io(io) => io.into(),
        other => XlsxError::new_err(other.to_string()),
    }
}

#[derive(Clone)]
struct SharedBytes(Arc<[u8]>);

impl AsRef<[u8]> for SharedBytes {
    fn as_ref(&self) -> &[u8] {
        &self.0
    }
}

/// A cursor over a file shared with other cursors. Reads are positioned, so each sheet iterator
/// seeks independently while all of them read the file that was opened, even if its path is later
/// replaced or deleted.
struct FileCursor {
    file: Arc<File>,
    pos: u64,
}

impl FileCursor {
    #[cfg(unix)]
    fn read_at(&self, buf: &mut [u8]) -> io::Result<usize> {
        std::os::unix::fs::FileExt::read_at(&*self.file, buf, self.pos)
    }

    #[cfg(windows)]
    fn read_at(&self, buf: &mut [u8]) -> io::Result<usize> {
        std::os::windows::fs::FileExt::seek_read(&*self.file, buf, self.pos)
    }
}

impl Read for FileCursor {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.read_at(buf)?;
        self.pos += n as u64;
        Ok(n)
    }
}

impl Seek for FileCursor {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let (base, offset) = match pos {
            SeekFrom::Start(n) => (n, 0),
            SeekFrom::Current(d) => (self.pos, d),
            SeekFrom::End(d) => (self.file.metadata()?.len(), d),
        };
        self.pos = base
            .checked_add_signed(offset)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "seek before the start of the file"))?;
        Ok(self.pos)
    }
}

/// Where the workbook comes from. Each sheet iterator gets its own cursor, so several sheets can
/// be read at once.
#[derive(Clone)]
enum Origin {
    File(Arc<File>),
    Bytes(SharedBytes),
}

enum Source {
    File(BufReader<FileCursor>),
    Bytes(Cursor<SharedBytes>),
}

impl Origin {
    fn open(&self) -> Source {
        match self {
            Origin::File(f) => Source::File(BufReader::with_capacity(64 * 1024, FileCursor { file: f.clone(), pos: 0 })),
            Origin::Bytes(b) => Source::Bytes(Cursor::new(b.clone())),
        }
    }
}

impl Read for Source {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Source::File(f) => f.read(buf),
            Source::Bytes(c) => c.read(buf),
        }
    }
}

impl Seek for Source {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        match self {
            Source::File(f) => f.seek(pos),
            Source::Bytes(c) => c.seek(pos),
        }
    }
}

fn origin_from(py: Python<'_>, source: &Bound<'_, PyAny>) -> PyResult<Origin> {
    if let Ok(bytes) = source.cast::<PyBytes>() {
        return Ok(Origin::Bytes(SharedBytes(Arc::from(bytes.as_bytes()))));
    }
    if let Ok(path) = source.extract::<PathBuf>() {
        return Ok(Origin::File(Arc::new(File::open(path)?)));
    }
    if source.hasattr("read")? {
        let data = source.call_method0("read")?;
        let bytes = data.cast::<PyBytes>().map_err(|_| PyTypeError::new_err("the file object must be opened in binary mode ('rb')"))?;
        return Ok(Origin::Bytes(SharedBytes(Arc::from(bytes.as_bytes()))));
    }
    if let Ok(view) = source.call_method0("__bytes__") {
        let bytes = view.cast::<PyBytes>()?;
        return Ok(Origin::Bytes(SharedBytes(Arc::from(bytes.as_bytes()))));
    }
    let _ = py;
    Err(PyTypeError::new_err("source must be a path, bytes, or a binary file object"))
}

/// Reads an XLSX workbook. See :func:`hypaxel.open_workbook`.
#[pyclass(module = "hypaxel")]
pub(crate) struct Reader {
    /// Cleared by `close()`, which releases the file; open row iterators keep their own handle.
    origin: Option<Origin>,
    reader: Option<XlsxReader<Source>>,
}

impl Reader {
    fn reader(&mut self) -> PyResult<&mut XlsxReader<Source>> {
        self.reader.as_mut().ok_or_else(|| PyValueError::new_err("the reader is closed"))
    }

    fn sheet_or_first(&mut self, sheet: Option<&Bound<'_, PyAny>>) -> PyResult<usize> {
        match sheet {
            Some(s) if !s.is_none() => self.sheet_index(s),
            _ => Ok(0),
        }
    }

    fn sheet_index(&mut self, sheet: &Bound<'_, PyAny>) -> PyResult<usize> {
        let reader = self.reader()?;
        if let Ok(index) = sheet.extract::<isize>() {
            let count = reader.sheets().len() as isize;
            let resolved = if index < 0 { index + count } else { index };
            if !(0..count).contains(&resolved) {
                return Err(XlsxError::new_err(format!("no worksheet at index {index}; the workbook has {count}")));
            }
            return Ok(resolved as usize);
        }
        let name: String = sheet.extract().map_err(|_| PyTypeError::new_err("sheet must be a name or a 0-based index"))?;
        let sheets = reader.sheets();
        sheets
            .iter()
            .position(|s| s.name() == name)
            .or_else(|| sheets.iter().position(|s| s.name().to_lowercase() == name.to_lowercase()))
            .ok_or_else(|| {
                let names: Vec<&str> = sheets.iter().map(|s| s.name()).collect();
                XlsxError::new_err(format!("no worksheet {name:?}; the workbook has {names:?}"))
            })
    }
}

#[pymethods]
impl Reader {
    #[new]
    #[pyo3(signature = (source, *, max_shared_strings_bytes = 1 << 30))]
    fn new(py: Python<'_>, source: &Bound<'_, PyAny>, max_shared_strings_bytes: u64) -> PyResult<Self> {
        let origin = origin_from(py, source)?;
        let options = ReadOptions::new().max_shared_strings_bytes(max_shared_strings_bytes);
        let handle = origin.open();
        let reader = py.detach(|| XlsxReader::with_options(handle, &options)).map_err(read_err)?;
        Ok(Reader { origin: Some(origin), reader: Some(reader) })
    }

    /// Sheet names in workbook order, including hidden sheets.
    #[getter]
    fn sheet_names(&mut self) -> PyResult<Vec<String>> {
        Ok(self.reader()?.sheet_names().map(str::to_string).collect())
    }

    /// One dict per sheet: ``{"name": ..., "visible": "visible" | "hidden" | "very_hidden", "kind": "worksheet" | "chartsheet" | "other"}``.
    #[getter]
    fn sheets<'py>(&mut self, py: Python<'py>) -> PyResult<Vec<Bound<'py, PyDict>>> {
        self.reader()?
            .sheets()
            .iter()
            .map(|s| {
                let d = PyDict::new(py);
                d.set_item("name", s.name())?;
                d.set_item(
                    "visible",
                    match s.visibility() {
                        Visibility::Hidden => "hidden",
                        Visibility::VeryHidden => "very_hidden",
                        _ => "visible",
                    },
                )?;
                d.set_item(
                    "kind",
                    match s.kind() {
                        SheetKind::Worksheet => "worksheet",
                        SheetKind::Chartsheet => "chartsheet",
                        _ => "other",
                    },
                )?;
                Ok(d)
            })
            .collect()
    }

    /// True if the workbook uses the 1904 date system.
    #[getter]
    fn date_1904(&mut self) -> PyResult<bool> {
        Ok(self.reader()?.is_1904())
    }

    /// The Excel tables of a sheet (name or 0-based index), one dict per table with the keys that
    /// :meth:`Worksheet.add_table` takes. Tables are read on each call; reading rows does not load them.
    #[pyo3(signature = (sheet = None))]
    fn tables<'py>(&mut self, py: Python<'py>, sheet: Option<&Bound<'py, PyAny>>) -> PyResult<Vec<Bound<'py, PyDict>>> {
        let index = self.sheet_or_first(sheet)?;
        let reader = self.reader()?;
        let tables = py.detach(|| reader.tables_at(index)).map_err(read_err)?;
        tables
            .iter()
            .map(|t| {
                let d = PyDict::new(py);
                d.set_item("name", t.name.as_deref())?;
                d.set_item("first_row", t.first_row)?;
                d.set_item("first_col", t.first_column)?;
                d.set_item("last_row", t.last_row)?;
                d.set_item("last_col", t.last_column)?;
                d.set_item("columns", t.columns.iter().map(|c| c.as_ref()).collect::<Vec<&str>>())?;
                d.set_item("style", t.style.name())?;
                d.set_item("header_row", t.header_row)?;
                d.set_item("autofilter", t.autofilter)?;
                d.set_item("row_stripes", t.row_stripes)?;
                d.set_item("column_stripes", t.column_stripes)?;
                d.set_item("first_column", t.first_column_highlight)?;
                d.set_item("last_column", t.last_column_highlight)?;
                Ok(d)
            })
            .collect()
    }

    /// Iterates over the rows of a sheet (name or 0-based index) as lists.
    #[pyo3(signature = (sheet = None, *, skip_empty = false))]
    fn rows(&mut self, py: Python<'_>, sheet: Option<&Bound<'_, PyAny>>, skip_empty: bool) -> PyResult<RowIterator> {
        let index = self.sheet_or_first(sheet)?;
        let handle = self.origin.as_ref().ok_or_else(|| PyValueError::new_err("the reader is closed"))?.open();
        let reader = self.reader()?;
        let rows = py.detach(|| reader.with_source(handle)?.into_worksheet_at(index)).map_err(read_err)?;
        Ok(RowIterator {
            rows,
            skip_empty,
            emitted: 0,
            pending: None,
            current: RowProps::default(),
            strings: (0..STRING_CACHE_SLOTS).map(|_| None).collect(),
            done: false,
        })
    }

    fn close(&mut self) {
        self.origin = None;
        self.reader = None;
    }

    fn __enter__(slf: Py<Self>) -> Py<Self> {
        slf
    }

    #[pyo3(signature = (*_args))]
    fn __exit__(&mut self, _args: &Bound<'_, pyo3::types::PyTuple>) {
        self.close();
    }

    fn __repr__(&mut self) -> String {
        match self.reader.as_ref() {
            Some(r) => format!("<hypaxel.Reader sheets={:?}>", r.sheet_names().collect::<Vec<_>>()),
            None => "<hypaxel.Reader closed>".to_string(),
        }
    }
}

#[derive(Clone, Copy, Default)]
struct RowProps {
    outline_level: u8,
    hidden: bool,
    collapsed: bool,
    height: Option<f64>,
}

/// Rows of one sheet as lists. Created by :meth:`Reader.rows`.
///
/// ``row_number``, ``outline_level``, ``hidden``, ``collapsed`` and ``height`` describe the row
/// returned last.
#[pyclass(module = "hypaxel")]
pub(crate) struct RowIterator {
    rows: Rows<Source>,
    skip_empty: bool,
    emitted: u32,
    pending: Option<(u32, Py<PyList>, RowProps)>,
    current: RowProps,
    /// Recently converted shared strings, by table index modulo the slot count.
    strings: Vec<Option<(usize, Py<PyString>)>>,
    done: bool,
}

fn datetime_to_py<'py>(py: Python<'py>, d: ExcelDateTime) -> PyResult<Option<Bound<'py, PyAny>>> {
    if d.kind() == DateKind::Duration {
        let Some(micros) = d.to_micros() else { return Ok(None) };
        let days = micros.div_euclid(86_400_000_000);
        let rest = micros.rem_euclid(86_400_000_000);
        let Ok(days) = i32::try_from(days) else { return Ok(None) };
        return Ok(Some(PyDelta::new(py, days, (rest / 1_000_000) as i32, (rest % 1_000_000) as i32, true)?.into_any()));
    }
    let Some(dt) = d.to_naive_datetime() else { return Ok(None) };
    use chrono::{Datelike, Timelike};
    let micro = dt.nanosecond() / 1000;
    if d.kind() == DateKind::Time && d.serial() < 1.0 {
        return Ok(Some(PyTime::new(py, dt.hour() as u8, dt.minute() as u8, dt.second() as u8, micro, None)?.into_any()));
    }
    Ok(Some(
        PyDateTime::new(
            py,
            dt.year(),
            dt.month() as u8,
            dt.day() as u8,
            dt.hour() as u8,
            dt.minute() as u8,
            dt.second() as u8,
            micro,
            None,
        )?
        .into_any(),
    ))
}

fn number_to_py(py: Python<'_>, n: f64) -> PyResult<Bound<'_, PyAny>> {
    if n.fract() == 0.0 && n.abs() < MAX_EXACT_INT {
        Ok((n as i64).into_pyobject(py)?.into_any())
    } else {
        Ok(n.into_pyobject(py)?.into_any())
    }
}

/// Slots in the shared string cache. A small direct-mapped cache keeps repeated strings (names,
/// categories) from being converted over and over while memory stays bounded: unique strings
/// simply replace each other.
const STRING_CACHE_SLOTS: usize = 4096;

fn convert_row<'py>(
    py: Python<'py>,
    row: &hypaxel::Row<'_>,
    strings: &mut [Option<(usize, Py<PyString>)>],
) -> PyResult<Bound<'py, PyList>> {
    let width = row.last_column() as usize;
    let list = PyList::new(py, (0..width).map(|_| py.None()))?;
    for cell in row.cells() {
        let value = match cell.value() {
            Value::String(s) => match cell.shared_string_index() {
                Some(i) => {
                    let slot = &mut strings[i % STRING_CACHE_SLOTS];
                    match slot {
                        Some((cached, string)) if *cached == i => string.bind(py).clone().into_any(),
                        _ => {
                            let string = PyString::new(py, s);
                            *slot = Some((i, string.clone().unbind()));
                            string.into_any()
                        }
                    }
                }
                None => PyString::new(py, s).into_any(),
            },
            Value::Number(n) => number_to_py(py, n)?,
            Value::Bool(b) => pyo3::types::PyBool::new(py, b).to_owned().into_any(),
            Value::DateTime(d) => match datetime_to_py(py, d)? {
                Some(v) => v,
                None => number_to_py(py, d.serial())?,
            },
            Value::Error(e) => PyString::new(py, e).into_any(),
            _ => continue,
        };
        list.set_item(cell.column() as usize - 1, value)?;
    }
    Ok(list)
}

#[pymethods]
impl RowIterator {
    fn __iter__(slf: PyRef<'_, Self>) -> PyRef<'_, Self> {
        slf
    }

    fn __next__(&mut self, py: Python<'_>) -> PyResult<Option<Py<PyList>>> {
        if let Some((number, _, props)) = &self.pending {
            let (number, props) = (*number, *props);
            if self.skip_empty || self.emitted + 1 >= number {
                self.emitted = number;
                self.current = props;
                return Ok(self.pending.take().map(|(_, list, _)| list));
            }
            self.emitted += 1;
            self.current = RowProps::default();
            return Ok(Some(PyList::empty(py).unbind()));
        }
        if self.done {
            return Ok(None);
        }
        let Some(row) = self.rows.next_row().map_err(read_err)? else {
            self.done = true;
            return Ok(None);
        };
        let number = row.number();
        let props =
            RowProps { outline_level: row.outline_level(), hidden: row.is_hidden(), collapsed: row.is_collapsed(), height: row.height() };
        let list = convert_row(py, &row, &mut self.strings)?.unbind();
        self.pending = Some((number, list, props));
        self.__next__(py)
    }

    /// 1-based number of the row returned last (0 before the first row).
    #[getter]
    fn row_number(&self) -> u32 {
        self.emitted
    }

    /// Outline (grouping) level of the row returned last, 0-7.
    #[getter]
    fn outline_level(&self) -> u8 {
        self.current.outline_level
    }

    #[getter]
    fn hidden(&self) -> bool {
        self.current.hidden
    }

    /// True if the row returned last is the summary row of a collapsed group.
    #[getter]
    fn collapsed(&self) -> bool {
        self.current.collapsed
    }

    /// Height in points of the row returned last, or None if the row stores none.
    #[getter]
    fn height(&self) -> Option<f64> {
        self.current.height
    }

    /// The column widths the sheet defines as ``(first_col, last_col, width)`` tuples (1-based,
    /// inclusive). Available before the first row is read.
    #[getter]
    fn column_widths(&mut self) -> PyResult<Vec<(u32, u32, f64)>> {
        Ok(self.layout()?.column_widths().iter().map(|c| (c.first_column(), c.last_column(), c.width())).collect())
    }

    /// The width of a 1-based column, or None if the sheet leaves it at the default.
    fn column_width(&mut self, column: u32) -> PyResult<Option<f64>> {
        Ok(self.layout()?.column_width(column))
    }

    /// True if summary rows sit above their groups.
    #[getter]
    fn outline_summary_above(&mut self) -> PyResult<bool> {
        Ok(self.layout()?.outline_summary_above())
    }

    /// The deepest row outline level the sheet declares (0 without grouping).
    #[getter]
    fn row_outline_levels(&mut self) -> PyResult<u8> {
        Ok(self.layout()?.row_outline_levels())
    }
}

impl RowIterator {
    fn layout(&mut self) -> PyResult<&SheetLayout> {
        self.rows.layout().map_err(read_err)
    }
}
