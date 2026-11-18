//! Conversions from Rust values to cells and rows.

use std::borrow::Cow;

use crate::engine::CellWriter;
use crate::style::StyleId;

/// A value that can be written as a single cell.
///
/// Implemented for strings, all primitive numbers, `bool`, `Option<T>` (None = empty cell),
/// [`Formula`], [`Blank`], [`Styled`] and, with the `chrono` feature, chrono's date/time types.
/// Custom types implement it by delegating to an existing implementation.
pub trait IntoCell {
    /// Writes the value as the next cell of the current row.
    fn write_cell(self, w: &mut CellWriter, style: StyleId);
}

/// A formula such as `Formula("SUM(A1:A9)")` (leading `=` optional). Excel computes it on open.
#[derive(Debug, Clone, Copy)]
pub struct Formula<'a>(pub &'a str);

/// An empty cell; with a style it carries the format (e.g. a border).
#[derive(Debug, Clone, Copy, Default)]
pub struct Blank;

/// A value with its own style, e.g. `Styled(1234.5, money)`. Useful in rows: `(name, Styled(total, money))`.
#[derive(Debug, Clone, Copy)]
pub struct Styled<T>(pub T, pub StyleId);

impl<T: IntoCell> IntoCell for Styled<T> {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, _style: StyleId) {
        self.0.write_cell(w, self.1);
    }
}

impl IntoCell for Formula<'_> {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_formula(self.0, style);
    }
}

impl IntoCell for Blank {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_blank(style);
    }
}

impl IntoCell for &str {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_str(self, style);
    }
}

impl IntoCell for String {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_str(&self, style);
    }
}

impl IntoCell for &String {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_str(self, style);
    }
}

impl IntoCell for Cow<'_, str> {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_str(&self, style);
    }
}

impl IntoCell for &Cow<'_, str> {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_str(self, style);
    }
}

impl IntoCell for char {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_str(self.encode_utf8(&mut [0; 4]), style);
    }
}

impl<T: IntoCell> IntoCell for Option<T> {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        match self {
            Some(v) => v.write_cell(w, style),
            None => w.write_blank(style),
        }
    }
}

impl<'a, T> IntoCell for &'a Option<T>
where
    &'a T: IntoCell,
{
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        match self {
            Some(v) => v.write_cell(w, style),
            None => w.write_blank(style),
        }
    }
}

macro_rules! signed {
    ($($t:ty),*) => {$(
        impl IntoCell for $t {
            #[inline]
            fn write_cell(self, w: &mut CellWriter, style: StyleId) { w.write_i64(self as i64, style); }
        }
        impl IntoCell for &$t {
            #[inline]
            fn write_cell(self, w: &mut CellWriter, style: StyleId) { w.write_i64(*self as i64, style); }
        }
    )*};
}

macro_rules! unsigned {
    ($($t:ty),*) => {$(
        impl IntoCell for $t {
            #[inline]
            fn write_cell(self, w: &mut CellWriter, style: StyleId) { w.write_u64(self as u64, style); }
        }
        impl IntoCell for &$t {
            #[inline]
            fn write_cell(self, w: &mut CellWriter, style: StyleId) { w.write_u64(*self as u64, style); }
        }
    )*};
}

macro_rules! copied {
    ($($t:ty),*) => {$(
        impl IntoCell for &$t {
            #[inline]
            fn write_cell(self, w: &mut CellWriter, style: StyleId) { (*self).write_cell(w, style); }
        }
    )*};
}

signed!(i8, i16, i32, i64, isize);
unsigned!(u8, u16, u32, u64, usize);
copied!(f32, f64, bool, char);

impl IntoCell for i128 {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_f64(self as f64, style);
    }
}

impl IntoCell for u128 {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_f64(self as f64, style);
    }
}

impl IntoCell for f64 {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_f64(self, style);
    }
}

impl IntoCell for f32 {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        // Go through the shortest decimal form of the f32 so 0.1f32 is written as 0.1, not 0.10000000149.
        let mut buf = zmij::Buffer::new();
        let value = if self.is_finite() { buf.format_finite(self).parse::<f64>().unwrap_or(self as f64) } else { self as f64 };
        w.write_f64(value, style);
    }
}

impl IntoCell for bool {
    #[inline]
    fn write_cell(self, w: &mut CellWriter, style: StyleId) {
        w.write_bool(self, style);
    }
}

/// A complete row: tuples of [`IntoCell`] values (up to 16 elements), arrays, `Vec`s and slices.
///
/// ```
/// # use hypaxel::{XlsxWriter, Formula};
/// # let mut writer = XlsxWriter::new(Vec::new());
/// # let mut sheet = writer.add_worksheet("S").unwrap();
/// sheet.add_row(("Widget", 3, 9.99, Formula("B1*C1")))?;
/// sheet.add_row(["a", "b", "c"])?;
/// # Ok::<(), hypaxel::Error>(())
/// ```
pub trait IntoRow {
    /// Writes the cells into the current (already started) row.
    fn write_cells(self, w: &mut CellWriter);
}

macro_rules! tuple_row {
    ($($name:ident)+) => {
        impl<$($name: IntoCell),+> IntoRow for ($($name,)+) {
            #[inline]
            #[allow(non_snake_case)]
            fn write_cells(self, w: &mut CellWriter) {
                let ($($name,)+) = self;
                $($name.write_cell(w, StyleId::DEFAULT);)+
            }
        }
    };
}

tuple_row!(A);
tuple_row!(A B);
tuple_row!(A B C);
tuple_row!(A B C D);
tuple_row!(A B C D E);
tuple_row!(A B C D E F);
tuple_row!(A B C D E F G);
tuple_row!(A B C D E F G H);
tuple_row!(A B C D E F G H I);
tuple_row!(A B C D E F G H I J);
tuple_row!(A B C D E F G H I J K);
tuple_row!(A B C D E F G H I J K L);
tuple_row!(A B C D E F G H I J K L M);
tuple_row!(A B C D E F G H I J K L M N);
tuple_row!(A B C D E F G H I J K L M N O);
tuple_row!(A B C D E F G H I J K L M N O P);

impl<T: IntoCell, const N: usize> IntoRow for [T; N] {
    #[inline]
    fn write_cells(self, w: &mut CellWriter) {
        for v in self {
            v.write_cell(w, StyleId::DEFAULT);
        }
    }
}

impl<T: IntoCell> IntoRow for Vec<T> {
    #[inline]
    fn write_cells(self, w: &mut CellWriter) {
        for v in self {
            v.write_cell(w, StyleId::DEFAULT);
        }
    }
}

impl<'a, T> IntoRow for &'a [T]
where
    &'a T: IntoCell,
{
    #[inline]
    fn write_cells(self, w: &mut CellWriter) {
        for v in self {
            v.write_cell(w, StyleId::DEFAULT);
        }
    }
}

impl<'a, T> IntoRow for &'a Vec<T>
where
    &'a T: IntoCell,
{
    #[inline]
    fn write_cells(self, w: &mut CellWriter) {
        self.as_slice().write_cells(w);
    }
}

#[cfg(feature = "chrono")]
mod chrono_impls {
    use chrono::{DateTime, Datelike, NaiveDate, NaiveDateTime, NaiveTime, TimeDelta, TimeZone, Timelike};

    use super::*;
    use crate::style::ValueKind;

    const NANOS_PER_DAY: f64 = 86_400_000_000_000.0;

    #[inline]
    fn nanos_of_day(t: &NaiveTime) -> u64 {
        // A leap second is reported as nanosecond >= 1e9; clamp it into the same second.
        t.num_seconds_from_midnight() as u64 * 1_000_000_000 + t.nanosecond().min(999_999_999) as u64
    }

    impl IntoCell for NaiveDate {
        /// Written as a date serial with the viewer's short-date format unless styled.
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            w.write_temporal(self.num_days_from_ce() as i64, 0, ValueKind::Date, style, || self.to_string());
        }
    }

    impl IntoCell for NaiveDateTime {
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            w.write_temporal(self.date().num_days_from_ce() as i64, nanos_of_day(&self.time()), ValueKind::DateTime, style, || {
                self.format("%Y-%m-%dT%H:%M:%S%.f").to_string()
            });
        }
    }

    impl<Tz: TimeZone> IntoCell for DateTime<Tz> {
        /// Written using the local clock time of its offset (Excel has no time zones).
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            self.naive_local().write_cell(w, style);
        }
    }

    impl IntoCell for NaiveTime {
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            w.write_days(nanos_of_day(&self) as f64 / NANOS_PER_DAY, ValueKind::Time, style);
        }
    }

    impl IntoCell for TimeDelta {
        /// Written as a number of days with the `[h]:mm:ss` format unless styled.
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            let days = self.num_seconds() as f64 / 86_400.0 + self.subsec_nanos() as f64 / NANOS_PER_DAY;
            w.write_days(days, ValueKind::Duration, style);
        }
    }

    impl IntoCell for &NaiveDate {
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            (*self).write_cell(w, style);
        }
    }

    impl IntoCell for &NaiveDateTime {
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            (*self).write_cell(w, style);
        }
    }

    impl<Tz: TimeZone> IntoCell for &DateTime<Tz> {
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            self.naive_local().write_cell(w, style);
        }
    }

    impl IntoCell for &NaiveTime {
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            (*self).write_cell(w, style);
        }
    }

    impl IntoCell for &TimeDelta {
        #[inline]
        fn write_cell(self, w: &mut CellWriter, style: StyleId) {
            (*self).write_cell(w, style);
        }
    }
}
