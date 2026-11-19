//! Which cell styles format numbers as dates, times or durations.

use std::io::Read;

use super::xml::{Event, XmlReader, decode_into};
use crate::error::Result;

/// How a number format presents a serial value.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum DateKind {
    /// A calendar date without a time of day (e.g. `yyyy-mm-dd`).
    Date,
    /// A date with a time of day (e.g. `yyyy-mm-dd hh:mm`).
    DateTime,
    /// A time of day (e.g. `hh:mm:ss`).
    Time,
    /// Elapsed time (e.g. `[h]:mm:ss`).
    Duration,
}

/// Classifies a built-in number format id.
pub(crate) fn builtin_kind(id: u32) -> Option<DateKind> {
    match id {
        14..=17 | 27..=31 | 34..=36 | 50..=54 | 57 | 58 => Some(DateKind::Date),
        22 => Some(DateKind::DateTime),
        18..=21 | 32 | 33 | 45 | 47 | 55 | 56 => Some(DateKind::Time),
        46 => Some(DateKind::Duration),
        _ => None,
    }
}

/// Classifies a format code such as `yyyy-mm-dd`, `[h]:mm` or `#,##0.00 "days"`.
///
/// Only the first section (positive numbers) is considered. Quoted text, escaped characters,
/// padding/fill characters and bracketed colors, conditions and locales are ignored.
pub(crate) fn format_kind(code: &str) -> Option<DateKind> {
    let b = code.as_bytes();
    let (mut date, mut time, mut minute_or_month, mut elapsed) = (false, false, false, false);
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b';' => break,
            b'"' => {
                i += 1;
                while i < b.len() && b[i] != b'"' {
                    i += 1;
                }
            }
            b'\\' | b'_' | b'*' => i += 1,
            b'[' => {
                let close = b[i..].iter().position(|&c| c == b']').map_or(b.len(), |n| i + n);
                let inner = &b[i + 1..close];
                elapsed |= !inner.is_empty() && inner.iter().all(|c| matches!(c.to_ascii_lowercase(), b'h' | b'm' | b's'));
                i = close;
            }
            _ if b[i..].len() >= 7 && b[i..i + 7].eq_ignore_ascii_case(b"general") => i += 6,
            b'y' | b'Y' | b'd' | b'D' | b'e' => date = true,
            b'h' | b'H' | b's' | b'S' => time = true,
            b'm' | b'M' => minute_or_month = true,
            _ => {}
        }
        i += 1;
    }
    // `m` is a month next to dates and a minute next to hours or seconds; alone it is a month.
    date |= minute_or_month && !time;
    if elapsed {
        Some(DateKind::Duration)
    } else if date && time {
        Some(DateKind::DateTime)
    } else if date {
        Some(DateKind::Date)
    } else if time {
        Some(DateKind::Time)
    } else {
        None
    }
}

/// Date classification of every cell format (`cellXfs` entry) in the workbook.
#[derive(Debug, Clone, Default)]
pub(crate) struct Styles {
    kinds: Vec<Option<DateKind>>,
}

impl Styles {
    pub fn date_kind(&self, style: u32) -> Option<DateKind> {
        self.kinds.get(style as usize).copied().flatten()
    }

    pub fn parse<S: Read>(xml: &mut XmlReader<S>) -> Result<Self> {
        let mut custom: Vec<(u32, Option<DateKind>)> = Vec::new();
        let mut kinds = Vec::new();
        let mut in_cell_xfs = false;
        let mut code = String::new();
        loop {
            let (tag, start) = match xml.next()? {
                Event::Start(t) => (t, true),
                Event::Empty(t) => (t, false),
                Event::End(name) if name == b"cellXfs" => {
                    in_cell_xfs = false;
                    continue;
                }
                Event::Eof => break,
                _ => continue,
            };
            match tag.name {
                b"numFmt" => {
                    let id = tag.attr(b"numFmtId").and_then(parse_u32);
                    code.clear();
                    let decoded = tag.attr(b"formatCode").map(|raw| decode_into(&mut code, raw, true, false));
                    if let (Some(id), Some(Ok(()))) = (id, decoded) {
                        custom.push((id, format_kind(&code)));
                    }
                }
                b"cellXfs" if start => in_cell_xfs = true,
                b"xf" if in_cell_xfs => {
                    let id = tag.attr(b"numFmtId").and_then(parse_u32).unwrap_or(0);
                    let kind = custom.iter().rev().find(|(i, _)| *i == id).map_or_else(|| builtin_kind(id), |(_, k)| *k);
                    kinds.push(kind);
                }
                _ => {}
            }
            if start && tag.name != b"cellXfs" && tag.name != b"styleSheet" && tag.name != b"numFmts" {
                xml.skip_element()?;
            }
        }
        Ok(Styles { kinds })
    }
}

pub(crate) fn parse_u32(raw: &[u8]) -> Option<u32> {
    std::str::from_utf8(raw).ok()?.trim().parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_format_codes() {
        use DateKind::*;
        let cases: &[(&str, Option<DateKind>)] = &[
            ("yyyy-mm-dd", Some(Date)),
            ("d/m/yy", Some(Date)),
            ("mmm yyyy", Some(Date)),
            ("[$-409]mmmm d, yyyy;@", Some(Date)),
            ("yyyy-mm-dd hh:mm:ss", Some(DateTime)),
            ("m/d/yy h:mm AM/PM", Some(DateTime)),
            ("hh:mm", Some(Time)),
            ("h:mm:ss AM/PM", Some(Time)),
            ("mm:ss.0", Some(Time)),
            ("[h]:mm:ss", Some(Duration)),
            ("[mm]:ss", Some(Duration)),
            ("General", None),
            ("0.00", None),
            ("#,##0.00 \"days\"", None),
            ("[Red]#,##0;[Blue]-#,##0", None),
            ("_(* #,##0_);_(* (#,##0);_(* \"-\"??_);_(@_)", None),
            ("0.00E+00", None),
            ("@", None),
            ("\"Date:\" @", None),
            ("0 \\d", None),
            ("General;yyyy", None),
        ];
        for (code, expected) in cases {
            assert_eq!(format_kind(code), *expected, "{code}");
        }
    }

    #[test]
    fn parses_cell_formats() {
        let xml = r#"<styleSheet xmlns="x"><numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy&quot;-&quot;mm"/><numFmt numFmtId="165" formatCode="0.0"/></numFmts>
            <fonts><font><sz val="11"/></font></fonts>
            <cellStyleXfs count="1"><xf numFmtId="14"/></cellStyleXfs>
            <cellXfs count="5"><xf numFmtId="0"/><xf numFmtId="164" applyNumberFormat="1"/><xf numFmtId="165"/><xf numFmtId="22"><alignment/></xf><xf numFmtId="46"/></cellXfs></styleSheet>"#;
        let styles = Styles::parse(&mut XmlReader::new(xml.as_bytes(), "styles.xml")).unwrap();
        assert_eq!(styles.kinds, [None, Some(DateKind::Date), None, Some(DateKind::DateTime), Some(DateKind::Duration)]);
        assert_eq!(styles.date_kind(99), None);
    }
}
