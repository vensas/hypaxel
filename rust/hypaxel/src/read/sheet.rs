//! Streaming worksheet parser: one row at a time, with memory bounded by the widest row.

use std::fmt;
use std::io::Read;
use std::sync::Arc;

use super::sst::SharedStrings;
use super::styles::{DateKind, Styles, parse_u32};
use super::table::is_true;
use super::value::{ExcelDateTime, Value, iso_to_serial};
use super::xml::{self, CellShape, Event, XmlReader};
use super::zip::EntryReader;
use crate::engine::{MAX_COLUMNS, MAX_ROWS};
use crate::error::{Error, Result};

/// Largest amount of decoded text one row may hold.
const MAX_ROW_TEXT: usize = 256 * 1024 * 1024;

#[derive(Debug, Clone, Copy)]
enum Raw {
    Shared(u32),
    Text(u32, u32),
    Number(f64),
    Bool(bool),
    Date(f64, DateKind),
    Error(u32, u32),
}

#[derive(Debug, Clone, Copy)]
struct RawCell {
    column: u32,
    value: Raw,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CellType {
    Number,
    Shared,
    FormulaString,
    InlineString,
    Bool,
    Error,
    IsoDate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum State {
    BeforeData,
    InData,
    Done,
}

/// A run of adjacent columns that share one width.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ColumnWidth {
    first: u32,
    last: u32,
    width: f64,
}

impl ColumnWidth {
    /// 1-based first column of the run.
    pub fn first_column(&self) -> u32 {
        self.first
    }

    /// 1-based last column of the run.
    pub fn last_column(&self) -> u32 {
        self.last
    }

    /// Width in characters of the default font, as in [`Column::width`](crate::Column).
    pub fn width(&self) -> f64 {
        self.width
    }
}

/// Worksheet settings stored ahead of the rows. Returned by [`Rows::layout`].
#[derive(Debug, Clone, Default, PartialEq)]
pub struct SheetLayout {
    columns: Vec<ColumnWidth>,
    summary_above: bool,
    row_outline_levels: u8,
}

impl SheetLayout {
    /// The column widths the sheet defines, explicit and auto-fitted alike, in file order.
    /// Columns that are not covered have the default width.
    pub fn column_widths(&self) -> &[ColumnWidth] {
        &self.columns
    }

    /// The width of a 1-based column; `None` if the sheet leaves it at the default.
    pub fn column_width(&self, column: u32) -> Option<f64> {
        self.columns.iter().find(|c| (c.first..=c.last).contains(&column)).map(|c| c.width)
    }

    /// True if summary rows sit above their groups ([`SheetOptions::outline_summary_above`](crate::SheetOptions::outline_summary_above)).
    pub fn outline_summary_above(&self) -> bool {
        self.summary_above
    }

    /// The deepest row outline level the sheet declares (0 without grouping).
    pub fn row_outline_levels(&self) -> u8 {
        self.row_outline_levels
    }
}

#[derive(Debug, Clone, Copy, Default)]
struct RowProps {
    outline_level: u8,
    hidden: bool,
    collapsed: bool,
    height: Option<f64>,
}

fn parse_outline_level(raw: &[u8]) -> u8 {
    parse_u32(raw).map_or(0, |n| n.min(7) as u8)
}

fn parse_measure(raw: &[u8]) -> Option<f64> {
    std::str::from_utf8(raw).ok()?.trim().parse::<f64>().ok().filter(|n| n.is_finite() && *n >= 0.0)
}

/// Rows of one worksheet, read front to back. Created by [`XlsxReader::worksheet`](crate::XlsxReader::worksheet).
///
/// ```no_run
/// let mut reader = hypaxel::XlsxReader::open("orders.xlsx")?;
/// let mut rows = reader.worksheet("Orders")?;
/// while let Some(row) = rows.next_row()? {
///     for cell in row.cells() {
///         println!("{}{}: {}", hypaxel::column_name(cell.column()), row.number(), cell.value());
///     }
/// }
/// # Ok::<(), hypaxel::Error>(())
/// ```
pub struct Rows<R> {
    xml: XmlReader<EntryReader<R>>,
    sst: Arc<SharedStrings>,
    styles: Arc<Styles>,
    is_1904: bool,
    state: State,
    previous_row: u32,
    number: u32,
    props: RowProps,
    layout: SheetLayout,
    cells: Vec<RawCell>,
    text: String,
    scratch: String,
}

impl<R> fmt::Debug for Rows<R> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Rows").field("part", &self.xml.part()).field("row", &self.number).finish_non_exhaustive()
    }
}

impl<R: Read> Rows<R> {
    pub(crate) fn new(xml: XmlReader<EntryReader<R>>, sst: Arc<SharedStrings>, styles: Arc<Styles>, is_1904: bool) -> Self {
        Self {
            xml,
            sst,
            styles,
            is_1904,
            state: State::BeforeData,
            previous_row: 0,
            number: 0,
            props: RowProps::default(),
            layout: SheetLayout::default(),
            cells: Vec::new(),
            text: String::new(),
            scratch: String::new(),
        }
    }

    /// The column widths and outline settings of the sheet. They precede the rows in the file, so
    /// this is available before the first row is read and costs nothing extra.
    pub fn layout(&mut self) -> Result<&SheetLayout> {
        if self.state == State::BeforeData {
            self.find_sheet_data()?;
        }
        Ok(&self.layout)
    }

    /// Reads the next row that contains at least one value. Rows that are missing from the file
    /// or only carry formatting are skipped; [`Row::number`] tells where the row is.
    pub fn next_row(&mut self) -> Result<Option<Row<'_>>> {
        loop {
            match self.state {
                State::Done => return Ok(None),
                State::BeforeData => self.find_sheet_data()?,
                State::InData => {
                    let (start, tag) = match self.xml.next()? {
                        Event::Start(t) if t.name == b"row" => (true, t),
                        Event::Empty(t) if t.name == b"row" => (false, t),
                        Event::Start(_) => {
                            self.xml.skip_element()?;
                            continue;
                        }
                        Event::End(_) | Event::Eof => {
                            self.state = State::Done;
                            continue;
                        }
                        _ => continue,
                    };
                    let mut number = None;
                    let mut props = RowProps::default();
                    for (name, value) in tag.attrs() {
                        match name {
                            b"r" if number.is_none() => number = Some(parse_u32(value)),
                            b"outlineLevel" => props.outline_level = parse_outline_level(value),
                            b"hidden" => props.hidden = is_true(Some(value)),
                            b"collapsed" => props.collapsed = is_true(Some(value)),
                            b"ht" => props.height = parse_measure(value),
                            _ => {}
                        }
                    }
                    let number = match number {
                        Some(Some(n)) => n,
                        Some(None) => return Err(self.xml.malformed("a row has an invalid row number")),
                        None => self.previous_row + 1,
                    };
                    if !(1..=MAX_ROWS).contains(&number) {
                        return Err(self.xml.malformed(format!("row number {number} is outside 1..=1048576")));
                    }
                    self.previous_row = number;
                    if !start {
                        continue;
                    }
                    self.number = number;
                    self.props = props;
                    self.read_cells()?;
                    if !self.cells.is_empty() {
                        return Ok(Some(Row {
                            number: self.number,
                            props: self.props,
                            cells: &self.cells,
                            text: &self.text,
                            sst: &self.sst,
                            is_1904: self.is_1904,
                        }));
                    }
                }
            }
        }
    }

    fn find_sheet_data(&mut self) -> Result<()> {
        loop {
            let (start, tag) = match self.xml.next()? {
                Event::Start(t) => (true, t),
                Event::Empty(t) => (false, t),
                Event::Eof => {
                    self.state = State::Done;
                    return Ok(());
                }
                _ => continue,
            };
            let mut descend = false;
            match tag.name {
                b"sheetData" => {
                    self.state = if start { State::InData } else { State::Done };
                    return Ok(());
                }
                b"worksheet" | b"dialogsheet" | b"macrosheet" | b"sheetPr" | b"cols" => descend = true,
                b"outlinePr" => {
                    self.layout.summary_above = tag.attr(b"summaryBelow").is_some_and(|v| matches!(v.trim_ascii(), b"0" | b"false"));
                }
                b"sheetFormatPr" => {
                    self.layout.row_outline_levels = tag.attr(b"outlineLevelRow").map_or(0, parse_outline_level);
                }
                b"col" => {
                    let (mut first, mut last, mut width) = (None, None, None);
                    for (name, value) in tag.attrs() {
                        match name {
                            b"min" => first = parse_u32(value),
                            b"max" => last = parse_u32(value),
                            b"width" => width = parse_measure(value),
                            _ => {}
                        }
                    }
                    if let (Some(first), Some(last), Some(width)) = (first, last, width) {
                        let last = last.min(MAX_COLUMNS);
                        if (1..=last).contains(&first) {
                            if self.layout.columns.len() >= MAX_COLUMNS as usize {
                                return Err(self.xml.malformed("the sheet defines more than 16384 column ranges"));
                            }
                            self.layout.columns.push(ColumnWidth { first, last, width });
                        }
                    }
                }
                _ => {}
            }
            if start && !descend {
                self.xml.skip_element()?;
            }
        }
    }

    fn read_cells(&mut self) -> Result<()> {
        self.cells.clear();
        self.text.clear();
        let mut column = 0u32;
        let mut sorted = true;
        loop {
            if let Some(cell) = self.xml.scan_cell()? {
                let next_column = match cell.reference {
                    Some(r) => parse_column(self.xml.bytes(r)),
                    None => Some(column + 1),
                };
                let cell_type = parse_cell_type(cell.cell_type.map(|t| self.xml.bytes(t)));
                let style = cell.style.and_then(|s| parse_digits(self.xml.bytes(s))).unwrap_or(0);
                let (next_column, cell_type) = self.check_cell(next_column, cell_type)?;
                if next_column <= column {
                    sorted = false;
                }
                column = next_column;
                let value = match cell.shape {
                    CellShape::Value => self.scanned_value(cell_type, style, cell.text)?,
                    CellShape::Inline if cell_type == CellType::InlineString => {
                        let start = self.text.len();
                        xml::decode_into(&mut self.text, self.xml.bytes(cell.text), true, true).map_err(|m| self.xml.malformed(m))?;
                        Some(Raw::Text(start as u32, self.text.len() as u32))
                    }
                    _ => None,
                };
                if let Some(value) = value {
                    self.push_cell(column, value)?;
                }
                continue;
            }
            let (start, tag) = match self.xml.next()? {
                Event::Start(t) if t.name == b"c" => (true, t),
                Event::Empty(t) if t.name == b"c" => (false, t),
                Event::Start(_) => {
                    self.xml.skip_element()?;
                    continue;
                }
                Event::End(_) => break,
                _ => continue,
            };
            let (mut reference, mut cell_type, mut style) = (None, None, None);
            for (name, value) in tag.attrs() {
                match name {
                    b"r" => reference = Some(value),
                    b"t" => cell_type = Some(value),
                    b"s" => style = Some(value),
                    _ => {}
                }
            }
            let next_column = match reference {
                Some(r) => parse_column(r),
                None => Some(column + 1),
            };
            let cell_type = parse_cell_type(cell_type);
            let style = style.and_then(parse_digits).unwrap_or(0);
            let (next_column, cell_type) = self.check_cell(next_column, cell_type)?;
            if next_column <= column {
                sorted = false;
            }
            column = next_column;
            if !start {
                continue;
            }
            if let Some(value) = self.read_cell(cell_type, style)? {
                self.push_cell(column, value)?;
            } else {
                self.check_row_text()?;
            }
        }
        if !sorted {
            self.cells.sort_by_key(|c| c.column);
            self.cells.dedup_by(|later, earlier| {
                let same = later.column == earlier.column;
                if same {
                    *earlier = *later;
                }
                same
            });
        }
        Ok(())
    }

    /// Reads the children of a `<c>` element up to its end tag.
    fn read_cell(&mut self, cell_type: CellType, style: u32) -> Result<Option<Raw>> {
        let is_text = matches!(cell_type, CellType::FormulaString | CellType::InlineString | CellType::Error);
        let text_start = self.text.len();
        let mut has_value = false;
        self.scratch.clear();
        loop {
            let child = match self.xml.next()? {
                Event::Start(t) if t.name == b"v" => 1,
                Event::Start(t) if t.name == b"is" => 2,
                Event::Start(_) => 3,
                Event::End(_) => break,
                _ => 0,
            };
            match child {
                1 => {
                    has_value = true;
                    if is_text {
                        self.xml.read_text_into(&mut self.text, true)?;
                    } else {
                        match self.xml.try_raw_text()? {
                            Some(raw) if !raw.contains(&b'&') => self.scratch.push_str(std::str::from_utf8(raw).unwrap_or("\u{FFFD}")),
                            Some(raw) => {
                                let raw = raw.to_vec();
                                xml::decode_into(&mut self.scratch, &raw, true, false).map_err(|m| self.xml.malformed(m))?;
                            }
                            None => self.xml.read_text_into(&mut self.scratch, false)?,
                        }
                    }
                }
                2 if cell_type == CellType::InlineString => {
                    has_value = true;
                    self.read_inline_string()?;
                }
                2 | 3 => self.xml.skip_element()?,
                _ => {}
            }
        }
        if !has_value {
            self.text.truncate(text_start);
            return Ok(None);
        }

        let (start, end) = (text_start as u32, self.text.len() as u32);
        match cell_type {
            CellType::FormulaString | CellType::InlineString => Ok(Some(Raw::Text(start, end))),
            CellType::Error => Ok(Some(Raw::Error(start, end))),
            _ => {
                let raw = std::mem::take(&mut self.scratch);
                let value = self.value_of(cell_type, style, raw.as_bytes());
                self.scratch = raw;
                value
            }
        }
    }

    /// The value of a cell matched by the fast path, from its raw `<v>` text.
    fn scanned_value(&mut self, cell_type: CellType, style: u32, text: xml::Span) -> Result<Option<Raw>> {
        let raw = self.xml.bytes(text);
        match cell_type {
            CellType::FormulaString | CellType::InlineString | CellType::Error => {
                let start = self.text.len() as u32;
                xml::decode_into(&mut self.text, raw, true, true).map_err(|m| self.xml.malformed(m))?;
                let end = self.text.len() as u32;
                Ok(Some(if cell_type == CellType::Error { Raw::Error(start, end) } else { Raw::Text(start, end) }))
            }
            _ if raw.contains(&b'&') => {
                self.scratch.clear();
                xml::decode_into(&mut self.scratch, raw, true, false).map_err(|m| self.xml.malformed(m))?;
                let decoded = std::mem::take(&mut self.scratch);
                let value = self.value_of(cell_type, style, decoded.as_bytes());
                self.scratch = decoded;
                value
            }
            _ => {
                // value_of needs &mut self, so the bytes leave the parser's buffer first; values are short.
                let mut copy = [0u8; 64];
                if raw.len() <= copy.len() {
                    copy[..raw.len()].copy_from_slice(raw);
                    let len = raw.len();
                    self.value_of(cell_type, style, &copy[..len])
                } else {
                    let owned = raw.to_vec();
                    self.value_of(cell_type, style, &owned)
                }
            }
        }
    }

    /// Converts the decoded text of a cell that is not a string type.
    fn value_of(&mut self, cell_type: CellType, style: u32, raw: &[u8]) -> Result<Option<Raw>> {
        let raw = raw.trim_ascii();
        let value = match cell_type {
            CellType::Number if raw.is_empty() => return Ok(None),
            CellType::Number => {
                let n = std::str::from_utf8(raw).ok().and_then(parse_number).ok_or_else(|| self.bad_value("number", raw))?;
                match self.styles.date_kind(style) {
                    Some(kind) => Raw::Date(n, kind),
                    None => Raw::Number(n),
                }
            }
            CellType::Shared => {
                let index = parse_digits(raw).filter(|&i| (i as usize) < self.sst.len());
                Raw::Shared(index.ok_or_else(|| self.bad_value("shared string index", raw))?)
            }
            CellType::Bool => match raw {
                b"1" | b"true" => Raw::Bool(true),
                b"0" | b"false" => Raw::Bool(false),
                _ => return Err(self.bad_value("boolean", raw)),
            },
            CellType::IsoDate => {
                let text = String::from_utf8_lossy(raw);
                match iso_to_serial(&text, self.is_1904) {
                    Some((serial, kind)) => Raw::Date(serial, kind),
                    None => {
                        let start = self.text.len() as u32;
                        self.text.push_str(&text);
                        Raw::Text(start, self.text.len() as u32)
                    }
                }
            }
            CellType::FormulaString | CellType::InlineString | CellType::Error => unreachable!("string cells are not converted"),
        };
        Ok(Some(value))
    }

    fn check_cell(&self, column: Option<u32>, cell_type: Option<CellType>) -> Result<(u32, CellType)> {
        let Some(column) = column.filter(|c| (1..=MAX_COLUMNS).contains(c)) else {
            return Err(self.xml.malformed(format!("row {} has a cell with an invalid reference", self.number)));
        };
        let Some(cell_type) = cell_type else {
            return Err(self.xml.malformed(format!("row {} has a cell with an unknown type", self.number)));
        };
        Ok((column, cell_type))
    }

    fn push_cell(&mut self, column: u32, value: Raw) -> Result<()> {
        if self.cells.len() >= MAX_COLUMNS as usize {
            return Err(self.xml.malformed(format!("row {} has more than 16384 cells", self.number)));
        }
        self.cells.push(RawCell { column, value });
        self.check_row_text()
    }

    fn check_row_text(&self) -> Result<()> {
        if self.text.len() > MAX_ROW_TEXT {
            return Err(Error::LimitExceeded(format!("row {} holds more than {MAX_ROW_TEXT} bytes of text", self.number)));
        }
        Ok(())
    }

    /// Reads `<is>` content: plain `<t>` or rich-text runs, without phonetic runs.
    fn read_inline_string(&mut self) -> Result<()> {
        let target_end = b"is";
        loop {
            let action = match self.xml.next()? {
                Event::Start(t) if t.name == b"t" => 1,
                Event::Start(t) if t.name == b"rPh" || t.name == b"rPr" || t.name == b"phoneticPr" => 2,
                Event::End(name) if name == target_end => return Ok(()),
                _ => 0,
            };
            match action {
                1 => self.xml.read_text_into(&mut self.text, true)?,
                2 => self.xml.skip_element()?,
                _ => {}
            }
        }
    }

    fn bad_value(&self, what: &str, raw: &[u8]) -> Error {
        let shown: String = String::from_utf8_lossy(raw).chars().take(40).collect();
        self.xml.malformed(format!("row {} has an invalid {what} {shown:?}", self.number))
    }
}

fn parse_cell_type(raw: Option<&[u8]>) -> Option<CellType> {
    match raw {
        None | Some(b"n") => Some(CellType::Number),
        Some(b"s") => Some(CellType::Shared),
        Some(b"str") => Some(CellType::FormulaString),
        Some(b"inlineStr") => Some(CellType::InlineString),
        Some(b"b") => Some(CellType::Bool),
        Some(b"e") => Some(CellType::Error),
        Some(b"d") => Some(CellType::IsoDate),
        Some(_) => None,
    }
}

/// Parses a non-negative decimal integer that fits into a `u32`.
fn parse_digits(raw: &[u8]) -> Option<u32> {
    if raw.is_empty() || raw.len() > 10 {
        return None;
    }
    let mut n = 0u64;
    for &b in raw {
        if !b.is_ascii_digit() {
            return None;
        }
        n = n * 10 + (b - b'0') as u64;
    }
    u32::try_from(n).ok()
}

/// Parses a cell number. Integers of up to 15 digits (exact in an `f64`) take a fast path.
fn parse_number(raw: &str) -> Option<f64> {
    let bytes = raw.as_bytes();
    let (negative, digits) = match bytes.first() {
        Some(b'-') => (true, &bytes[1..]),
        _ => (false, bytes),
    };
    if !digits.is_empty() && digits.len() <= 15 && digits.iter().all(u8::is_ascii_digit) {
        let n = digits.iter().fold(0i64, |n, &b| n * 10 + (b - b'0') as i64) as f64;
        return Some(if negative { -n } else { n });
    }
    raw.parse::<f64>().ok().filter(|n| n.is_finite())
}

/// Parses the column letters of a cell reference such as `AB12`.
fn parse_column(reference: &[u8]) -> Option<u32> {
    let mut column = 0u32;
    let mut letters = 0;
    for &b in reference {
        if b.is_ascii_alphabetic() {
            letters += 1;
            if letters > 3 {
                return None;
            }
            column = column * 26 + (b.to_ascii_uppercase() - b'A' + 1) as u32;
        } else {
            break;
        }
    }
    (letters > 0).then_some(column)
}

/// One row of a worksheet. Borrowed from [`Rows`]; it is valid until the next call to
/// [`Rows::next_row`].
#[derive(Clone, Copy)]
pub struct Row<'a> {
    number: u32,
    props: RowProps,
    cells: &'a [RawCell],
    text: &'a str,
    sst: &'a SharedStrings,
    is_1904: bool,
}

impl fmt::Debug for Row<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Row").field("number", &self.number).field("cells", &self.cells().collect::<Vec<_>>()).finish()
    }
}

/// A cell with a value. Columns are 1-based (A = 1).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Cell<'a> {
    column: u32,
    value: Value<'a>,
    shared_string: Option<u32>,
}

impl<'a> Cell<'a> {
    /// 1-based column (A = 1).
    pub fn column(&self) -> u32 {
        self.column
    }

    pub fn value(&self) -> Value<'a> {
        self.value
    }

    /// For text from the shared string table, its index in that table. Equal indices mean equal
    /// text, so callers can convert each shared string once and reuse the result.
    pub fn shared_string_index(&self) -> Option<usize> {
        self.shared_string.map(|i| i as usize)
    }
}

impl<'a> Row<'a> {
    /// 1-based row number as shown in Excel.
    pub fn number(&self) -> u32 {
        self.number
    }

    /// Outline (grouping) level, 0–7.
    pub fn outline_level(&self) -> u8 {
        self.props.outline_level
    }

    pub fn is_hidden(&self) -> bool {
        self.props.hidden
    }

    /// True for the summary row of a collapsed group.
    pub fn is_collapsed(&self) -> bool {
        self.props.collapsed
    }

    /// Height in points, if the row stores one.
    pub fn height(&self) -> Option<f64> {
        self.props.height
    }

    /// Number of cells with a value.
    pub fn len(&self) -> usize {
        self.cells.len()
    }

    /// Always false: rows without values are skipped by [`Rows::next_row`].
    pub fn is_empty(&self) -> bool {
        self.cells.is_empty()
    }

    /// 1-based column of the last cell with a value.
    pub fn last_column(&self) -> u32 {
        self.cells.last().map_or(0, |c| c.column)
    }

    fn resolve(&self, raw: Raw) -> Value<'a> {
        match raw {
            Raw::Shared(i) => Value::String(self.sst.get(i as usize).unwrap_or_default()),
            Raw::Text(s, e) => Value::String(&self.text[s as usize..e as usize]),
            Raw::Error(s, e) => Value::Error(&self.text[s as usize..e as usize]),
            Raw::Number(n) => Value::Number(n),
            Raw::Bool(b) => Value::Bool(b),
            Raw::Date(n, kind) => Value::DateTime(ExcelDateTime::new(n, kind, self.is_1904)),
        }
    }

    /// The cells that have a value, in column order.
    pub fn cells(&self) -> impl ExactSizeIterator<Item = Cell<'a>> + 'a {
        let row = *self;
        self.cells.iter().map(move |c| Cell {
            column: c.column,
            value: row.resolve(c.value),
            shared_string: match c.value {
                Raw::Shared(i) => Some(i),
                _ => None,
            },
        })
    }

    /// The value in a 1-based column; [`Value::Empty`] if there is none.
    pub fn get(&self, column: u32) -> Value<'a> {
        match self.cells.binary_search_by_key(&column, |c| c.column) {
            Ok(i) => self.resolve(self.cells[i].value),
            Err(_) => Value::Empty,
        }
    }

    /// The values of columns 1 to [`last_column`](Row::last_column), with [`Value::Empty`] for
    /// gaps.
    pub fn values(&self) -> impl Iterator<Item = Value<'a>> + 'a {
        let row = *self;
        let mut next = 0;
        (1..=self.last_column()).map(move |column| {
            let cell = row.cells[next];
            if cell.column == column {
                next += 1;
                row.resolve(cell.value)
            } else {
                Value::Empty
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_column_letters() {
        assert_eq!(parse_column(b"A1"), Some(1));
        assert_eq!(parse_column(b"z9"), Some(26));
        assert_eq!(parse_column(b"AA10"), Some(27));
        assert_eq!(parse_column(b"XFD1048576"), Some(16_384));
        assert_eq!(parse_column(b"ABCD1"), None);
        assert_eq!(parse_column(b"12"), None);
    }

    #[test]
    fn parses_numbers() {
        assert_eq!(parse_number("42"), Some(42.0));
        assert_eq!(parse_number("-123456789012345"), Some(-123_456_789_012_345.0));
        assert_eq!(parse_number("12345678901234567890"), Some(1.2345678901234567e19));
        assert_eq!(parse_number("3.25E-2"), Some(0.0325));
        assert_eq!(parse_number("-"), None);
        assert_eq!(parse_number("NaN"), None);
        assert_eq!(parse_number("inf"), None);
        assert_eq!(parse_digits(b"4294967295"), Some(u32::MAX));
        assert_eq!(parse_digits(b"4294967296"), None);
        assert_eq!(parse_digits(b""), None);
    }
}
