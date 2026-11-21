//! The reading half of the engine: a workbook handle and a row stream that encodes rows into a
//! compact binary batch, decoded by `RowDecoder` in `node/ts/core.ts`. As with writing, a whole
//! batch of rows crosses the JavaScript boundary in one call.
//!
//! Batch format (little-endian). The first batch of a sheet holds its layout; in the batches after
//! it one row follows another:
//!
//! - `LAYOUT` `u8` flags (bit 0: summary rows above their groups), `u8` row outline levels, `u32`
//!   count, then per column width `u32` first column, `u32` last column, `f64` width, `u8` hidden
//! - `ROW_PROPS`, only before a row that is grouped, hidden, collapsed or has a height: `u8` flags
//!   (bits 0–2 outline level, bit 3 hidden, bit 4 collapsed, bit 5 a height follows), `f64` height
//! - `ROW` `u32` row number, `u32` width (the 1-based column of the last cell with a value),
//!   followed by cells until `width` columns are covered:
//! - `SKIP` `u32` count of empty columns
//! - `NUMBER` `f64`
//! - `STRING` / `ERROR` `u32` byte length, UTF-8 bytes
//! - `TRUE`, `FALSE`
//! - `DATE` `f64` wall-clock milliseconds since 1970-01-01, as if the wall clock were UTC
//! - `DURATION` `f64` elapsed milliseconds
//! - `SHARED_NEW` `u32` shared string index, `u32` byte length, UTF-8 bytes
//! - `SHARED` `u32` shared string index, for a string sent earlier in the same slot
//! - `COPY` `u32` 0-based column of an earlier cell in the same row holding the same shared string
//!
//! Shared strings go through a direct-mapped cache of [`STRING_SLOTS`] slots that the decoder
//! mirrors: a string is sent once per slot occupancy, so repeated text (names, categories) is
//! neither copied nor decoded again, while memory stays bounded. When strings that share a slot
//! alternate within a row, `COPY` refers back to the earlier cell, so a row never carries the same
//! shared string twice and its size stays bounded by the shared string table.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, BufReader, Cursor, Read, Seek, SeekFrom};
use std::path::Path;
use std::sync::{Arc, Mutex};

use hypaxel::{DateKind, ExcelDateTime, ReadOptions, Row, Rows, SheetKind, SheetLayout, Table, Value, Visibility, XlsxReader};

use crate::{Error, Result, failed, invalid};

const TAG_ROW: u8 = 1;
const TAG_SKIP: u8 = 2;
const TAG_NUMBER: u8 = 3;
const TAG_STRING: u8 = 4;
const TAG_TRUE: u8 = 5;
const TAG_FALSE: u8 = 6;
const TAG_DATE: u8 = 7;
const TAG_DURATION: u8 = 8;
const TAG_ERROR: u8 = 9;
const TAG_SHARED_NEW: u8 = 10;
const TAG_SHARED: u8 = 11;
const TAG_COPY: u8 = 12;
const TAG_LAYOUT: u8 = 13;
const TAG_ROW_PROPS: u8 = 14;

const PROP_HIDDEN: u8 = 1 << 3;
const PROP_COLLAPSED: u8 = 1 << 4;
const PROP_HEIGHT: u8 = 1 << 5;

/// Slots in the shared string cache. Must equal `STRING_SLOTS` in `node/ts/core.ts`.
pub const STRING_SLOTS: usize = 4096;

/// A batch is complete once it holds this many bytes (it may exceed it by one row).
pub const BATCH_BYTES: usize = 64 * 1024;

/// Days from 0001-01-01 (day 1, as counted by [`ExcelDateTime::to_days_and_micros`]) to 1970-01-01.
const UNIX_EPOCH_DAYS: i64 = 719_163;
const MILLIS_PER_DAY: i64 = 86_400_000;

fn read_error(e: hypaxel::Error) -> Error {
    match e {
        hypaxel::Error::Io(io) if io.kind() == io::ErrorKind::NotFound => failed(format!("file not found: {io}")),
        other => failed(other.to_string()),
    }
}

#[derive(Clone)]
struct SharedBytes(Arc<Vec<u8>>);

impl AsRef<[u8]> for SharedBytes {
    fn as_ref(&self) -> &[u8] {
        &self.0
    }
}

/// A cursor over a file shared with other cursors. Reads are positioned, so each row stream seeks
/// independently while all of them read the file that was opened, even if its path is later
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

    #[cfg(not(any(unix, windows)))]
    fn read_at(&self, _buf: &mut [u8]) -> io::Result<usize> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "reading files is not supported on this platform"))
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

/// Where the workbook comes from. Each row stream gets its own cursor, so several sheets can be
/// read at once.
#[derive(Clone)]
enum Origin {
    File(Arc<File>),
    Bytes(SharedBytes),
}

impl Origin {
    fn open(&self) -> Source {
        match self {
            Origin::File(f) => Source::File(BufReader::with_capacity(64 * 1024, FileCursor { file: f.clone(), pos: 0 })),
            Origin::Bytes(b) => Source::Bytes(Cursor::new(b.clone())),
        }
    }
}

/// A seekable handle to the workbook.
enum Source {
    File(BufReader<FileCursor>),
    Bytes(Cursor<SharedBytes>),
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

#[derive(Debug, Default, Clone)]
#[cfg_attr(feature = "serde", derive(serde::Deserialize), serde(rename_all = "camelCase", default))]
pub struct ReadConfig {
    pub max_shared_strings_bytes: Option<f64>,
    pub include_empty_rows: Option<bool>,
}

impl ReadConfig {
    fn options(&self) -> Result<ReadOptions> {
        let mut options = ReadOptions::new().include_empty_rows(self.include_empty_rows.unwrap_or(false));
        if let Some(bytes) = self.max_shared_strings_bytes {
            if !(bytes.is_finite() && bytes >= 0.0) {
                return Err(invalid("maxSharedStringsBytes must be a non-negative number"));
            }
            options = options.max_shared_strings_bytes(bytes as u64);
        }
        Ok(options)
    }
}

/// A sheet listed in the workbook.
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize))]
pub struct SheetEntry {
    pub name: String,
    /// `visible`, `hidden` or `veryHidden`.
    pub visible: &'static str,
    /// `worksheet`, `chartsheet` or `other`.
    pub kind: &'static str,
}

/// An Excel table of a worksheet, with the fields that `Engine::add_table` takes.
#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize), serde(rename_all = "camelCase"))]
pub struct TableEntry {
    pub name: String,
    pub first_row: u32,
    pub first_column: u32,
    pub last_row: u32,
    pub last_column: u32,
    pub columns: Vec<String>,
    /// `TableStyleMedium2` etc.; `none` for no built-in table style.
    pub style: String,
    pub header_row: bool,
    pub autofilter: bool,
    pub row_stripes: bool,
    pub column_stripes: bool,
    pub highlight_first_column: bool,
    pub highlight_last_column: bool,
    pub totals_row: bool,
}

impl From<Table> for TableEntry {
    fn from(t: Table) -> Self {
        TableEntry {
            name: t.name.unwrap_or_default(),
            first_row: t.first_row,
            first_column: t.first_column,
            last_row: t.last_row,
            last_column: t.last_column,
            columns: t.columns.into_iter().map(|c| c.into_owned()).collect(),
            style: t.style.name().unwrap_or_else(|| "none".to_string()),
            header_row: t.header_row,
            autofilter: t.autofilter,
            row_stripes: t.row_stripes,
            column_stripes: t.column_stripes,
            highlight_first_column: t.first_column_highlight,
            highlight_last_column: t.last_column_highlight,
            totals_row: t.totals_row,
        }
    }
}

/// An open workbook: its structure, and a shared handle from which row streams are opened.
pub struct Workbook {
    origin: Origin,
    reader: Arc<Mutex<XlsxReader<Source>>>,
    sheets: Vec<SheetEntry>,
    date1904: bool,
}

impl Workbook {
    /// Opens a workbook file.
    pub fn open_path(path: impl AsRef<Path>, config: &ReadConfig) -> Result<Self> {
        let file = File::open(path).map_err(|e| read_error(e.into()))?;
        Self::open(Origin::File(Arc::new(file)), config)
    }

    /// Opens a workbook held in memory. A `Vec` is taken over without copying.
    pub fn open_bytes(bytes: impl Into<Vec<u8>>, config: &ReadConfig) -> Result<Self> {
        Self::open(Origin::Bytes(SharedBytes(Arc::new(bytes.into()))), config)
    }

    fn open(origin: Origin, config: &ReadConfig) -> Result<Self> {
        let options = config.options()?;
        let reader = XlsxReader::with_options(origin.open(), &options).map_err(read_error)?;
        let sheets = reader
            .sheets()
            .iter()
            .map(|s| SheetEntry {
                name: s.name().to_string(),
                visible: match s.visibility() {
                    Visibility::Hidden => "hidden",
                    Visibility::VeryHidden => "veryHidden",
                    _ => "visible",
                },
                kind: match s.kind() {
                    SheetKind::Worksheet => "worksheet",
                    SheetKind::Chartsheet => "chartsheet",
                    _ => "other",
                },
            })
            .collect();
        let date1904 = reader.is_1904();
        Ok(Workbook { origin, reader: Arc::new(Mutex::new(reader)), sheets, date1904 })
    }

    pub fn sheets(&self) -> &[SheetEntry] {
        &self.sheets
    }

    pub fn date1904(&self) -> bool {
        self.date1904
    }

    fn check_index(&self, index: u32) -> Result<()> {
        let count = self.sheets.len();
        if index as usize >= count {
            return Err(invalid(format!("no worksheet at index {index}; the workbook has {count}")));
        }
        Ok(())
    }

    /// The Excel tables of the sheet at a 0-based index. Their parts are read on each call.
    pub fn tables(&self, index: u32) -> Result<Vec<TableEntry>> {
        self.check_index(index)?;
        let mut reader = self.reader.lock().map_err(|_| failed("the workbook is unusable after an earlier failure"))?;
        let tables = reader.tables_at(index as usize).map_err(read_error)?;
        Ok(tables.into_iter().map(TableEntry::from).collect())
    }

    /// A stream over the rows of the sheet at a 0-based index. The sheet is opened, and the shared
    /// string table loaded, on the first call to [`RowStream::next_batch`].
    pub fn rows(&self, index: u32) -> Result<RowStream> {
        self.check_index(index)?;
        Ok(RowStream {
            state: StreamState::Pending { origin: self.origin.clone(), reader: self.reader.clone(), index: index as usize },
            slots: vec![u32::MAX; STRING_SLOTS].into_boxed_slice(),
            row_shared: Vec::new(),
            row_columns: HashMap::new(),
        })
    }
}

enum StreamState {
    Pending { origin: Origin, reader: Arc<Mutex<XlsxReader<Source>>>, index: usize },
    Open(Box<Rows<Source>>),
    Done,
}

/// The rows of one sheet, encoded in batches.
pub struct RowStream {
    state: StreamState,
    /// The shared string index held by each cache slot, `u32::MAX` when empty.
    slots: Box<[u32]>,
    /// Shared string index and 0-based column of each shared string cell in the current row.
    row_shared: Vec<(u32, u32)>,
    /// First column of each shared string in the current row, built from `row_shared` on the
    /// first slot collision in the row.
    row_columns: HashMap<u32, u32>,
}

fn put_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn put_f64(out: &mut Vec<u8>, tag: u8, value: f64) {
    out.push(tag);
    out.extend_from_slice(&value.to_le_bytes());
}

fn put_str(out: &mut Vec<u8>, value: &str) {
    put_u32(out, value.len() as u32);
    out.extend_from_slice(value.as_bytes());
}

fn put_datetime(out: &mut Vec<u8>, d: ExcelDateTime) {
    if d.kind() == DateKind::Duration {
        match d.to_micros() {
            Some(micros) => put_f64(out, TAG_DURATION, (micros / 1000) as f64),
            None => put_f64(out, TAG_NUMBER, d.serial()),
        }
        return;
    }
    match d.to_days_and_micros() {
        Some((days, micros)) => put_f64(out, TAG_DATE, ((days - UNIX_EPOCH_DAYS) * MILLIS_PER_DAY + (micros / 1000) as i64) as f64),
        None => put_f64(out, TAG_NUMBER, d.serial()),
    }
}

fn put_layout(out: &mut Vec<u8>, layout: &SheetLayout) {
    out.push(TAG_LAYOUT);
    out.push(layout.outline_summary_above() as u8);
    out.push(layout.row_outline_levels());
    put_u32(out, layout.column_widths().len() as u32);
    for column in layout.column_widths() {
        put_u32(out, column.first_column());
        put_u32(out, column.last_column());
        out.extend_from_slice(&column.width().to_le_bytes());
        out.push(column.is_hidden() as u8);
    }
}

fn put_row_props(out: &mut Vec<u8>, row: &Row<'_>) {
    let mut flags = row.outline_level();
    if row.is_hidden() {
        flags |= PROP_HIDDEN;
    }
    if row.is_collapsed() {
        flags |= PROP_COLLAPSED;
    }
    if row.height().is_some() {
        flags |= PROP_HEIGHT;
    }
    if flags == 0 {
        return;
    }
    out.push(TAG_ROW_PROPS);
    out.push(flags);
    if let Some(height) = row.height() {
        out.extend_from_slice(&height.to_le_bytes());
    }
}

impl RowStream {
    /// Opens the sheet and writes its layout to `out`.
    fn open(&mut self, out: &mut Vec<u8>) -> Result<()> {
        let StreamState::Pending { origin, reader, index } = &self.state else { return Ok(()) };
        let handle = origin.open();
        let mut rows = {
            let mut reader = reader.lock().map_err(|_| failed("the workbook is unusable after an earlier failure"))?;
            reader.with_source(handle).and_then(|r| r.into_worksheet_at(*index)).map_err(read_error)?
        };
        put_layout(out, rows.layout().map_err(read_error)?);
        self.state = StreamState::Open(Box::new(rows));
        Ok(())
    }

    /// Appends rows to `out` until it holds at least [`BATCH_BYTES`] or the sheet ends. Returns
    /// false once the sheet has ended and nothing was appended. The first batch holds only the
    /// layout, so that it arrives even if the first row cannot be read.
    pub fn next_batch(&mut self, out: &mut Vec<u8>) -> Result<bool> {
        let start = out.len();
        if matches!(self.state, StreamState::Pending { .. }) {
            if let Err(e) = self.open(out) {
                self.state = StreamState::Done;
                return Err(e);
            }
            return Ok(true);
        }
        let StreamState::Open(rows) = &mut self.state else { return Ok(false) };
        while out.len() - start < BATCH_BYTES {
            let row = match rows.next_row() {
                Ok(Some(row)) => row,
                Ok(None) => {
                    self.state = StreamState::Done;
                    break;
                }
                Err(e) => {
                    self.state = StreamState::Done;
                    return Err(read_error(e));
                }
            };
            put_row_props(out, &row);
            out.push(TAG_ROW);
            put_u32(out, row.number());
            put_u32(out, row.last_column());
            self.row_shared.clear();
            self.row_columns.clear();
            let mut indexed = false;
            let mut column = 1;
            for cell in row.cells() {
                if cell.column() > column {
                    out.push(TAG_SKIP);
                    put_u32(out, cell.column() - column);
                }
                column = cell.column() + 1;
                match cell.value() {
                    Value::String(s) => match cell.shared_string_index() {
                        Some(i) => {
                            let (index, at) = (i as u32, cell.column() - 1);
                            let slot = &mut self.slots[i % STRING_SLOTS];
                            if *slot == index {
                                out.push(TAG_SHARED);
                                put_u32(out, index);
                            } else {
                                if !indexed {
                                    indexed = true;
                                    for &(earlier, col) in &self.row_shared {
                                        self.row_columns.entry(earlier).or_insert(col);
                                    }
                                }
                                if let Some(&earlier) = self.row_columns.get(&index) {
                                    out.push(TAG_COPY);
                                    put_u32(out, earlier);
                                } else {
                                    *slot = index;
                                    out.push(TAG_SHARED_NEW);
                                    put_u32(out, index);
                                    put_str(out, s);
                                }
                            }
                            if indexed {
                                self.row_columns.entry(index).or_insert(at);
                            } else {
                                self.row_shared.push((index, at));
                            }
                        }
                        None => {
                            out.push(TAG_STRING);
                            put_str(out, s);
                        }
                    },
                    Value::Number(n) => put_f64(out, TAG_NUMBER, n),
                    Value::Bool(b) => out.push(if b { TAG_TRUE } else { TAG_FALSE }),
                    Value::DateTime(d) => put_datetime(out, d),
                    Value::Error(e) => {
                        out.push(TAG_ERROR);
                        put_str(out, e);
                    }
                    _ => {
                        out.push(TAG_SKIP);
                        put_u32(out, 1);
                    }
                }
            }
        }
        Ok(out.len() > start)
    }

    /// Stops reading and releases the file handle.
    pub fn close(&mut self) {
        self.state = StreamState::Done;
    }

    /// True once the sheet has been read to the end, reading failed or the stream was closed.
    pub fn done(&self) -> bool {
        matches!(self.state, StreamState::Done)
    }
}

#[cfg(test)]
mod tests {
    use chrono::NaiveDate;
    use hypaxel::{Options, StringStorage, XlsxWriter};

    use super::*;

    fn workbook(shared: bool) -> Vec<u8> {
        let options = Options::new().string_storage(if shared { StringStorage::Shared } else { StringStorage::Inline });
        let mut writer = XlsxWriter::with_options(Vec::new(), options);
        let mut sheet = writer.add_worksheet("Data").unwrap();
        let when = NaiveDate::from_ymd_opt(2024, 5, 1).unwrap().and_hms_opt(13, 30, 15).unwrap();
        sheet.write("text");
        sheet.write(1.5);
        sheet.write(true);
        sheet.skip_cells(1);
        sheet.write(when);
        sheet.end_row().unwrap();
        sheet.skip_rows(2);
        sheet.write("text");
        sheet.end_row().unwrap();
        writer.add_worksheet_with("Hidden", &hypaxel::SheetOptions::new().hidden()).unwrap();
        writer.finish().unwrap()
    }

    /// Bytes of the layout record of a sheet without column widths.
    const EMPTY_LAYOUT: usize = 7;

    struct Cursor<'a> {
        data: &'a [u8],
        pos: usize,
    }

    impl Cursor<'_> {
        fn u8(&mut self) -> u8 {
            self.pos += 1;
            self.data[self.pos - 1]
        }
        fn u32(&mut self) -> u32 {
            self.pos += 4;
            u32::from_le_bytes(self.data[self.pos - 4..self.pos].try_into().unwrap())
        }
        fn f64(&mut self) -> f64 {
            self.pos += 8;
            f64::from_le_bytes(self.data[self.pos - 8..self.pos].try_into().unwrap())
        }
        fn str(&mut self) -> String {
            let len = self.u32() as usize;
            self.pos += len;
            String::from_utf8(self.data[self.pos - len..self.pos].to_vec()).unwrap()
        }
    }

    fn batch(bytes: Vec<u8>) -> Vec<u8> {
        let book = Workbook::open_bytes(bytes, &ReadConfig::default()).unwrap();
        let mut rows = book.rows(0).unwrap();
        let out = read_all(&mut rows).unwrap();
        assert!(rows.done());
        assert!(!rows.next_batch(&mut out.clone()).unwrap());
        out
    }

    /// The layout batch and every row batch of a sheet.
    fn read_all(rows: &mut RowStream) -> Result<Vec<u8>> {
        let mut out = Vec::new();
        while rows.next_batch(&mut out)? {}
        Ok(out)
    }

    #[test]
    fn reads_the_opened_file_after_its_path_is_replaced_or_deleted() {
        let dir = std::env::temp_dir().join(format!("hypaxel-engine-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("book.xlsx");
        std::fs::write(&path, workbook(true)).unwrap();
        let book = Workbook::open_path(&path, &ReadConfig::default()).unwrap();
        let out = read_all(&mut book.rows(0).unwrap()).unwrap();
        assert!(out.len() > EMPTY_LAYOUT);

        let replacement = dir.join("other.xlsx");
        std::fs::write(&replacement, b"not a workbook").unwrap();
        std::fs::rename(&replacement, &path).unwrap();
        let replaced = read_all(&mut book.rows(0).unwrap());
        std::fs::remove_dir_all(&dir).unwrap();
        let deleted = read_all(&mut book.rows(0).unwrap());

        assert_eq!(replaced.unwrap(), out);
        assert_eq!(deleted.unwrap(), out);
    }

    #[test]
    fn sends_a_shared_string_once_per_row_when_slots_collide() {
        let long = |c: char| c.to_string().repeat(1000);
        let options = Options::new().string_storage(StringStorage::Shared);
        let mut writer = XlsxWriter::with_options(Vec::new(), options);
        let mut sheet = writer.add_worksheet("Data").unwrap();
        for i in 0..=STRING_SLOTS {
            match i {
                0 => sheet.write(long('a').as_str()),
                STRING_SLOTS => sheet.write(long('b').as_str()),
                _ => sheet.write(format!("s{i}").as_str()),
            };
        }
        sheet.end_row().unwrap();
        for i in 0..2000 {
            sheet.write(long(if i % 2 == 0 { 'a' } else { 'b' }).as_str());
        }
        sheet.end_row().unwrap();
        let bytes = writer.finish().unwrap();

        let book = Workbook::open_bytes(bytes, &ReadConfig::default()).unwrap();
        let mut rows = book.rows(0).unwrap();
        let mut out = Vec::new();
        while rows.next_batch(&mut out).unwrap() {}
        assert!(out.len() < 100_000, "{} bytes", out.len());
    }

    #[test]
    fn lists_sheets() {
        let book = Workbook::open_bytes(workbook(false), &ReadConfig::default()).unwrap();
        assert_eq!(book.sheets()[0], SheetEntry { name: "Data".into(), visible: "visible", kind: "worksheet" });
        assert_eq!(book.sheets()[1].visible, "hidden");
        assert!(!book.date1904());
        assert!(matches!(book.rows(2), Err(Error::Invalid(m)) if m.contains("has 2")));
    }

    #[test]
    fn encodes_every_value_with_inline_strings() {
        let out = batch(workbook(false));
        let mut c = Cursor { data: &out, pos: 0 };
        assert_eq!((c.u8(), c.u8(), c.u8(), c.u32()), (TAG_LAYOUT, 0, 0, 0));
        assert_eq!((c.u8(), c.u32(), c.u32()), (TAG_ROW, 1, 5));
        assert_eq!((c.u8(), c.str()), (TAG_STRING, "text".to_string()));
        assert_eq!((c.u8(), c.f64()), (TAG_NUMBER, 1.5));
        assert_eq!(c.u8(), TAG_TRUE);
        assert_eq!((c.u8(), c.u32()), (TAG_SKIP, 1));
        let expected = NaiveDate::from_ymd_opt(2024, 5, 1).unwrap().and_hms_opt(13, 30, 15).unwrap().and_utc().timestamp_millis();
        assert_eq!((c.u8(), c.f64()), (TAG_DATE, expected as f64));
        assert_eq!((c.u8(), c.u32(), c.u32()), (TAG_ROW, 4, 1));
        assert_eq!((c.u8(), c.str()), (TAG_STRING, "text".to_string()));
        assert_eq!(c.pos, out.len());
    }

    #[test]
    fn sends_each_shared_string_once() {
        let out = batch(workbook(true));
        let mut c = Cursor { data: &out, pos: EMPTY_LAYOUT };
        assert_eq!((c.u8(), c.u32(), c.u32()), (TAG_ROW, 1, 5));
        assert_eq!((c.u8(), c.u32(), c.str()), (TAG_SHARED_NEW, 0, "text".to_string()));
        c.pos = out.len() - 1 - 4 - 1 - 8;
        assert_eq!((c.u8(), c.u32(), c.u32()), (TAG_ROW, 4, 1));
        assert_eq!((c.u8(), c.u32()), (TAG_SHARED, 0));
    }

    #[test]
    fn encodes_durations_and_out_of_range_dates() {
        let mut writer = XlsxWriter::new(Vec::new());
        let hours = writer.add_style(&hypaxel::Style::new().num_format("[h]:mm"));
        let date = writer.add_style(&hypaxel::Style::new().num_format("yyyy-mm-dd"));
        let mut sheet = writer.add_worksheet("Data").unwrap();
        sheet.write_styled(1.5, hours);
        sheet.write_styled(60.0, date);
        sheet.end_row().unwrap();
        let out = batch(writer.finish().unwrap());
        let mut c = Cursor { data: &out, pos: EMPTY_LAYOUT + 9 };
        assert_eq!((c.u8(), c.f64()), (TAG_DURATION, 36.0 * 3_600_000.0));
        assert_eq!((c.u8(), c.f64()), (TAG_NUMBER, 60.0), "1900-02-29 does not exist");
    }

    #[test]
    fn encodes_the_layout_and_row_properties_and_lists_tables() {
        use hypaxel::{RowOptions, SheetOptions, TableStyle};

        let mut writer = XlsxWriter::new(Vec::new());
        let options = SheetOptions::new().column_widths(&[12.5]).row_outline_levels(2).outline_summary_above();
        let mut sheet = writer.add_worksheet_with("Data", &options).unwrap();
        sheet.add_row(("Name",)).unwrap();
        sheet.begin_row_with(&RowOptions::new().outline_level(2).hidden().collapsed().height(20.5)).unwrap().write(1).end_row().unwrap();
        sheet.begin_row_with(&RowOptions::new().outline_level(1)).unwrap().write(2).end_row().unwrap();
        sheet.add_table(&Table::new(1, 1, 3, 1).name("People").columns(["Name"]).style(TableStyle::Light(4))).unwrap();
        sheet.add_table(&Table::new(5, 1, 6, 1).header_row(false).style(TableStyle::None)).unwrap();
        let bytes = writer.finish().unwrap();

        let book = Workbook::open_bytes(bytes.clone(), &ReadConfig::default()).unwrap();
        let tables = book.tables(0).unwrap();
        assert_eq!(tables.len(), 2);
        assert_eq!(
            tables[0],
            TableEntry {
                name: "People".into(),
                first_row: 1,
                first_column: 1,
                last_row: 3,
                last_column: 1,
                columns: vec!["Name".into()],
                style: "TableStyleLight4".into(),
                header_row: true,
                autofilter: true,
                row_stripes: true,
                column_stripes: false,
                highlight_first_column: false,
                highlight_last_column: false,
                totals_row: false,
            }
        );
        assert_eq!((tables[1].name.as_str(), tables[1].style.as_str(), tables[1].header_row), ("Table2", "none", false));
        assert!(matches!(book.tables(1), Err(Error::Invalid(_))));

        let out = batch(bytes);
        let mut c = Cursor { data: &out, pos: 0 };
        assert_eq!((c.u8(), c.u8(), c.u8(), c.u32()), (TAG_LAYOUT, 1, 2, 1));
        assert_eq!((c.u32(), c.u32(), c.f64(), c.u8()), (1, 1, 12.5, 0));
        assert_eq!((c.u8(), c.u32(), c.u32()), (TAG_ROW, 1, 1));
        assert_eq!((c.u8(), c.str()), (TAG_STRING, "Name".to_string()));
        assert_eq!((c.u8(), c.u8(), c.f64()), (TAG_ROW_PROPS, 2 | PROP_HIDDEN | PROP_COLLAPSED | PROP_HEIGHT, 20.5));
        assert_eq!((c.u8(), c.u32(), c.u32(), c.u8(), c.f64()), (TAG_ROW, 2, 1, TAG_NUMBER, 1.0));
        assert_eq!((c.u8(), c.u8()), (TAG_ROW_PROPS, 1));
        assert_eq!((c.u8(), c.u32(), c.u32(), c.u8(), c.f64()), (TAG_ROW, 3, 1, TAG_NUMBER, 2.0));
        assert_eq!(c.pos, out.len());
    }

    #[test]
    fn encodes_rows_without_values_on_request() {
        use hypaxel::RowOptions;

        let mut writer = XlsxWriter::new(Vec::new());
        let mut sheet = writer.add_worksheet("Data").unwrap();
        sheet.begin_row_with(&RowOptions::new().height(40.0)).unwrap().end_row().unwrap();
        sheet.add_row((1,)).unwrap();
        let bytes = writer.finish().unwrap();

        let config = ReadConfig { include_empty_rows: Some(true), ..Default::default() };
        let out = read_all(&mut Workbook::open_bytes(bytes.clone(), &config).unwrap().rows(0).unwrap()).unwrap();
        let mut c = Cursor { data: &out, pos: EMPTY_LAYOUT };
        assert_eq!((c.u8(), c.u8(), c.f64()), (TAG_ROW_PROPS, PROP_HEIGHT, 40.0));
        assert_eq!((c.u8(), c.u32(), c.u32()), (TAG_ROW, 1, 0));
        assert_eq!((c.u8(), c.u32(), c.u32(), c.u8(), c.f64()), (TAG_ROW, 2, 1, TAG_NUMBER, 1.0));
        assert_eq!(c.pos, out.len());

        let out = batch(bytes);
        let mut c = Cursor { data: &out, pos: EMPTY_LAYOUT };
        assert_eq!((c.u8(), c.u32(), c.u32()), (TAG_ROW, 2, 1));
    }

    #[test]
    fn sends_the_layout_before_a_row_that_cannot_be_read() {
        use std::io::Write;

        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        for (name, content) in [
            ("_rels/.rels", r#"<Relationships><Relationship Id="rId1" Type="t/officeDocument" Target="xl/workbook.xml"/></Relationships>"#),
            ("xl/workbook.xml", r#"<workbook><sheets><sheet name="S" sheetId="1" id="rId1"/></sheets></workbook>"#),
            (
                "xl/_rels/workbook.xml.rels",
                r#"<Relationships><Relationship Id="rId1" Type="t/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"#,
            ),
            (
                "xl/worksheets/sheet1.xml",
                r#"<worksheet><cols><col min="1" max="1" width="9"/></cols><sheetData><row r="1"><c><v>1,5</v></c></row></sheetData></worksheet>"#,
            ),
        ] {
            zip.start_file(name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(content.as_bytes()).unwrap();
        }
        let book = Workbook::open_bytes(zip.finish().unwrap().into_inner(), &ReadConfig::default()).unwrap();
        let mut rows = book.rows(0).unwrap();
        let mut out = Vec::new();
        assert!(rows.next_batch(&mut out).unwrap());
        let mut c = Cursor { data: &out, pos: 0 };
        assert_eq!((c.u8(), c.u8(), c.u8(), c.u32()), (TAG_LAYOUT, 0, 0, 1));
        assert!(rows.next_batch(&mut out).unwrap_err().to_string().contains("invalid number"));
    }

    #[test]
    fn reports_bad_input() {
        let err = |r: Result<Workbook>| r.err().unwrap().to_string();
        assert!(!err(Workbook::open_bytes(vec![1, 2, 3], &ReadConfig::default())).is_empty());
        assert!(err(Workbook::open_path("/no/such/file.xlsx", &ReadConfig::default())).starts_with("file not found"));
        let config = ReadConfig { max_shared_strings_bytes: Some(-1.0), ..Default::default() };
        assert!(matches!(Workbook::open_bytes(workbook(true), &config), Err(Error::Invalid(_))));

        let tight = ReadConfig { max_shared_strings_bytes: Some(1.0), ..Default::default() };
        let book = Workbook::open_bytes(workbook(true), &tight).unwrap();
        let mut rows = book.rows(0).unwrap();
        assert!(rows.next_batch(&mut Vec::new()).unwrap_err().to_string().contains("limit"));
        assert!(rows.done());
    }
}
