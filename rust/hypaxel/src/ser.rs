//! Write any `T: Serialize` as worksheet rows.
//!
//! Struct fields become columns (header = field name, so `#[serde(rename = "...")]` sets the
//! header), `Option::None` becomes an empty cell, unit enum variants are written by name.
//! Maps write their values (keys become headers), sequences and tuples write their elements.
//!
//! Chrono values serialize as strings by default. To get native, sortable Excel dates use the
//! helpers in this module:
//!
//! ```
//! # #[cfg(feature = "chrono")] {
//! #[derive(serde::Serialize)]
//! struct Order {
//!     id: u32,
//!     #[serde(serialize_with = "hypaxel::records::excel_date")]
//!     placed: chrono::NaiveDate,
//!     #[serde(serialize_with = "hypaxel::records::excel_date")]
//!     shipped: Option<chrono::NaiveDateTime>,
//! }
//! # }
//! ```
//!
//! With other serializers (e.g. JSON) these fields still serialize as ISO 8601 strings.

use std::borrow::Cow;
use std::fmt::Display;

use serde::Serialize;
use serde::ser::{
    self, Impossible, SerializeMap, SerializeSeq, SerializeStruct, SerializeStructVariant, SerializeTuple, SerializeTupleStruct,
    SerializeTupleVariant, Serializer,
};

use crate::engine::CellWriter;
use crate::error::{Error, Result};
use crate::style::{Style, StyleId, ValueKind};

const TEMPORAL_MARKER: &str = "$hypaxel::temporal";
const DURATION_MARKER: &str = "$hypaxel::duration";

impl ser::Error for Error {
    fn custom<T: Display>(msg: T) -> Self {
        Error::Custom(msg.to_string())
    }
}

/// Options for writing records.
#[derive(Debug, Clone)]
pub struct RecordOptions {
    /// Write a header row with the field names. Default: true.
    pub header: bool,
    /// Header style; `None` applies bold text on a light gray fill with a bottom border.
    pub header_style: Option<StyleId>,
    /// Add an auto filter over the written range (requires `header`). Default: false.
    pub autofilter: bool,
    /// Styles for individual columns, keyed by header (field) name.
    pub column_styles: Vec<(Cow<'static, str>, StyleId)>,
}

impl Default for RecordOptions {
    fn default() -> Self {
        RecordOptions { header: true, header_style: None, autofilter: false, column_styles: Vec::new() }
    }
}

impl RecordOptions {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn header(mut self, header: bool) -> Self {
        self.header = header;
        self
    }

    pub fn header_style(mut self, style: StyleId) -> Self {
        self.header_style = Some(style);
        self
    }

    pub fn autofilter(mut self, enabled: bool) -> Self {
        self.autofilter = enabled;
        self
    }

    /// Applies a style (e.g. a number format) to the column for field `name`.
    pub fn column_style(mut self, name: impl Into<Cow<'static, str>>, style: StyleId) -> Self {
        self.column_styles.push((name.into(), style));
        self
    }
}

pub(crate) fn default_header_style() -> Style {
    use crate::style::{Border, BorderLine, BorderStyle, Color};
    Style::new()
        .bold()
        .fill(Color::LIGHT_GRAY)
        .border(Border { bottom: BorderLine { style: BorderStyle::Thin, color: None }, ..Border::default() })
}

/// State for writing a sequence of records: resolved per-column styles and the first row.
pub(crate) struct RecordContext {
    styles: Vec<StyleId>,
    columns: u32,
    first_row: u32,
    autofilter: bool,
}

impl RecordContext {
    /// Writes the header (if requested) based on the first record.
    pub fn begin<T: Serialize + ?Sized>(
        cells: &mut CellWriter,
        first: Option<&T>,
        options: &RecordOptions,
        header_style: StyleId,
    ) -> Result<Self> {
        let names = match first {
            Some(record) => field_names(record)?,
            None => Vec::new(),
        };
        let styles =
            names.iter().map(|name| options.column_styles.iter().find(|(n, _)| n == name).map_or(StyleId::DEFAULT, |&(_, s)| s)).collect();
        let first_row = cells.next_row();
        if options.header && !names.is_empty() {
            cells.begin_next_row();
            for name in &names {
                cells.write_str(name, header_style);
            }
            cells.close_row();
        }
        Ok(RecordContext { styles, columns: names.len() as u32, first_row, autofilter: options.autofilter && options.header })
    }

    #[inline]
    pub fn write<T: Serialize + ?Sized>(&self, cells: &mut CellWriter, record: &T) -> Result<()> {
        cells.begin_next_row();
        let result = record.serialize(RecordSerializer { cells: &mut *cells, styles: &self.styles });
        cells.close_row();
        result
    }

    pub fn end(&self, cells: &mut CellWriter) -> Result<()> {
        if self.autofilter && self.columns > 0 {
            cells.autofilter(self.first_row, 1, cells.row().max(self.first_row), self.columns)?;
        }
        Ok(())
    }
}

/// Collects the column names of a record.
pub(crate) fn field_names<T: Serialize + ?Sized>(record: &T) -> Result<Vec<String>> {
    let mut names = Vec::new();
    record.serialize(NameCollector { names: &mut names })?;
    Ok(names)
}

/// Estimated column widths from header names.
pub(crate) fn estimate_widths(names: &[String]) -> Vec<f64> {
    names.iter().map(|n| ((n.chars().count() + 3).clamp(12, 60)) as f64).collect()
}

// ---------------------------------------------------------------------------------------------
// Record (row) serializer

struct RecordSerializer<'a> {
    cells: &'a mut CellWriter,
    styles: &'a [StyleId],
}

struct Fields<'a> {
    cells: &'a mut CellWriter,
    styles: &'a [StyleId],
    index: usize,
}

impl Fields<'_> {
    #[inline]
    fn cell<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<()> {
        let style = self.styles.get(self.index).copied().unwrap_or_default();
        self.index += 1;
        value.serialize(CellSerializer { cells: &mut *self.cells, style })
    }
}

macro_rules! record_scalar {
    ($($method:ident: $t:ty),*) => {$(
        fn $method(self, v: $t) -> Result<()> {
            v.serialize(CellSerializer { cells: self.cells, style: self.styles.first().copied().unwrap_or_default() })
        }
    )*};
}

impl<'a> Serializer for RecordSerializer<'a> {
    type Ok = ();
    type Error = Error;
    type SerializeSeq = Fields<'a>;
    type SerializeTuple = Fields<'a>;
    type SerializeTupleStruct = Fields<'a>;
    type SerializeTupleVariant = Fields<'a>;
    type SerializeMap = Fields<'a>;
    type SerializeStruct = Fields<'a>;
    type SerializeStructVariant = Fields<'a>;

    record_scalar!(serialize_bool: bool, serialize_i8: i8, serialize_i16: i16, serialize_i32: i32, serialize_i64: i64,
        serialize_i128: i128, serialize_u8: u8, serialize_u16: u16, serialize_u32: u32, serialize_u64: u64,
        serialize_u128: u128, serialize_f32: f32, serialize_f64: f64, serialize_char: char, serialize_str: &str);

    fn serialize_bytes(self, _: &[u8]) -> Result<()> {
        Err(Error::Unsupported("byte arrays".into()))
    }
    fn serialize_none(self) -> Result<()> {
        Ok(())
    }
    fn serialize_some<T: Serialize + ?Sized>(self, value: &T) -> Result<()> {
        value.serialize(self)
    }
    fn serialize_unit(self) -> Result<()> {
        Ok(())
    }
    fn serialize_unit_struct(self, _: &'static str) -> Result<()> {
        Ok(())
    }
    fn serialize_unit_variant(self, _: &'static str, _: u32, variant: &'static str) -> Result<()> {
        self.serialize_str(variant)
    }
    fn serialize_newtype_struct<T: Serialize + ?Sized>(self, name: &'static str, value: &T) -> Result<()> {
        let style = self.styles.first().copied().unwrap_or_default();
        CellSerializer { cells: self.cells, style }.serialize_newtype_struct(name, value)
    }
    fn serialize_newtype_variant<T: Serialize + ?Sized>(self, _: &'static str, _: u32, _: &'static str, value: &T) -> Result<()> {
        value.serialize(self)
    }
    fn serialize_seq(self, _: Option<usize>) -> Result<Fields<'a>> {
        Ok(Fields { cells: self.cells, styles: self.styles, index: 0 })
    }
    fn serialize_tuple(self, len: usize) -> Result<Fields<'a>> {
        self.serialize_seq(Some(len))
    }
    fn serialize_tuple_struct(self, _: &'static str, len: usize) -> Result<Fields<'a>> {
        self.serialize_seq(Some(len))
    }
    fn serialize_tuple_variant(self, _: &'static str, _: u32, _: &'static str, len: usize) -> Result<Fields<'a>> {
        self.serialize_seq(Some(len))
    }
    fn serialize_map(self, len: Option<usize>) -> Result<Fields<'a>> {
        self.serialize_seq(len)
    }
    fn serialize_struct(self, _: &'static str, len: usize) -> Result<Fields<'a>> {
        self.serialize_seq(Some(len))
    }
    fn serialize_struct_variant(self, _: &'static str, _: u32, _: &'static str, len: usize) -> Result<Fields<'a>> {
        self.serialize_seq(Some(len))
    }
}

impl SerializeSeq for Fields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_element<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<()> {
        self.cell(value)
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeTuple for Fields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_element<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<()> {
        self.cell(value)
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeTupleStruct for Fields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_field<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<()> {
        self.cell(value)
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeTupleVariant for Fields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_field<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<()> {
        self.cell(value)
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeMap for Fields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_key<T: Serialize + ?Sized>(&mut self, _: &T) -> Result<()> {
        Ok(())
    }
    fn serialize_value<T: Serialize + ?Sized>(&mut self, value: &T) -> Result<()> {
        self.cell(value)
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeStruct for Fields<'_> {
    type Ok = ();
    type Error = Error;
    #[inline]
    fn serialize_field<T: Serialize + ?Sized>(&mut self, _: &'static str, value: &T) -> Result<()> {
        self.cell(value)
    }
    fn skip_field(&mut self, _: &'static str) -> Result<()> {
        // Skipped fields have no header column either, so the column count stays aligned.
        Ok(())
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeStructVariant for Fields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_field<T: Serialize + ?Sized>(&mut self, _: &'static str, value: &T) -> Result<()> {
        self.cell(value)
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

// ---------------------------------------------------------------------------------------------
// Single-cell serializer

struct CellSerializer<'a> {
    cells: &'a mut CellWriter,
    style: StyleId,
}

fn nested() -> Error {
    Error::Unsupported("nested sequences, maps or structs cannot be written into a single cell".into())
}

impl Serializer for CellSerializer<'_> {
    type Ok = ();
    type Error = Error;
    type SerializeSeq = Impossible<(), Error>;
    type SerializeTuple = Impossible<(), Error>;
    type SerializeTupleStruct = Impossible<(), Error>;
    type SerializeTupleVariant = Impossible<(), Error>;
    type SerializeMap = Impossible<(), Error>;
    type SerializeStruct = Impossible<(), Error>;
    type SerializeStructVariant = Impossible<(), Error>;

    #[inline]
    fn serialize_bool(self, v: bool) -> Result<()> {
        self.cells.write_bool(v, self.style);
        Ok(())
    }
    #[inline]
    fn serialize_i8(self, v: i8) -> Result<()> {
        self.serialize_i64(v as i64)
    }
    #[inline]
    fn serialize_i16(self, v: i16) -> Result<()> {
        self.serialize_i64(v as i64)
    }
    #[inline]
    fn serialize_i32(self, v: i32) -> Result<()> {
        self.serialize_i64(v as i64)
    }
    #[inline]
    fn serialize_i64(self, v: i64) -> Result<()> {
        self.cells.write_i64(v, self.style);
        Ok(())
    }
    fn serialize_i128(self, v: i128) -> Result<()> {
        self.serialize_f64(v as f64)
    }
    #[inline]
    fn serialize_u8(self, v: u8) -> Result<()> {
        self.serialize_u64(v as u64)
    }
    #[inline]
    fn serialize_u16(self, v: u16) -> Result<()> {
        self.serialize_u64(v as u64)
    }
    #[inline]
    fn serialize_u32(self, v: u32) -> Result<()> {
        self.serialize_u64(v as u64)
    }
    #[inline]
    fn serialize_u64(self, v: u64) -> Result<()> {
        self.cells.write_u64(v, self.style);
        Ok(())
    }
    fn serialize_u128(self, v: u128) -> Result<()> {
        self.serialize_f64(v as f64)
    }
    #[inline]
    fn serialize_f32(self, v: f32) -> Result<()> {
        crate::IntoCell::write_cell(v, self.cells, self.style);
        Ok(())
    }
    #[inline]
    fn serialize_f64(self, v: f64) -> Result<()> {
        self.cells.write_f64(v, self.style);
        Ok(())
    }
    fn serialize_char(self, v: char) -> Result<()> {
        self.serialize_str(v.encode_utf8(&mut [0; 4]))
    }
    #[inline]
    fn serialize_str(self, v: &str) -> Result<()> {
        self.cells.write_str(v, self.style);
        Ok(())
    }
    fn serialize_bytes(self, _: &[u8]) -> Result<()> {
        Err(Error::Unsupported("byte arrays".into()))
    }
    #[inline]
    fn serialize_none(self) -> Result<()> {
        self.cells.write_blank(self.style);
        Ok(())
    }
    #[inline]
    fn serialize_some<T: Serialize + ?Sized>(self, value: &T) -> Result<()> {
        value.serialize(self)
    }
    fn serialize_unit(self) -> Result<()> {
        self.serialize_none()
    }
    fn serialize_unit_struct(self, _: &'static str) -> Result<()> {
        self.serialize_none()
    }
    fn serialize_unit_variant(self, _: &'static str, _: u32, variant: &'static str) -> Result<()> {
        self.serialize_str(variant)
    }
    fn serialize_newtype_struct<T: Serialize + ?Sized>(self, name: &'static str, value: &T) -> Result<()> {
        match name {
            TEMPORAL_MARKER => value.serialize(TemporalCapture { cells: self.cells, style: self.style }),
            DURATION_MARKER => value.serialize(DurationCapture { cells: self.cells, style: self.style }),
            _ => value.serialize(self),
        }
    }
    fn serialize_newtype_variant<T: Serialize + ?Sized>(self, _: &'static str, _: u32, _: &'static str, value: &T) -> Result<()> {
        value.serialize(self)
    }
    fn serialize_seq(self, _: Option<usize>) -> Result<Self::SerializeSeq> {
        Err(nested())
    }
    fn serialize_tuple(self, _: usize) -> Result<Self::SerializeTuple> {
        Err(nested())
    }
    fn serialize_tuple_struct(self, _: &'static str, _: usize) -> Result<Self::SerializeTupleStruct> {
        Err(nested())
    }
    fn serialize_tuple_variant(self, _: &'static str, _: u32, _: &'static str, _: usize) -> Result<Self::SerializeTupleVariant> {
        Err(nested())
    }
    fn serialize_map(self, _: Option<usize>) -> Result<Self::SerializeMap> {
        Err(nested())
    }
    fn serialize_struct(self, _: &'static str, _: usize) -> Result<Self::SerializeStruct> {
        Err(nested())
    }
    fn serialize_struct_variant(self, _: &'static str, _: u32, _: &'static str, _: usize) -> Result<Self::SerializeStructVariant> {
        Err(nested())
    }
}

/// Implements the listed `Serializer` methods as "unsupported value" errors.
macro_rules! unsupported {
    ($($method:ident($($arg:ty),*)),* $(,)?) => {$(
        fn $method(self $(, _: $arg)*) -> Result<()> {
            Err(Error::Unsupported(concat!("value kind `", stringify!($method), "`").into()))
        }
    )*};
}

macro_rules! unsupported_compound {
    () => {
        fn serialize_newtype_struct<T: Serialize + ?Sized>(self, _: &'static str, _: &T) -> Result<()> {
            Err(Error::Unsupported("newtype struct".into()))
        }
        fn serialize_newtype_variant<T: Serialize + ?Sized>(self, _: &'static str, _: u32, _: &'static str, _: &T) -> Result<()> {
            Err(Error::Unsupported("newtype variant".into()))
        }
        fn serialize_seq(self, _: Option<usize>) -> Result<Self::SerializeSeq> {
            Err(nested())
        }
        fn serialize_tuple(self, _: usize) -> Result<Self::SerializeTuple> {
            Err(nested())
        }
        fn serialize_tuple_struct(self, _: &'static str, _: usize) -> Result<Self::SerializeTupleStruct> {
            Err(nested())
        }
        fn serialize_tuple_variant(self, _: &'static str, _: u32, _: &'static str, _: usize) -> Result<Self::SerializeTupleVariant> {
            Err(nested())
        }
        fn serialize_map(self, _: Option<usize>) -> Result<Self::SerializeMap> {
            Err(nested())
        }
        fn serialize_struct(self, _: &'static str, _: usize) -> Result<Self::SerializeStruct> {
            Err(nested())
        }
        fn serialize_struct_variant(self, _: &'static str, _: u32, _: &'static str, _: usize) -> Result<Self::SerializeStructVariant> {
            Err(nested())
        }
    };
}

// ---------------------------------------------------------------------------------------------
// Temporal capture: parses the fixed-width ISO strings produced by the `excel_*` helpers.

struct TemporalCapture<'a> {
    cells: &'a mut CellWriter,
    style: StyleId,
}

struct DurationCapture<'a> {
    cells: &'a mut CellWriter,
    style: StyleId,
}

/// Days from 0001-01-01 (= day 1), proleptic Gregorian.
///
/// Howard Hinnant's `days_from_civil`: <https://howardhinnant.github.io/date_algorithms.html>
fn days_from_ce(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m as i64 + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468 + 719_163
}

fn digits(s: &[u8]) -> Option<u64> {
    if s.is_empty() || !s.iter().all(u8::is_ascii_digit) {
        return None;
    }
    Some(s.iter().fold(0u64, |acc, &b| acc * 10 + (b - b'0') as u64))
}

fn parse_date(s: &[u8]) -> Option<i64> {
    if s.len() != 10 || s[4] != b'-' || s[7] != b'-' {
        return None;
    }
    let (y, m, d) = (digits(&s[0..4])?, digits(&s[5..7])? as u32, digits(&s[8..10])? as u32);
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    Some(days_from_ce(y as i64, m, d))
}

/// Parses `HH:MM:SS[.fffffffff]` into nanoseconds of the day.
fn parse_time(s: &[u8]) -> Option<u64> {
    if s.len() < 8 || s[2] != b':' || s[5] != b':' {
        return None;
    }
    let (h, m, sec) = (digits(&s[0..2])?, digits(&s[3..5])?, digits(&s[6..8])?);
    let mut nanos = 0;
    if s.len() > 8 {
        if s[8] != b'.' || s.len() > 18 {
            return None;
        }
        let frac = &s[9..];
        nanos = digits(frac)? * 10u64.pow(9 - frac.len() as u32);
    }
    Some(((h * 60 + m) * 60 + sec.min(59)) * 1_000_000_000 + nanos)
}

impl Serializer for TemporalCapture<'_> {
    type Ok = ();
    type Error = Error;
    type SerializeSeq = Impossible<(), Error>;
    type SerializeTuple = Impossible<(), Error>;
    type SerializeTupleStruct = Impossible<(), Error>;
    type SerializeTupleVariant = Impossible<(), Error>;
    type SerializeMap = Impossible<(), Error>;
    type SerializeStruct = Impossible<(), Error>;
    type SerializeStructVariant = Impossible<(), Error>;

    fn serialize_str(self, v: &str) -> Result<()> {
        let b = v.as_bytes();
        let is_date = b.len() >= 10 && b[4] == b'-';
        let parsed = match b.len() {
            10 if is_date => parse_date(b).map(|d| (d, 0, ValueKind::Date)),
            n if is_date && n >= 19 && b[10] == b'T' => {
                parse_date(&b[..10]).zip(parse_time(&b[11..])).map(|(d, t)| (d, t, ValueKind::DateTime))
            }
            _ if !is_date => parse_time(b).map(|t| (-1, t, ValueKind::Time)),
            _ => None,
        };
        match parsed {
            Some((_, nanos, ValueKind::Time)) => self.cells.write_days(nanos as f64 / 86_400e9, ValueKind::Time, self.style),
            Some((days, nanos, kind)) => self.cells.write_temporal(days, nanos, kind, self.style, || v.to_owned()),
            None => self.cells.write_str(v, self.style),
        }
        Ok(())
    }

    fn serialize_none(self) -> Result<()> {
        self.cells.write_blank(self.style);
        Ok(())
    }

    fn serialize_some<T: Serialize + ?Sized>(self, value: &T) -> Result<()> {
        value.serialize(self)
    }

    unsupported!(
        serialize_bool(bool),
        serialize_i8(i8),
        serialize_i16(i16),
        serialize_i32(i32),
        serialize_i64(i64),
        serialize_u8(u8),
        serialize_u16(u16),
        serialize_u32(u32),
        serialize_u64(u64),
        serialize_f32(f32),
        serialize_f64(f64),
        serialize_char(char),
        serialize_bytes(&[u8]),
        serialize_unit(),
        serialize_unit_struct(&'static str),
        serialize_unit_variant(&'static str, u32, &'static str)
    );
    unsupported_compound!();
}

impl Serializer for DurationCapture<'_> {
    type Ok = ();
    type Error = Error;
    type SerializeSeq = Impossible<(), Error>;
    type SerializeTuple = Impossible<(), Error>;
    type SerializeTupleStruct = Impossible<(), Error>;
    type SerializeTupleVariant = Impossible<(), Error>;
    type SerializeMap = Impossible<(), Error>;
    type SerializeStruct = Impossible<(), Error>;
    type SerializeStructVariant = Impossible<(), Error>;

    fn serialize_f64(self, seconds: f64) -> Result<()> {
        self.cells.write_days(seconds / 86_400.0, ValueKind::Duration, self.style);
        Ok(())
    }

    fn serialize_none(self) -> Result<()> {
        self.cells.write_blank(self.style);
        Ok(())
    }

    fn serialize_some<T: Serialize + ?Sized>(self, value: &T) -> Result<()> {
        value.serialize(self)
    }

    unsupported!(
        serialize_bool(bool),
        serialize_i8(i8),
        serialize_i16(i16),
        serialize_i32(i32),
        serialize_i64(i64),
        serialize_u8(u8),
        serialize_u16(u16),
        serialize_u32(u32),
        serialize_u64(u64),
        serialize_f32(f32),
        serialize_char(char),
        serialize_str(&str),
        serialize_bytes(&[u8]),
        serialize_unit(),
        serialize_unit_struct(&'static str),
        serialize_unit_variant(&'static str, u32, &'static str)
    );
    unsupported_compound!();
}

// ---------------------------------------------------------------------------------------------
// Header names

struct NameCollector<'a> {
    names: &'a mut Vec<String>,
}

struct NameFields<'a> {
    names: &'a mut Vec<String>,
}

impl NameFields<'_> {
    fn positional(&mut self) {
        let n = self.names.len() + 1;
        self.names.push(format!("Column{n}"));
    }
}

impl<'a> Serializer for NameCollector<'a> {
    type Ok = ();
    type Error = Error;
    type SerializeSeq = NameFields<'a>;
    type SerializeTuple = NameFields<'a>;
    type SerializeTupleStruct = NameFields<'a>;
    type SerializeTupleVariant = NameFields<'a>;
    type SerializeMap = NameFields<'a>;
    type SerializeStruct = NameFields<'a>;
    type SerializeStructVariant = NameFields<'a>;

    fn serialize_some<T: Serialize + ?Sized>(self, value: &T) -> Result<()> {
        value.serialize(self)
    }
    fn serialize_newtype_struct<T: Serialize + ?Sized>(self, name: &'static str, value: &T) -> Result<()> {
        if name == TEMPORAL_MARKER || name == DURATION_MARKER {
            self.names.push("Value".into());
            Ok(())
        } else {
            value.serialize(self)
        }
    }
    fn serialize_newtype_variant<T: Serialize + ?Sized>(self, _: &'static str, _: u32, _: &'static str, value: &T) -> Result<()> {
        value.serialize(self)
    }
    fn serialize_seq(self, _: Option<usize>) -> Result<NameFields<'a>> {
        Ok(NameFields { names: self.names })
    }
    fn serialize_tuple(self, _: usize) -> Result<NameFields<'a>> {
        Ok(NameFields { names: self.names })
    }
    fn serialize_tuple_struct(self, _: &'static str, _: usize) -> Result<NameFields<'a>> {
        Ok(NameFields { names: self.names })
    }
    fn serialize_tuple_variant(self, _: &'static str, _: u32, _: &'static str, _: usize) -> Result<NameFields<'a>> {
        Ok(NameFields { names: self.names })
    }
    fn serialize_map(self, _: Option<usize>) -> Result<NameFields<'a>> {
        Ok(NameFields { names: self.names })
    }
    fn serialize_struct(self, _: &'static str, _: usize) -> Result<NameFields<'a>> {
        Ok(NameFields { names: self.names })
    }
    fn serialize_struct_variant(self, _: &'static str, _: u32, _: &'static str, _: usize) -> Result<NameFields<'a>> {
        Ok(NameFields { names: self.names })
    }

    // A scalar record is a single column.
    fn serialize_bool(self, _: bool) -> Result<()> {
        self.names.push("Value".into());
        Ok(())
    }
    fn serialize_i64(self, _: i64) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_u64(self, _: u64) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_f64(self, _: f64) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_str(self, _: &str) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_i8(self, _: i8) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_i16(self, _: i16) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_i32(self, _: i32) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_u8(self, _: u8) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_u16(self, _: u16) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_u32(self, _: u32) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_f32(self, _: f32) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_char(self, _: char) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_bytes(self, _: &[u8]) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_none(self) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_unit(self) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_unit_struct(self, _: &'static str) -> Result<()> {
        self.serialize_bool(false)
    }
    fn serialize_unit_variant(self, _: &'static str, _: u32, _: &'static str) -> Result<()> {
        self.serialize_bool(false)
    }
}

impl SerializeSeq for NameFields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_element<T: Serialize + ?Sized>(&mut self, _: &T) -> Result<()> {
        self.positional();
        Ok(())
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeTuple for NameFields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_element<T: Serialize + ?Sized>(&mut self, _: &T) -> Result<()> {
        self.positional();
        Ok(())
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeTupleStruct for NameFields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_field<T: Serialize + ?Sized>(&mut self, _: &T) -> Result<()> {
        self.positional();
        Ok(())
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeTupleVariant for NameFields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_field<T: Serialize + ?Sized>(&mut self, _: &T) -> Result<()> {
        self.positional();
        Ok(())
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeMap for NameFields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_key<T: Serialize + ?Sized>(&mut self, key: &T) -> Result<()> {
        let mut text = String::new();
        key.serialize(KeyToString { out: &mut text })?;
        self.names.push(text);
        Ok(())
    }
    fn serialize_value<T: Serialize + ?Sized>(&mut self, _: &T) -> Result<()> {
        Ok(())
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeStruct for NameFields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_field<T: Serialize + ?Sized>(&mut self, key: &'static str, _: &T) -> Result<()> {
        self.names.push(key.to_owned());
        Ok(())
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

impl SerializeStructVariant for NameFields<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_field<T: Serialize + ?Sized>(&mut self, key: &'static str, _: &T) -> Result<()> {
        self.names.push(key.to_owned());
        Ok(())
    }
    fn end(self) -> Result<()> {
        Ok(())
    }
}

struct KeyToString<'a> {
    out: &'a mut String,
}

impl Serializer for KeyToString<'_> {
    type Ok = ();
    type Error = Error;
    type SerializeSeq = Impossible<(), Error>;
    type SerializeTuple = Impossible<(), Error>;
    type SerializeTupleStruct = Impossible<(), Error>;
    type SerializeTupleVariant = Impossible<(), Error>;
    type SerializeMap = Impossible<(), Error>;
    type SerializeStruct = Impossible<(), Error>;
    type SerializeStructVariant = Impossible<(), Error>;

    fn serialize_str(self, v: &str) -> Result<()> {
        self.out.push_str(v);
        Ok(())
    }
    fn serialize_char(self, v: char) -> Result<()> {
        self.out.push(v);
        Ok(())
    }
    fn serialize_i64(self, v: i64) -> Result<()> {
        self.out.push_str(&v.to_string());
        Ok(())
    }
    fn serialize_u64(self, v: u64) -> Result<()> {
        self.out.push_str(&v.to_string());
        Ok(())
    }
    fn serialize_i32(self, v: i32) -> Result<()> {
        self.serialize_i64(v as i64)
    }
    fn serialize_u32(self, v: u32) -> Result<()> {
        self.serialize_u64(v as u64)
    }
    fn serialize_unit_variant(self, _: &'static str, _: u32, variant: &'static str) -> Result<()> {
        self.serialize_str(variant)
    }
    fn serialize_some<T: Serialize + ?Sized>(self, value: &T) -> Result<()> {
        value.serialize(self)
    }

    unsupported!(
        serialize_bool(bool),
        serialize_i8(i8),
        serialize_i16(i16),
        serialize_u8(u8),
        serialize_u16(u16),
        serialize_f32(f32),
        serialize_f64(f64),
        serialize_bytes(&[u8]),
        serialize_none(),
        serialize_unit(),
        serialize_unit_struct(&'static str)
    );
    unsupported_compound!();
}

// ---------------------------------------------------------------------------------------------
// `serialize_with` helpers for native Excel dates

#[cfg(feature = "chrono")]
pub use self::temporal::{ExcelTemporal, excel_date};

#[cfg(feature = "chrono")]
mod temporal {
    use chrono::{DateTime, Datelike, NaiveDate, NaiveDateTime, NaiveTime, TimeDelta, TimeZone, Timelike};
    use serde::Serializer;

    use super::{DURATION_MARKER, TEMPORAL_MARKER};

    /// Chrono values that [`excel_date`] can write as native Excel dates/times/durations.
    pub trait ExcelTemporal {
        #[doc(hidden)]
        fn serialize_excel<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error>;
    }

    /// ISO 8601 text formatted into a stack buffer (no allocation per value).
    struct Iso {
        buf: [u8; 32],
        len: usize,
    }

    impl Iso {
        fn new() -> Self {
            Iso { buf: [0; 32], len: 0 }
        }

        fn digits(&mut self, mut value: u32, width: usize) {
            for i in (0..width).rev() {
                self.buf[self.len + i] = b'0' + (value % 10) as u8;
                value /= 10;
            }
            self.len += width;
        }

        fn byte(&mut self, b: u8) {
            self.buf[self.len] = b;
            self.len += 1;
        }

        fn date(&mut self, d: &NaiveDate) -> bool {
            let year = d.year();
            if !(0..=9999).contains(&year) {
                return false;
            }
            self.digits(year as u32, 4);
            self.byte(b'-');
            self.digits(d.month(), 2);
            self.byte(b'-');
            self.digits(d.day(), 2);
            true
        }

        fn time(&mut self, t: &NaiveTime) {
            self.digits(t.hour(), 2);
            self.byte(b':');
            self.digits(t.minute(), 2);
            self.byte(b':');
            self.digits(t.second(), 2);
            let nanos = t.nanosecond().min(999_999_999);
            if nanos != 0 {
                self.byte(b'.');
                self.digits(nanos, 9);
            }
        }
    }

    impl serde::Serialize for Iso {
        fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
            // Only ASCII digits and separators are ever written.
            s.serialize_str(std::str::from_utf8(&self.buf[..self.len]).unwrap_or_default())
        }
    }

    impl ExcelTemporal for NaiveDate {
        fn serialize_excel<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
            let mut iso = Iso::new();
            if iso.date(self) { s.serialize_newtype_struct(TEMPORAL_MARKER, &iso) } else { s.serialize_str(&self.to_string()) }
        }
    }

    impl ExcelTemporal for NaiveDateTime {
        fn serialize_excel<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
            let mut iso = Iso::new();
            if iso.date(&self.date()) {
                iso.byte(b'T');
                iso.time(&self.time());
                s.serialize_newtype_struct(TEMPORAL_MARKER, &iso)
            } else {
                s.serialize_str(&self.to_string())
            }
        }
    }

    impl<Tz: TimeZone> ExcelTemporal for DateTime<Tz> {
        fn serialize_excel<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
            self.naive_local().serialize_excel(s)
        }
    }

    impl ExcelTemporal for NaiveTime {
        fn serialize_excel<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
            let mut iso = Iso::new();
            iso.time(self);
            s.serialize_newtype_struct(TEMPORAL_MARKER, &iso)
        }
    }

    impl ExcelTemporal for TimeDelta {
        /// Serialized as fractional seconds.
        fn serialize_excel<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
            let seconds = self.num_seconds() as f64 + self.subsec_nanos() as f64 / 1e9;
            s.serialize_newtype_struct(DURATION_MARKER, &seconds)
        }
    }

    impl<T: ExcelTemporal> ExcelTemporal for Option<T> {
        fn serialize_excel<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
            match self {
                Some(v) => v.serialize_excel(s),
                None => s.serialize_none(),
            }
        }
    }

    impl<T: ExcelTemporal + ?Sized> ExcelTemporal for &T {
        fn serialize_excel<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
            (**self).serialize_excel(s)
        }
    }

    /// `#[serde(serialize_with = "hypaxel::records::excel_date")]`: writes chrono dates, date-times,
    /// times and durations (and `Option`s of them) as native Excel values. Other serializers see an
    /// ISO 8601 string (durations: seconds as `f64`).
    pub fn excel_date<T: ExcelTemporal, S: Serializer>(value: &T, serializer: S) -> Result<S::Ok, S::Error> {
        value.serialize_excel(serializer)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil_days_match_chrono_convention() {
        assert_eq!(days_from_ce(1, 1, 1), 1);
        assert_eq!(days_from_ce(1899, 12, 30), 693_594);
        assert_eq!(days_from_ce(1904, 1, 1), 695_056);
        assert_eq!(days_from_ce(2024, 2, 29) - 693_594, 45_351);
    }

    #[test]
    fn parses_times() {
        assert_eq!(parse_time(b"12:00:00"), Some(43_200_000_000_000));
        assert_eq!(parse_time(b"00:00:01.5"), Some(1_500_000_000));
        assert_eq!(parse_time(b"00:00:00.000000001"), Some(1));
    }
}
