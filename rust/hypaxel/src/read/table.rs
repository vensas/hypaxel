//! Reads a table part (`xl/tables/tableN.xml`) into the [`Table`] the writer accepts.

use std::borrow::Cow;
use std::io::Read;

use super::styles::parse_u32;
use super::xml::{Event, Tag, XmlReader, decode_into};
use crate::engine::{MAX_COLUMNS, MAX_ROWS};
use crate::error::Result;
use crate::table::{Table, TableStyle};

pub(crate) fn parse<S: Read>(xml: &mut XmlReader<S>) -> Result<Table> {
    let mut table: Option<Table> = None;
    loop {
        let (start, tag) = match xml.next()? {
            Event::Start(t) => (true, t),
            Event::Empty(t) => (false, t),
            Event::Eof => break,
            _ => continue,
        };
        let mut descend = false;
        let mut problem = None;
        match (&mut table, tag.name) {
            (None, b"table") => match tag.attr(b"ref").and_then(parse_range) {
                Some((r1, c1, r2, c2)) => {
                    let mut t = Table::new(r1, c1, r2, c2).style(TableStyle::None).autofilter(false).row_stripes(false);
                    t.name = text(&tag, b"displayName", false).or_else(|| text(&tag, b"name", false));
                    t.header_row = tag.attr(b"headerRowCount").is_none_or(|v| v.trim_ascii() != b"0");
                    t.totals_row = tag.attr(b"totalsRowCount").and_then(parse_u32).is_some_and(|n| n > 0);
                    table = Some(t);
                    descend = true;
                }
                None => problem = Some("the table has no valid cell range"),
            },
            (None, _) => problem = Some("the part is not a table"),
            (Some(t), b"autoFilter") => t.autofilter = true,
            (Some(_), b"tableColumns") => descend = true,
            (Some(t), b"tableColumn") => {
                if t.columns.len() >= MAX_COLUMNS as usize {
                    problem = Some("the table has more than 16384 columns");
                } else {
                    t.columns.push(Cow::Owned(text(&tag, b"name", true).unwrap_or_default()));
                }
            }
            (Some(t), b"tableStyleInfo") => {
                t.style = tag.attr(b"name").and_then(parse_style).unwrap_or(TableStyle::None);
                t.row_stripes = flag(&tag, b"showRowStripes");
                t.column_stripes = flag(&tag, b"showColumnStripes");
                t.first_column_highlight = flag(&tag, b"showFirstColumn");
                t.last_column_highlight = flag(&tag, b"showLastColumn");
            }
            _ => {}
        }
        if let Some(problem) = problem {
            return Err(xml.malformed(problem));
        }
        if start && !descend {
            xml.skip_element()?;
        }
    }
    table.ok_or_else(|| xml.malformed("the part is not a table"))
}

fn text(tag: &Tag<'_>, name: &[u8], excel_escapes: bool) -> Option<String> {
    let mut out = String::new();
    decode_into(&mut out, tag.attr(name)?, true, excel_escapes).ok()?;
    Some(out)
}

fn flag(tag: &Tag<'_>, name: &[u8]) -> bool {
    is_true(tag.attr(name))
}

pub(crate) fn is_true(raw: Option<&[u8]>) -> bool {
    matches!(raw.map(<[u8]>::trim_ascii), Some(b"1" | b"true"))
}

/// Maps a built-in style name such as `TableStyleMedium9`; custom styles have no counterpart.
fn parse_style(raw: &[u8]) -> Option<TableStyle> {
    let name = std::str::from_utf8(raw).ok()?.trim().strip_prefix("TableStyle")?;
    let digits = name.find(|c: char| c.is_ascii_digit())?;
    let number: u8 = name[digits..].parse().ok()?;
    match (&name[..digits], number) {
        ("Light", 1..=21) => Some(TableStyle::Light(number)),
        ("Medium", 1..=28) => Some(TableStyle::Medium(number)),
        ("Dark", 1..=11) => Some(TableStyle::Dark(number)),
        _ => None,
    }
}

/// Parses `A1:C10` (or a single cell) into `(first row, first column, last row, last column)`.
fn parse_range(raw: &[u8]) -> Option<(u32, u32, u32, u32)> {
    let raw = raw.trim_ascii();
    let (first, last) = match raw.iter().position(|&b| b == b':') {
        Some(i) => (&raw[..i], &raw[i + 1..]),
        None => (raw, raw),
    };
    let (r1, c1) = parse_cell(first)?;
    let (r2, c2) = parse_cell(last)?;
    (r1 <= r2 && c1 <= c2).then_some((r1, c1, r2, c2))
}

fn parse_cell(raw: &[u8]) -> Option<(u32, u32)> {
    let mut column = 0u32;
    let mut row = 0u32;
    let mut letters = 0;
    let mut digits = 0;
    for &b in raw {
        match b {
            b'$' => {}
            b'A'..=b'Z' | b'a'..=b'z' if digits == 0 && letters < 3 => {
                letters += 1;
                column = column * 26 + (b.to_ascii_uppercase() - b'A' + 1) as u32;
            }
            b'0'..=b'9' if letters > 0 && digits < 7 => {
                digits += 1;
                row = row * 10 + (b - b'0') as u32;
            }
            _ => return None,
        }
    }
    ((1..=MAX_ROWS).contains(&row) && (1..=MAX_COLUMNS).contains(&column)).then_some((row, column))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ranges() {
        assert_eq!(parse_range(b"A1:C10"), Some((1, 1, 10, 3)));
        assert_eq!(parse_range(b"$B$2:$XFD$1048576"), Some((2, 2, 1_048_576, 16_384)));
        assert_eq!(parse_range(b"d4"), Some((4, 4, 4, 4)));
        for bad in ["", "A", "1", "A0", "XFE1", "A1048577", "C3:A1", "A1:B", "A1:B2:C3", "1A"] {
            assert_eq!(parse_range(bad.as_bytes()), None, "{bad}");
        }
    }

    #[test]
    fn maps_built_in_styles() {
        assert_eq!(parse_style(b"TableStyleMedium9"), Some(TableStyle::Medium(9)));
        assert_eq!(parse_style(b"TableStyleLight21"), Some(TableStyle::Light(21)));
        assert_eq!(parse_style(b"TableStyleDark11"), Some(TableStyle::Dark(11)));
        for custom in ["TableStyleDark12", "TableStyleMedium", "My Style", "PivotStyleLight16", ""] {
            assert_eq!(parse_style(custom.as_bytes()), None, "{custom}");
        }
    }
}
