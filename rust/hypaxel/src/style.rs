//! Cell formats: fonts, fills, borders, alignment and number formats.

use std::borrow::Cow;
use std::collections::HashMap;

use crate::xml;

/// Handle to a format registered with [`XlsxWriter::add_style`](crate::XlsxWriter::add_style).
/// Only valid for the writer that created it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct StyleId(pub(crate) u32);

impl StyleId {
    /// The workbook's default ("Normal") format.
    pub const DEFAULT: StyleId = StyleId(0);

    /// Index into the workbook's `cellXfs` table.
    pub fn index(self) -> u32 {
        self.0
    }
}

/// An ARGB color.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Color(pub u32);

impl Color {
    pub const BLACK: Color = Color::rgb(0, 0, 0);
    pub const WHITE: Color = Color::rgb(255, 255, 255);
    pub const RED: Color = Color::rgb(255, 0, 0);
    pub const GREEN: Color = Color::rgb(0, 128, 0);
    pub const BLUE: Color = Color::rgb(0, 0, 255);
    pub const YELLOW: Color = Color::rgb(255, 255, 0);
    pub const LIGHT_GRAY: Color = Color::rgb(217, 217, 217);
    pub const DARK_GRAY: Color = Color::rgb(89, 89, 89);

    /// An opaque color.
    pub const fn rgb(r: u8, g: u8, b: u8) -> Color {
        Color(0xFF00_0000 | (r as u32) << 16 | (g as u32) << 8 | b as u32)
    }

    /// Parses `#RRGGBB`, `RRGGBB`, `#AARRGGBB` or `AARRGGBB`.
    pub fn hex(s: &str) -> Option<Color> {
        let s = s.strip_prefix('#').unwrap_or(s);
        let v = u32::from_str_radix(s, 16).ok()?;
        match s.len() {
            6 => Some(Color(0xFF00_0000 | v)),
            8 => Some(Color(v)),
            _ => None,
        }
    }

    fn write_argb(self, out: &mut Vec<u8>) {
        const HEX: &[u8; 16] = b"0123456789ABCDEF";
        for shift in (0..8).rev() {
            out.push(HEX[((self.0 >> (shift * 4)) & 0xF) as usize]);
        }
    }
}

/// Font settings. Defaults to Calibri 11.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Font {
    pub name: Cow<'static, str>,
    /// Size in hundredths of a point (1100 = 11pt). Use [`Font::size`] to set it in points.
    pub size_centipoints: u32,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikethrough: bool,
    pub color: Option<Color>,
}

impl Default for Font {
    fn default() -> Self {
        Font {
            name: Cow::Borrowed("Calibri"),
            size_centipoints: 1100,
            bold: false,
            italic: false,
            underline: false,
            strikethrough: false,
            color: None,
        }
    }
}

impl Font {
    /// Sets the size in points.
    pub fn size(mut self, points: f64) -> Self {
        self.size_centipoints = (points * 100.0).round().clamp(100.0, 40_900.0) as u32;
        self
    }
}

/// Border line styles.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum BorderStyle {
    #[default]
    None,
    Thin,
    Medium,
    Dashed,
    Dotted,
    Thick,
    Double,
    Hair,
    MediumDashed,
    DashDot,
    MediumDashDot,
    DashDotDot,
    MediumDashDotDot,
    SlantDashDot,
}

impl BorderStyle {
    fn name(self) -> &'static [u8] {
        match self {
            BorderStyle::None => b"none",
            BorderStyle::Thin => b"thin",
            BorderStyle::Medium => b"medium",
            BorderStyle::Dashed => b"dashed",
            BorderStyle::Dotted => b"dotted",
            BorderStyle::Thick => b"thick",
            BorderStyle::Double => b"double",
            BorderStyle::Hair => b"hair",
            BorderStyle::MediumDashed => b"mediumDashed",
            BorderStyle::DashDot => b"dashDot",
            BorderStyle::MediumDashDot => b"mediumDashDot",
            BorderStyle::DashDotDot => b"dashDotDot",
            BorderStyle::MediumDashDotDot => b"mediumDashDotDot",
            BorderStyle::SlantDashDot => b"slantDashDot",
        }
    }
}

/// One edge of a border.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct BorderLine {
    pub style: BorderStyle,
    pub color: Option<Color>,
}

/// Cell borders.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Border {
    pub left: BorderLine,
    pub right: BorderLine,
    pub top: BorderLine,
    pub bottom: BorderLine,
}

impl Border {
    /// The same line on all four edges.
    pub fn all(style: BorderStyle, color: Option<Color>) -> Border {
        let line = BorderLine { style, color };
        Border { left: line, right: line, top: line, bottom: line }
    }
}

/// Horizontal alignment.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum HAlign {
    #[default]
    General,
    Left,
    Center,
    Right,
    Fill,
    Justify,
    CenterContinuous,
    Distributed,
}

/// Vertical alignment.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum VAlign {
    #[default]
    Bottom,
    Top,
    Center,
    Justify,
    Distributed,
}

/// Text alignment.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Alignment {
    pub horizontal: HAlign,
    pub vertical: VAlign,
    pub wrap_text: bool,
    pub shrink_to_fit: bool,
    pub indent: u8,
    /// 0–180 degrees, or 255 for vertical text.
    pub rotation: u8,
}

/// A complete cell format, built fluently:
///
/// ```
/// use hypaxel::{Style, Color, HAlign};
/// let header = Style::new().bold().font_color(Color::WHITE).fill(Color::hex("#305496").unwrap()).align(HAlign::Center);
/// let money = Style::new().num_format("#,##0.00");
/// ```
#[derive(Debug, Clone, PartialEq, Eq, Hash, Default)]
pub struct Style {
    pub num_format: Option<Cow<'static, str>>,
    pub font: Option<Font>,
    pub fill: Option<Color>,
    pub border: Option<Border>,
    pub alignment: Option<Alignment>,
}

impl Style {
    pub fn new() -> Self {
        Self::default()
    }

    /// Excel number format code, e.g. `"0.00"`, `"#,##0"`, `"yyyy-mm-dd"`, `"@"`.
    pub fn num_format(mut self, code: impl Into<Cow<'static, str>>) -> Self {
        self.num_format = Some(code.into());
        self
    }

    pub fn font(mut self, font: Font) -> Self {
        self.font = Some(font);
        self
    }

    fn font_mut(&mut self) -> &mut Font {
        self.font.get_or_insert_with(Font::default)
    }

    pub fn bold(mut self) -> Self {
        self.font_mut().bold = true;
        self
    }

    pub fn italic(mut self) -> Self {
        self.font_mut().italic = true;
        self
    }

    pub fn underline(mut self) -> Self {
        self.font_mut().underline = true;
        self
    }

    pub fn font_color(mut self, color: Color) -> Self {
        self.font_mut().color = Some(color);
        self
    }

    pub fn font_size(mut self, points: f64) -> Self {
        let font = self.font.take().unwrap_or_default().size(points);
        self.font = Some(font);
        self
    }

    pub fn font_name(mut self, name: impl Into<Cow<'static, str>>) -> Self {
        self.font_mut().name = name.into();
        self
    }

    /// Solid background fill.
    pub fn fill(mut self, color: Color) -> Self {
        self.fill = Some(color);
        self
    }

    pub fn border(mut self, border: Border) -> Self {
        self.border = Some(border);
        self
    }

    pub fn alignment(mut self, alignment: Alignment) -> Self {
        self.alignment = Some(alignment);
        self
    }

    pub fn align(mut self, horizontal: HAlign) -> Self {
        self.alignment.get_or_insert_with(Alignment::default).horizontal = horizontal;
        self
    }

    pub fn valign(mut self, vertical: VAlign) -> Self {
        self.alignment.get_or_insert_with(Alignment::default).vertical = vertical;
        self
    }

    pub fn wrap(mut self) -> Self {
        self.alignment.get_or_insert_with(Alignment::default).wrap_text = true;
        self
    }
}

/// Commonly used number format codes.
pub mod formats {
    pub const GENERAL: &str = "General";
    pub const INTEGER: &str = "0";
    pub const DECIMAL2: &str = "0.00";
    pub const THOUSANDS: &str = "#,##0";
    pub const THOUSANDS2: &str = "#,##0.00";
    pub const PERCENT: &str = "0%";
    pub const PERCENT2: &str = "0.00%";
    pub const TEXT: &str = "@";
    pub const DATE: &str = "yyyy-mm-dd";
    pub const DATETIME: &str = "yyyy-mm-dd hh:mm:ss";
    pub const TIME: &str = "hh:mm:ss";
    pub const DURATION: &str = "[h]:mm:ss";
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(not(any(feature = "chrono", feature = "serde")), allow(dead_code))]
pub(crate) enum ValueKind {
    Date = 0,
    DateTime = 1,
    Time = 2,
    Duration = 3,
}

#[cfg_attr(not(any(feature = "chrono", feature = "serde")), allow(dead_code))]
impl ValueKind {
    fn builtin_format(self) -> u32 {
        match self {
            ValueKind::Date => 14,     // short date, viewer's locale
            ValueKind::DateTime => 22, // short date + time, viewer's locale
            ValueKind::Time => 21,     // h:mm:ss
            ValueKind::Duration => 46, // [h]:mm:ss
        }
    }
}

fn builtin_num_fmt(code: &str) -> Option<u32> {
    Some(match code {
        "General" => 0,
        "0" => 1,
        "0.00" => 2,
        "#,##0" => 3,
        "#,##0.00" => 4,
        "0%" => 9,
        "0.00%" => 10,
        "0.00E+00" => 11,
        "@" => 49,
        _ => return None,
    })
}

#[derive(Clone, PartialEq, Eq, Hash)]
struct Xf {
    num_fmt: u32,
    font: u32,
    fill: u32,
    border: u32,
    alignment: Option<Alignment>,
}

pub(crate) struct StyleRegistry {
    fonts: Vec<Font>,
    font_ids: HashMap<Font, u32>,
    fills: Vec<Color>, // excluding the two mandatory fills
    fill_ids: HashMap<Color, u32>,
    borders: Vec<Border>, // excluding the default border
    border_ids: HashMap<Border, u32>,
    num_fmts: Vec<Box<str>>,
    num_fmt_ids: HashMap<Box<str>, u32>,
    xfs: Vec<Xf>,
    xf_ids: HashMap<Xf, u32>,
    style_ids: HashMap<Style, StyleId>,
    variants: HashMap<(u32, u8), u32>,
    default_variants: [u32; 4],
}

impl StyleRegistry {
    pub fn new() -> Self {
        let default_xf = Xf { num_fmt: 0, font: 0, fill: 0, border: 0, alignment: None };
        let mut registry = StyleRegistry {
            fonts: vec![Font::default()],
            font_ids: HashMap::new(),
            fills: Vec::new(),
            fill_ids: HashMap::new(),
            borders: Vec::new(),
            border_ids: HashMap::new(),
            num_fmts: Vec::new(),
            num_fmt_ids: HashMap::new(),
            xfs: vec![default_xf.clone()],
            xf_ids: HashMap::new(),
            style_ids: HashMap::new(),
            variants: HashMap::new(),
            default_variants: [0; 4],
        };
        registry.font_ids.insert(Font::default(), 0);
        registry.xf_ids.insert(default_xf, 0);
        registry
    }

    #[inline]
    pub fn len(&self) -> u32 {
        self.xfs.len() as u32
    }

    pub fn add(&mut self, style: &Style) -> StyleId {
        if let Some(&id) = self.style_ids.get(style) {
            return id;
        }

        let num_fmt = style.num_format.as_deref().map_or(0, |code| self.num_fmt_id(code));
        let font = style.font.as_ref().map_or(0, |font| {
            *self.font_ids.entry(font.clone()).or_insert_with(|| {
                self.fonts.push(font.clone());
                (self.fonts.len() - 1) as u32
            })
        });
        let fill = style.fill.map_or(0, |color| {
            *self.fill_ids.entry(color).or_insert_with(|| {
                self.fills.push(color);
                (self.fills.len() + 1) as u32
            })
        });
        let border = style.border.map_or(0, |border| {
            *self.border_ids.entry(border).or_insert_with(|| {
                self.borders.push(border);
                self.borders.len() as u32
            })
        });
        let id = StyleId(self.xf_id(Xf { num_fmt, font, fill, border, alignment: style.alignment }));
        self.style_ids.insert(style.clone(), id);
        id
    }

    #[cfg_attr(not(any(feature = "chrono", feature = "serde")), allow(dead_code))]
    /// A format able to display a date/time value: the style itself when it already has a number
    /// format, otherwise a variant of it with the matching built-in format.
    #[inline]
    pub fn value_variant(&mut self, style: u32, kind: ValueKind) -> u32 {
        if style == 0 {
            let cached = self.default_variants[kind as usize];
            if cached != 0 {
                return cached;
            }
        }
        self.value_variant_slow(style, kind)
    }

    #[cold]
    fn value_variant_slow(&mut self, style: u32, kind: ValueKind) -> u32 {
        let Some(xf) = self.xfs.get(style as usize) else { return style };
        if xf.num_fmt != 0 {
            return style;
        }
        if let Some(&id) = self.variants.get(&(style, kind as u8)) {
            return id;
        }
        let variant = Xf { num_fmt: kind.builtin_format(), ..xf.clone() };
        let id = self.xf_id(variant);
        self.variants.insert((style, kind as u8), id);
        if style == 0 {
            self.default_variants[kind as usize] = id;
        }
        id
    }

    fn xf_id(&mut self, xf: Xf) -> u32 {
        if let Some(&id) = self.xf_ids.get(&xf) {
            return id;
        }
        let id = self.xfs.len() as u32;
        self.xfs.push(xf.clone());
        self.xf_ids.insert(xf, id);
        id
    }

    fn num_fmt_id(&mut self, code: &str) -> u32 {
        if let Some(id) = builtin_num_fmt(code) {
            return id;
        }
        if let Some(&id) = self.num_fmt_ids.get(code) {
            return id;
        }
        let id = 164 + self.num_fmts.len() as u32;
        self.num_fmts.push(code.into());
        self.num_fmt_ids.insert(code.into(), id);
        id
    }

    pub fn write_xml(&self, x: &mut Vec<u8>) {
        x.extend_from_slice(xml::DECLARATION);
        x.extend_from_slice(b"<styleSheet xmlns=\"");
        x.extend_from_slice(xml::NS_MAIN);
        x.extend_from_slice(b"\">");

        if !self.num_fmts.is_empty() {
            x.extend_from_slice(b"<numFmts count=\"");
            xml::push_int(x, self.num_fmts.len());
            x.extend_from_slice(b"\">");
            for (i, code) in self.num_fmts.iter().enumerate() {
                x.extend_from_slice(b"<numFmt numFmtId=\"");
                xml::push_int(x, 164 + i);
                x.extend_from_slice(b"\" formatCode=\"");
                xml::escape_into(x, code);
                x.extend_from_slice(b"\"/>");
            }
            x.extend_from_slice(b"</numFmts>");
        }

        x.extend_from_slice(b"<fonts count=\"");
        xml::push_int(x, self.fonts.len());
        x.extend_from_slice(b"\">");
        for font in &self.fonts {
            x.extend_from_slice(b"<font>");
            if font.bold {
                x.extend_from_slice(b"<b/>");
            }
            if font.italic {
                x.extend_from_slice(b"<i/>");
            }
            if font.strikethrough {
                x.extend_from_slice(b"<strike/>");
            }
            if font.underline {
                x.extend_from_slice(b"<u/>");
            }
            x.extend_from_slice(b"<sz val=\"");
            xml::push_f64(x, font.size_centipoints as f64 / 100.0);
            x.extend_from_slice(b"\"/>");
            if let Some(color) = font.color {
                write_color(x, b"color", color);
            }
            // No theme part is written, so fonts never reference theme colors or schemes.
            x.extend_from_slice(b"<name val=\"");
            xml::escape_into(x, &font.name);
            x.extend_from_slice(b"\"/><family val=\"2\"/></font>");
        }

        x.extend_from_slice(b"</fonts><fills count=\"");
        xml::push_int(x, self.fills.len() + 2);
        x.extend_from_slice(b"\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>");
        for &color in &self.fills {
            x.extend_from_slice(b"<fill><patternFill patternType=\"solid\">");
            write_color(x, b"fgColor", color);
            x.extend_from_slice(b"<bgColor indexed=\"64\"/></patternFill></fill>");
        }

        x.extend_from_slice(b"</fills><borders count=\"");
        xml::push_int(x, self.borders.len() + 1);
        x.extend_from_slice(b"\"><border><left/><right/><top/><bottom/><diagonal/></border>");
        for border in &self.borders {
            x.extend_from_slice(b"<border>");
            write_border_line(x, b"left", border.left);
            write_border_line(x, b"right", border.right);
            write_border_line(x, b"top", border.top);
            write_border_line(x, b"bottom", border.bottom);
            x.extend_from_slice(b"<diagonal/></border>");
        }

        x.extend_from_slice(b"</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"");
        xml::push_int(x, self.xfs.len());
        x.extend_from_slice(b"\">");
        for xf in &self.xfs {
            x.extend_from_slice(b"<xf numFmtId=\"");
            xml::push_int(x, xf.num_fmt);
            x.extend_from_slice(b"\" fontId=\"");
            xml::push_int(x, xf.font);
            x.extend_from_slice(b"\" fillId=\"");
            xml::push_int(x, xf.fill);
            x.extend_from_slice(b"\" borderId=\"");
            xml::push_int(x, xf.border);
            x.extend_from_slice(b"\" xfId=\"0\"");
            if xf.num_fmt != 0 {
                x.extend_from_slice(b" applyNumberFormat=\"1\"");
            }
            if xf.font != 0 {
                x.extend_from_slice(b" applyFont=\"1\"");
            }
            if xf.fill != 0 {
                x.extend_from_slice(b" applyFill=\"1\"");
            }
            if xf.border != 0 {
                x.extend_from_slice(b" applyBorder=\"1\"");
            }
            match xf.alignment {
                Some(a) => {
                    x.extend_from_slice(b" applyAlignment=\"1\"><alignment");
                    if a.horizontal != HAlign::General {
                        x.extend_from_slice(b" horizontal=\"");
                        x.extend_from_slice(halign_name(a.horizontal));
                        x.push(b'"');
                    }
                    if a.vertical != VAlign::Bottom {
                        x.extend_from_slice(b" vertical=\"");
                        x.extend_from_slice(valign_name(a.vertical));
                        x.push(b'"');
                    }
                    if a.rotation != 0 {
                        x.extend_from_slice(b" textRotation=\"");
                        xml::push_int(x, a.rotation);
                        x.push(b'"');
                    }
                    if a.wrap_text {
                        x.extend_from_slice(b" wrapText=\"1\"");
                    }
                    if a.indent != 0 {
                        x.extend_from_slice(b" indent=\"");
                        xml::push_int(x, a.indent);
                        x.push(b'"');
                    }
                    if a.shrink_to_fit {
                        x.extend_from_slice(b" shrinkToFit=\"1\"");
                    }
                    x.extend_from_slice(b"/></xf>");
                }
                None => x.extend_from_slice(b"/>"),
            }
        }

        x.extend_from_slice(b"</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles><dxfs count=\"0\"/><tableStyles count=\"0\"/></styleSheet>");
    }
}

fn write_color(x: &mut Vec<u8>, element: &[u8], color: Color) {
    x.push(b'<');
    x.extend_from_slice(element);
    x.extend_from_slice(b" rgb=\"");
    color.write_argb(x);
    x.extend_from_slice(b"\"/>");
}

fn write_border_line(x: &mut Vec<u8>, element: &[u8], line: BorderLine) {
    x.push(b'<');
    x.extend_from_slice(element);
    if line.style == BorderStyle::None {
        x.extend_from_slice(b"/>");
        return;
    }
    x.extend_from_slice(b" style=\"");
    x.extend_from_slice(line.style.name());
    x.extend_from_slice(b"\">");
    match line.color {
        Some(color) => write_color(x, b"color", color),
        None => x.extend_from_slice(b"<color indexed=\"64\"/>"),
    }
    x.extend_from_slice(b"</");
    x.extend_from_slice(element);
    x.push(b'>');
}

fn halign_name(h: HAlign) -> &'static [u8] {
    match h {
        HAlign::General => b"general",
        HAlign::Left => b"left",
        HAlign::Center => b"center",
        HAlign::Right => b"right",
        HAlign::Fill => b"fill",
        HAlign::Justify => b"justify",
        HAlign::CenterContinuous => b"centerContinuous",
        HAlign::Distributed => b"distributed",
    }
}

fn valign_name(v: VAlign) -> &'static [u8] {
    match v {
        VAlign::Bottom => b"bottom",
        VAlign::Top => b"top",
        VAlign::Center => b"center",
        VAlign::Justify => b"justify",
        VAlign::Distributed => b"distributed",
    }
}
