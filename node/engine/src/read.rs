//! The reading half of the engine: a workbook handle and a row stream that encodes rows into a
//! compact binary batch, decoded by `RowDecoder` in `node/ts/core.ts`. As with writing, a whole
//! batch of rows crosses the JavaScript boundary in one call.
//!
//! Batch format (little-endian), one row after another:
//!
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

use hypaxel::{DateKind, ExcelDateTime, ReadOptions, Rows, SheetKind, Value, Visibility, XlsxReader};

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
}

impl ReadConfig {
    fn options(&self) -> Result<ReadOptions> {
        let mut options = ReadOptions::new();
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

    /// A stream over the rows of the sheet at a 0-based index. The sheet is opened, and the shared
    /// string table loaded, on the first call to [`RowStream::next_batch`].
    pub fn rows(&self, index: u32) -> Result<RowStream> {
        let count = self.sheets.len();
        if index as usize >= count {
            return Err(invalid(format!("no worksheet at index {index}; the workbook has {count}")));
        }
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

impl RowStream {
    fn open(&mut self) -> Result<()> {
        let StreamState::Pending { origin, reader, index } = &self.state else { return Ok(()) };
        let handle = origin.open();
        let rows = {
            let mut reader = reader.lock().map_err(|_| failed("the workbook is unusable after an earlier failure"))?;
            reader.with_source(handle).and_then(|r| r.into_worksheet_at(*index)).map_err(read_error)?
        };
        self.state = StreamState::Open(Box::new(rows));
        Ok(())
    }

    /// Appends rows to `out` until it holds at least [`BATCH_BYTES`] or the sheet ends. Returns
    /// false once the sheet has ended and nothing was appended.
    pub fn next_batch(&mut self, out: &mut Vec<u8>) -> Result<bool> {
        let start = out.len();
        if let Err(e) = self.open() {
            self.state = StreamState::Done;
            return Err(e);
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
        let mut out = Vec::new();
        assert!(rows.next_batch(&mut out).unwrap());
        assert!(rows.done());
        assert!(!rows.next_batch(&mut out.clone()).unwrap());
        out
    }

    #[test]
    fn reads_the_opened_file_after_its_path_is_replaced_or_deleted() {
        let dir = std::env::temp_dir().join(format!("hypaxel-engine-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("book.xlsx");
        std::fs::write(&path, workbook(true)).unwrap();
        let book = Workbook::open_path(&path, &ReadConfig::default()).unwrap();
        let mut early = book.rows(0).unwrap();
        let mut out = Vec::new();
        assert!(early.next_batch(&mut out).unwrap());

        let replacement = dir.join("other.xlsx");
        std::fs::write(&replacement, b"not a workbook").unwrap();
        std::fs::rename(&replacement, &path).unwrap();
        let mut late = book.rows(0).unwrap();
        let mut again = Vec::new();
        let replaced = late.next_batch(&mut again);
        std::fs::remove_dir_all(&dir).unwrap();
        let mut last = Vec::new();
        let deleted = book.rows(0).unwrap().next_batch(&mut last);

        assert!(replaced.unwrap());
        assert_eq!(again, out);
        assert!(deleted.unwrap());
        assert_eq!(last, out);
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
        let mut c = Cursor { data: &out, pos: 0 };
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
        let mut c = Cursor { data: &out, pos: 9 };
        assert_eq!((c.u8(), c.f64()), (TAG_DURATION, 36.0 * 3_600_000.0));
        assert_eq!((c.u8(), c.f64()), (TAG_NUMBER, 60.0), "1900-02-29 does not exist");
    }

    #[test]
    fn reports_bad_input() {
        let err = |r: Result<Workbook>| r.err().unwrap().to_string();
        assert!(!err(Workbook::open_bytes(vec![1, 2, 3], &ReadConfig::default())).is_empty());
        assert!(err(Workbook::open_path("/no/such/file.xlsx", &ReadConfig::default())).starts_with("file not found"));
        let config = ReadConfig { max_shared_strings_bytes: Some(-1.0) };
        assert!(matches!(Workbook::open_bytes(workbook(true), &config), Err(Error::Invalid(_))));

        let tight = ReadConfig { max_shared_strings_bytes: Some(1.0) };
        let book = Workbook::open_bytes(workbook(true), &tight).unwrap();
        let mut rows = book.rows(0).unwrap();
        assert!(rows.next_batch(&mut Vec::new()).unwrap_err().to_string().contains("limit"));
        assert!(rows.done());
    }
}
