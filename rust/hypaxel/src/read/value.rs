//! Cell values produced by the reader.

use std::fmt;

pub use super::styles::DateKind;

/// The value of one cell, borrowed from the row (or the shared string table) it belongs to.
#[derive(Debug, Clone, Copy, PartialEq)]
#[non_exhaustive]
pub enum Value<'a> {
    /// No value. Returned for missing cells and cells that only carry formatting.
    Empty,
    /// Text: a shared string, an inline string or the cached result of a text formula.
    String(&'a str),
    /// A number that is not formatted as a date or time.
    Number(f64),
    /// `TRUE` or `FALSE`.
    Bool(bool),
    /// A number formatted as a date, time or duration.
    DateTime(ExcelDateTime),
    /// An error value such as `#DIV/0!` or `#N/A`.
    Error(&'a str),
}

impl<'a> Value<'a> {
    pub fn is_empty(&self) -> bool {
        matches!(self, Value::Empty)
    }

    /// The text of a string cell.
    pub fn as_str(&self) -> Option<&'a str> {
        match self {
            Value::String(s) => Some(s),
            _ => None,
        }
    }

    /// The number of a numeric cell; for date and time cells, the serial number.
    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Value::Number(n) => Some(*n),
            Value::DateTime(d) => Some(d.serial()),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Value::Bool(b) => Some(*b),
            _ => None,
        }
    }

    pub fn as_datetime(&self) -> Option<ExcelDateTime> {
        match self {
            Value::DateTime(d) => Some(*d),
            _ => None,
        }
    }
}

impl fmt::Display for Value<'_> {
    /// Formats the value roughly as Excel's General format would, with dates in ISO 8601.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Value::Empty => Ok(()),
            Value::String(s) | Value::Error(s) => f.write_str(s),
            Value::Number(n) => write!(f, "{n}"),
            Value::Bool(b) => f.write_str(if *b { "TRUE" } else { "FALSE" }),
            Value::DateTime(d) => fmt::Display::fmt(d, f),
        }
    }
}

/// A date, time or duration as Excel stores it: a serial number of days.
///
/// The serial is interpreted in the workbook's date system (1900, or 1904 for workbooks created by
/// old Mac versions of Excel). In the 1900 system Excel counts the nonexistent 1900-02-29 as
/// serial 60, so serials before 1900-03-01 are shifted by one day; the conversions account for it.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ExcelDateTime {
    serial: f64,
    kind: DateKind,
    is_1904: bool,
}

const MICROS_PER_DAY: f64 = 86_400_000_000.0;
const MILLIS_PER_DAY: f64 = 86_400_000.0;

impl ExcelDateTime {
    pub(crate) fn new(serial: f64, kind: DateKind, is_1904: bool) -> Self {
        Self { serial, kind, is_1904 }
    }

    /// The raw serial number (days, with the time of day as the fraction).
    pub fn serial(&self) -> f64 {
        self.serial
    }

    /// What the cell's number format shows: a date, a date and time, a time of day or a duration.
    pub fn kind(&self) -> DateKind {
        self.kind
    }

    /// True for workbooks that use the 1904 date system.
    pub fn is_1904(&self) -> bool {
        self.is_1904
    }

    /// Splits the serial into whole days since 0001-01-01 (day 1) and microseconds into the day.
    /// Returns `None` for negative serials, the fictitious 1900-02-29 and non-finite values.
    ///
    /// Times are rounded to the millisecond. Serials carry about 15 significant digits, so finer
    /// digits are noise (writers such as Excel and Hypaxel store about 1e-10 day, or 8.6 µs).
    pub fn to_days_and_micros(&self) -> Option<(i64, u64)> {
        if !self.serial.is_finite() || self.serial < 0.0 || self.serial >= 3_000_000.0 {
            return None;
        }
        // Split before scaling: the whole serial times the units per day would lose precision.
        let whole = self.serial.floor();
        let mut day = whole as i64;
        let mut micros = ((self.serial - whole) * MILLIS_PER_DAY).round() as u64 * 1000;
        if micros >= MICROS_PER_DAY as u64 {
            day += 1;
            micros -= MICROS_PER_DAY as u64;
        }
        let days_from_ce = if self.is_1904 {
            EPOCH_1904 + day
        } else if day < 60 {
            EPOCH_1900 + day + 1
        } else if day == 60 {
            return None;
        } else {
            EPOCH_1900 + day
        };
        Some((days_from_ce, micros))
    }

    /// The value as elapsed microseconds (rounded to the millisecond), independent of the date system.
    pub fn to_micros(&self) -> Option<i64> {
        if !self.serial.is_finite() || self.serial.abs() >= 100_000_000.0 {
            return None;
        }
        let whole = self.serial.trunc();
        Some(whole as i64 * MICROS_PER_DAY as i64 + ((self.serial - whole) * MILLIS_PER_DAY).round() as i64 * 1000)
    }
}

/// Days from 0001-01-01 (day 1) to 1899-12-30, the day before Excel's serial 1 (after 1900-02-28).
pub(crate) const EPOCH_1900: i64 = 693_594;
/// Days from 0001-01-01 (day 1) to 1904-01-01, serial 0 in the 1904 date system.
pub(crate) const EPOCH_1904: i64 = 695_056;

/// Days since 0001-01-01 (where 0001-01-01 is day 1), proleptic Gregorian.
pub(crate) fn days_from_ce(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m as i64 + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468 + 719_163
}

fn civil_from_days(days_from_ce: i64) -> (i64, u32, u32) {
    let z = days_from_ce - 719_163 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    (y, m, d)
}

/// Converts an ISO 8601 date, date-time or time (`t="d"` cells) to a serial number.
pub(crate) fn iso_to_serial(s: &str, is_1904: bool) -> Option<(f64, DateKind)> {
    let s = s.trim().trim_end_matches('Z');
    let (date, time) = match s.split_once('T') {
        Some((d, t)) => (Some(d), Some(t)),
        None if s.contains(':') => (None, Some(s)),
        None => (Some(s), None),
    };
    let day = match date {
        Some(d) => {
            let mut parts = d.splitn(3, '-');
            let y: i64 = parts.next()?.parse().ok()?;
            let m: u32 = parts.next()?.parse().ok()?;
            let dd: u32 = parts.next()?.parse().ok()?;
            if !(1..=12).contains(&m) || !(1..=31).contains(&dd) {
                return None;
            }
            let days = days_from_ce(y, m, dd);
            if is_1904 {
                days - EPOCH_1904
            } else {
                let day = days - EPOCH_1900;
                if day < 61 { day - 1 } else { day }
            }
        }
        None => 0,
    };
    let fraction = match time {
        Some(t) => {
            let mut parts = t.splitn(3, ':');
            let h: f64 = parts.next()?.parse().ok()?;
            let m: f64 = parts.next()?.parse().ok()?;
            let sec: f64 = parts.next().map_or(Some(0.0), |p| p.parse().ok())?;
            (h * 3600.0 + m * 60.0 + sec) / 86_400.0
        }
        None => 0.0,
    };
    let kind = match (date, time) {
        (Some(_), Some(_)) => DateKind::DateTime,
        (Some(_), None) => DateKind::Date,
        _ => DateKind::Time,
    };
    Some((day as f64 + fraction, kind))
}

impl fmt::Display for ExcelDateTime {
    /// ISO 8601 (`2024-05-01`, `2024-05-01T08:30:00`, `08:30:00`), or the serial number if it is
    /// not a representable date.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let time = |f: &mut fmt::Formatter<'_>, micros: u64| {
            let secs = micros / 1_000_000;
            write!(f, "{:02}:{:02}:{:02}", secs / 3600, secs / 60 % 60, secs % 60)?;
            match micros % 1_000_000 {
                0 => Ok(()),
                frac if frac % 1000 == 0 => write!(f, ".{:03}", frac / 1000),
                frac => write!(f, ".{frac:06}"),
            }
        };
        if self.kind == DateKind::Duration {
            return write!(f, "{}", self.serial);
        }
        let Some((days, micros)) = self.to_days_and_micros() else {
            return write!(f, "{}", self.serial);
        };
        if self.kind == DateKind::Time && self.serial < 1.0 {
            return time(f, micros);
        }
        let (y, m, d) = civil_from_days(days);
        write!(f, "{y:04}-{m:02}-{d:02}")?;
        if self.kind != DateKind::Date || micros != 0 {
            f.write_str("T")?;
            time(f, micros)?;
        }
        Ok(())
    }
}

#[cfg(feature = "chrono")]
mod chrono_impls {
    use chrono::{NaiveDate, NaiveDateTime, NaiveTime, TimeDelta};

    use super::*;

    impl ExcelDateTime {
        /// The date and time this serial stands for.
        pub fn to_naive_datetime(&self) -> Option<NaiveDateTime> {
            let (days, micros) = self.to_days_and_micros()?;
            let date = NaiveDate::from_num_days_from_ce_opt(i32::try_from(days).ok()?)?;
            let time = NaiveTime::from_num_seconds_from_midnight_opt((micros / 1_000_000) as u32, (micros % 1_000_000) as u32 * 1000)?;
            Some(date.and_time(time))
        }

        /// The calendar date (the time of day is dropped).
        pub fn to_naive_date(&self) -> Option<NaiveDate> {
            self.to_naive_datetime().map(|dt| dt.date())
        }

        /// The time of day (the date is dropped).
        pub fn to_naive_time(&self) -> Option<NaiveTime> {
            self.to_naive_datetime().map(|dt| dt.time())
        }

        /// The serial as elapsed time, as used by `[h]:mm:ss` formats.
        pub fn to_duration(&self) -> Option<TimeDelta> {
            Some(TimeDelta::microseconds(self.to_micros()?))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dt(serial: f64, kind: DateKind) -> String {
        ExcelDateTime::new(serial, kind, false).to_string()
    }

    #[test]
    fn converts_1900_serials_like_excel() {
        assert_eq!(dt(1.0, DateKind::Date), "1900-01-01");
        assert_eq!(dt(59.0, DateKind::Date), "1900-02-28");
        assert_eq!(dt(60.0, DateKind::Date), "60");
        assert_eq!(dt(61.0, DateKind::Date), "1900-03-01");
        assert_eq!(dt(45_413.0, DateKind::Date), "2024-05-01");
        assert_eq!(dt(45_413.75, DateKind::DateTime), "2024-05-01T18:00:00");
        assert_eq!(dt(45_413.5 + 0.5 / 86_400.0, DateKind::DateTime), "2024-05-01T12:00:00.500");
        assert_eq!(dt(0.25, DateKind::Time), "06:00:00");
        assert_eq!(dt(1.5, DateKind::Duration), "1.5");
        assert_eq!(dt(-1.0, DateKind::Date), "-1");
        assert_eq!(ExcelDateTime::new(0.0, DateKind::Date, true).to_string(), "1904-01-01");
    }

    #[test]
    fn parses_iso_dates_consistently_with_serials() {
        for (serial, kind) in [(1.0, DateKind::Date), (59.0, DateKind::Date), (61.0, DateKind::Date), (45_413.75, DateKind::DateTime)] {
            let text = dt(serial, kind);
            assert_eq!(iso_to_serial(&text, false), Some((serial, kind)), "{text}");
        }
        assert_eq!(iso_to_serial("06:00:00", false), Some((0.25, DateKind::Time)));
        assert_eq!(iso_to_serial("1904-01-02", true), Some((1.0, DateKind::Date)));
        assert_eq!(iso_to_serial("not a date", false), None);
    }

    #[test]
    fn civil_round_trip() {
        for days in [1, 365, 693_594, 738_000, 1_000_000] {
            let (y, m, d) = civil_from_days(days);
            assert_eq!(days_from_ce(y, m, d), days);
        }
    }

    #[cfg(feature = "chrono")]
    #[test]
    fn converts_to_chrono() {
        let d = ExcelDateTime::new(45_413.75, DateKind::DateTime, false);
        assert_eq!(d.to_naive_datetime().unwrap().to_string(), "2024-05-01 18:00:00");
        assert_eq!(ExcelDateTime::new(1.5, DateKind::Duration, false).to_duration(), Some(chrono::TimeDelta::hours(36)));
    }
}
