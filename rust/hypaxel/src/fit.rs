//! Column auto-fit: width estimates for cell content in pixels of Calibri 11 at 96 DPI, the
//! metric Excel's column widths are defined in (a width of `w` characters is `7w + 5` pixels).

use std::collections::BTreeMap;

use crate::options::{AutoFit, Column};
use crate::style::StyleRegistry;
use crate::xml;

/// The sample stops early once the held-back XML reaches this size.
pub(crate) const MAX_SAMPLE_BYTES: usize = 8 * 1024 * 1024;

/// Calibri 11 advance widths in pixels for ASCII 0x20..=0x7E.
#[rustfmt::skip]
const ASCII_PX: [u8; 95] = [
    3, 3, 4, 7, 7, 10, 10, 2, 4, 4, 7, 7, 3, 4, 3, 5,  // space ! " # $ % & ' ( ) * + , - . /
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7,                      // 0-9
    3, 3, 7, 7, 7, 6, 13,                              // : ; < = > ? @
    9, 8, 8, 9, 7, 7, 9, 9, 4, 5, 8, 6, 12, 10, 10, 8, 10, 8, 7, 7, 9, 9, 13, 8, 7, 7, // A-Z
    4, 5, 4, 7, 7, 4,                                  // [ \ ] ^ _ `
    7, 8, 6, 8, 8, 5, 7, 8, 4, 4, 7, 4, 12, 8, 8, 8, 8, 5, 6, 5, 8, 7, 11, 7, 7, 6,   // a-z
    5, 7, 5, 7,                                        // { | } ~
];

const DIGIT_PX: f32 = 7.0;

#[inline]
fn char_px(c: char) -> f32 {
    let code = c as u32;
    if (0x20..0x7F).contains(&code) {
        return ASCII_PX[(code - 0x20) as usize] as f32;
    }
    if is_wide(code) { 15.0 } else { 8.0 }
}

/// East Asian wide and fullwidth characters, plus emoji, which Excel renders about two digits wide.
fn is_wide(code: u32) -> bool {
    matches!(code,
        0x1100..=0x115F | 0x2E80..=0x303E | 0x3041..=0x33FF | 0x3400..=0x4DBF | 0x4E00..=0x9FFF
        | 0xA000..=0xA4CF | 0xAC00..=0xD7A3 | 0xF900..=0xFAFF | 0xFE30..=0xFE4F | 0xFF00..=0xFF60
        | 0xFFE0..=0xFFE6 | 0x1F300..=0x1F64F | 0x1F900..=0x1F9FF | 0x20000..=0x3FFFD)
}

/// Width of the longest line of `text`.
pub(crate) fn text_px(text: &str) -> f32 {
    let mut widest: f32 = 0.0;
    let mut line = 0.0;
    for c in text.chars() {
        if c == '\n' {
            widest = widest.max(line);
            line = 0.0;
        } else if c != '\r' {
            line += char_px(c);
        }
    }
    widest.max(line)
}

/// How a style renders numbers, reduced to what matters for width.
#[derive(Clone, Copy)]
enum Shape {
    /// General (or text) format: up to 11 characters of the shortest representation.
    General,
    /// A fixed-width rendering such as a date or scientific notation.
    Constant(f32),
    Fixed {
        decimals: u8,
        min_integer_digits: u8,
        thousands: bool,
        percent: u8,
        literal_px: f32,
    },
}

#[derive(Clone, Copy)]
struct StyleFit {
    scale: f32,
    shape: Shape,
}

impl StyleFit {
    fn of(styles: &StyleRegistry, style: u32) -> StyleFit {
        let (size_centipoints, bold, format) = styles.measure_info(style);
        let mut scale = size_centipoints as f32 / 1100.0;
        if bold {
            scale *= 1.1;
        }
        let shape = match format {
            NumFormat::Builtin(id) => builtin_shape(id),
            NumFormat::Custom(code) => custom_shape(code),
        };
        StyleFit { scale, shape }
    }
}

pub(crate) enum NumFormat<'a> {
    Builtin(u32),
    Custom(&'a str),
}

fn builtin_shape(id: u32) -> Shape {
    let fixed = |decimals, thousands, percent| Shape::Fixed { decimals, min_integer_digits: 1, thousands, percent, literal_px: 0.0 };
    match id {
        1 => fixed(0, false, 0),
        2 => fixed(2, false, 0),
        3 => fixed(0, true, 0),
        4 => fixed(2, true, 0),
        9 => fixed(0, false, 1),
        10 => fixed(2, false, 1),
        11 => Shape::Constant(text_px("1.00E+00")),
        14 => Shape::Constant(text_px("12/31/2026")),
        21 => Shape::Constant(text_px("23:59:59")),
        22 => Shape::Constant(text_px("12/31/2026 23:59")),
        46 => Shape::Constant(text_px("100:00:00")),
        _ => Shape::General,
    }
}

/// Estimates the rendering of a custom format code from its first section.
fn custom_shape(code: &str) -> Shape {
    let section = code.split(';').next().unwrap_or("");
    if section.eq_ignore_ascii_case("general") || section == "@" || section.is_empty() {
        return Shape::General;
    }

    // Separate literal text from format tokens.
    let mut tokens = String::new();
    let mut literal_px = 0.0;
    let mut chars = section.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '"' => {
                for q in chars.by_ref() {
                    if q == '"' {
                        break;
                    }
                    literal_px += char_px(q);
                }
            }
            '\\' => literal_px += chars.next().map_or(0.0, char_px),
            '_' => literal_px += chars.next().map_or(0.0, char_px),
            '*' => {
                chars.next();
            }
            '[' => {
                let mut inner = String::new();
                for b in chars.by_ref() {
                    if b == ']' {
                        break;
                    }
                    inner.push(b);
                }
                if let Some(currency) = inner.strip_prefix('$') {
                    literal_px += text_px(currency.split('-').next().unwrap_or(""));
                } else if inner.chars().all(|c| matches!(c.to_ascii_lowercase(), 'h' | 'm' | 's')) {
                    tokens.push_str("hhh"); // elapsed time: allow three digits
                }
            }
            _ => tokens.push(c),
        }
    }

    let numeric = tokens.contains(['0', '#', '?']);
    let lower = tokens.to_ascii_lowercase();
    if !numeric && lower.contains(['y', 'm', 'd', 'h', 's']) {
        return Shape::Constant(literal_px + date_px(&lower));
    }
    if !numeric {
        return Shape::General;
    }
    if lower.contains("e+") || lower.contains("e-") {
        let digits = tokens.chars().filter(|c| matches!(c, '0' | '#' | '?')).count() as f32;
        return Shape::Constant(literal_px + digits * DIGIT_PX + text_px(".E+"));
    }

    let (integer, fraction) = match tokens.split_once('.') {
        Some((i, f)) => (i, f),
        None => (tokens.as_str(), ""),
    };
    let placeholders = |s: &str| s.chars().filter(|c| matches!(c, '0' | '#' | '?')).count();
    let decimals = placeholders(fraction).min(30) as u8;
    let min_integer_digits = integer.chars().filter(|&c| c == '0' || c == '?').count().min(30) as u8;
    let others: f32 =
        tokens.chars().filter(|c| !matches!(c, '0' | '#' | '?' | ',' | '.' | '%')).filter(|c| !c.is_whitespace()).map(char_px).sum();
    let spaces: f32 = tokens.chars().filter(|c| c.is_whitespace()).map(char_px).sum();
    Shape::Fixed {
        decimals,
        min_integer_digits,
        thousands: integer.contains(','),
        percent: tokens.matches('%').count().min(2) as u8,
        literal_px: literal_px + others + spaces,
    }
}

/// Width of a date/time format, with each token at its widest rendering.
fn date_px(tokens: &str) -> f32 {
    let mut px = 0.0;
    let mut chars = tokens.chars().peekable();
    while let Some(c) = chars.next() {
        if tokens.contains("am/pm") && c == 'a' {
            // "am/pm" renders as "PM"
            for _ in 0..4 {
                chars.next();
            }
            px += text_px("PM");
            continue;
        }
        let mut run = 1;
        while chars.peek() == Some(&c) {
            chars.next();
            run += 1;
        }
        px += match (c, run) {
            ('y', 1..=2) => 2.0 * DIGIT_PX,
            ('y', _) => 4.0 * DIGIT_PX,
            ('m', 3) => text_px("Sep"),
            ('m', 4..) => text_px("September"),
            ('d', 3) => text_px("Wed"),
            ('d', 4..) => text_px("Wednesday"),
            ('m' | 'd' | 's', _) => 2.0 * DIGIT_PX,
            ('h', _) => run.max(2) as f32 * DIGIT_PX,
            ('0', _) => run as f32 * DIGIT_PX,
            (other, _) => run as f32 * char_px(other),
        };
    }
    px
}

/// Per-sheet auto-fit state while the sample is being written.
pub(crate) struct FitState {
    settings: AutoFit,
    /// Explicit column definitions from the sheet options.
    columns: BTreeMap<u32, Column>,
    /// Widest content per column (index = column - 1), in pixels.
    widths: Vec<f32>,
    styles: Vec<Option<StyleFit>>,
    pub rows: u32,
    /// Where `<cols>` belongs in the held-back XML.
    pub cols_at: usize,
}

impl FitState {
    pub fn new(settings: AutoFit, columns: BTreeMap<u32, Column>, cols_at: usize) -> Self {
        FitState { settings, columns, widths: Vec::new(), styles: Vec::new(), rows: 0, cols_at }
    }

    #[inline]
    pub fn sample_complete(&self, xml_len: usize) -> bool {
        self.rows >= self.settings.sample_rows || xml_len >= MAX_SAMPLE_BYTES
    }

    fn style(&mut self, styles: &StyleRegistry, style: u32) -> StyleFit {
        let i = style as usize;
        if i >= self.styles.len() {
            self.styles.resize(i + 1, None);
        }
        *self.styles[i].get_or_insert_with(|| StyleFit::of(styles, style))
    }

    fn observe(&mut self, column: u32, px: f32) {
        let i = column as usize - 1;
        if i >= self.widths.len() {
            self.widths.resize(i + 1, 0.0);
        }
        if px > self.widths[i] {
            self.widths[i] = px;
        }
    }

    pub fn text(&mut self, styles: &StyleRegistry, column: u32, style: u32, text: &str) {
        let scale = self.style(styles, style).scale;
        self.observe(column, text_px(text) * scale);
    }

    pub fn number(&mut self, styles: &StyleRegistry, column: u32, style: u32, value: f64) {
        let fit = self.style(styles, style);
        if !value.is_finite() {
            self.observe(column, text_px("#NUM!") * fit.scale);
            return;
        }
        let px = match fit.shape {
            Shape::General => general_px(value),
            Shape::Constant(px) => px,
            Shape::Fixed { decimals, min_integer_digits, thousands, percent, literal_px } => {
                let scaled = value.abs() * 100f64.powi(percent as i32);
                let rounded = (scaled * 10f64.powi(decimals as i32)).round() / 10f64.powi(decimals as i32);
                let mut digits = if rounded.trunc() >= 1.0 { rounded.trunc().log10().floor() as u32 + 1 } else { 0 };
                digits = digits.max(min_integer_digits as u32);
                let mut px = digits as f32 * DIGIT_PX + literal_px + percent as f32 * char_px('%');
                if thousands && digits > 3 {
                    px += ((digits - 1) / 3) as f32 * char_px(',');
                }
                if decimals > 0 {
                    px += char_px('.') + decimals as f32 * DIGIT_PX;
                }
                if value < 0.0 {
                    px += char_px('-');
                }
                px
            }
        };
        self.observe(column, px * fit.scale);
    }

    /// Writes `<cols>` for the explicit columns and the fitted ones.
    pub fn write_cols(&self, x: &mut Vec<u8>) {
        let fitted = self.widths.iter().enumerate().filter(|&(_, &px)| px > 0.0).map(|(i, _)| i as u32 + 1);
        let mut indexes: Vec<u32> = self.columns.keys().copied().chain(fitted).collect();
        indexes.sort_unstable();
        indexes.dedup();
        if indexes.is_empty() {
            return;
        }
        x.extend_from_slice(b"<cols>");
        for index in indexes {
            let mut column = self.columns.get(&index).copied().unwrap_or_default();
            if column.width.is_none() {
                let px = self.widths.get(index as usize - 1).copied().unwrap_or(0.0);
                if px > 0.0 {
                    column.width = Some(self.width_for(px));
                }
            }
            write_col(x, index, &column);
        }
        x.extend_from_slice(b"</cols>");
    }

    /// Column width in characters for content `px` wide, with Excel's cell padding.
    fn width_for(&self, px: f32) -> f64 {
        let chars = (px as f64 + 2.0) / 7.0;
        ((chars * 100.0).ceil() / 100.0).min(self.settings.max_width).clamp(0.0, 255.0)
    }
}

fn general_px(value: f64) -> f32 {
    if !value.is_finite() {
        return text_px("#NUM!");
    }
    let mut buf = Vec::with_capacity(24);
    let magnitude = value.abs();
    if magnitude != 0.0 && !(1e-9..1e11).contains(&magnitude) {
        return text_px(if value < 0.0 { "-1.23457E+11" } else { "1.23457E+11" });
    }
    xml::push_f64(&mut buf, value);
    // General shows at most 11 characters, rounding the fraction away.
    buf.iter().take(11).map(|&b| char_px(b as char)).sum()
}

pub(crate) fn write_col(x: &mut Vec<u8>, index: u32, column: &Column) {
    x.extend_from_slice(b"<col min=\"");
    xml::push_int(x, index);
    x.extend_from_slice(b"\" max=\"");
    xml::push_int(x, index);
    x.extend_from_slice(b"\" width=\"");
    xml::push_f64(x, column.width.map_or(9.140625, |w| w.clamp(0.0, 255.0)));
    x.push(b'"');
    if column.width.is_some() {
        x.extend_from_slice(b" customWidth=\"1\"");
    }
    if column.style.0 != 0 {
        x.extend_from_slice(b" style=\"");
        xml::push_int(x, column.style.0);
        x.push(b'"');
    }
    if column.hidden {
        x.extend_from_slice(b" hidden=\"1\"");
    }
    x.extend_from_slice(b"/>");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_widths() {
        assert_eq!(text_px(""), 0.0);
        assert_eq!(text_px("0123456789"), 70.0);
        assert_eq!(text_px("ab\nlonger line"), text_px("longer line"));
        assert!(text_px("WWW") > text_px("iii"));
        assert_eq!(text_px("日本"), 30.0);
    }

    #[test]
    fn number_shapes() {
        let px = |code: &str, value: f64| {
            let shape = custom_shape(code);
            let mut state = FitState::new(AutoFit::default(), BTreeMap::new(), 0);
            state.styles = vec![Some(StyleFit { scale: 1.0, shape })];
            state.number(&StyleRegistry::new(), 1, 0, value);
            state.widths[0]
        };
        assert_eq!(px("#,##0.00", 1234567.891), text_px("1,234,567.89"));
        assert_eq!(px("0.0%", 0.256), text_px("25.6%"));
        assert_eq!(px("0", -42.0), text_px("-42"));
        assert_eq!(px("000", 7.0), text_px("007"));
        assert_eq!(px("\"$\"#,##0", 1500.0), text_px("$1,500"));
        assert_eq!(px("[$€-407] #,##0.00", 10.0), text_px("€ 10.00"));
        assert_eq!(px("yyyy-mm-dd", 45000.0), text_px("2026-12-31"));
        assert_eq!(px("dd mmmm yyyy", 45000.0), text_px("31 September 2026"));
        assert_eq!(px("h:mm AM/PM", 0.5), text_px("12:00 PM"));
        assert_eq!(px("General", 3.25), text_px("3.25"));
        assert_eq!(px("@", 1.0 / 3.0), text_px("0.333333333"));
    }

    #[test]
    fn general_numbers() {
        assert_eq!(general_px(42.0), text_px("42"));
        assert_eq!(general_px(1e15), text_px("1.23457E+11"));
        assert_eq!(general_px(f64::NAN), text_px("#NUM!"));
    }
}
