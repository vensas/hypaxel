//! Node.js bindings for the hypaxel XLSX writer.
//!
//! The public API lives in `node/ts/`. This module is the native core: a writer that keeps its
//! output in memory and takes cells as a compact binary batch, so a whole batch of rows crosses
//! the JavaScript boundary in one call.

use chrono::DateTime;
use hypaxel::{
    Alignment, Blank, Border, BorderLine, BorderStyle, Color, Formula, HAlign, Options, SheetOptions, StringStorage, Style, StyleId,
    VAlign, XlsxWriter,
};
use napi::bindgen_prelude::*;
use napi_derive::napi;

const TAG_END_ROW: u8 = 0;
const TAG_BLANK: u8 = 1;
const TAG_NUMBER: u8 = 2;
const TAG_STRING: u8 = 3;
const TAG_TRUE: u8 = 4;
const TAG_FALSE: u8 = 5;
const TAG_DATE: u8 = 6;
const TAG_FORMULA: u8 = 7;
const TAG_STYLE: u8 = 8;
const TAG_ROW_STYLE: u8 = 9;
const TAG_SKIP: u8 = 10;

fn err(message: impl Into<String>) -> Error {
    Error::new(Status::GenericFailure, message.into())
}

fn invalid(message: impl Into<String>) -> Error {
    Error::new(Status::InvalidArg, message.into())
}

fn core(e: hypaxel::Error) -> Error {
    err(e.to_string())
}

#[napi(object)]
#[derive(Default)]
pub struct NativeWriterOptions {
    pub compression_level: Option<u32>,
    pub shared_strings: Option<bool>,
    pub flush_threshold: Option<u32>,
    pub date1904: Option<bool>,
    pub cell_references: Option<bool>,
}

#[napi(object)]
#[derive(Default)]
pub struct NativeStyleOptions {
    pub bold: Option<bool>,
    pub italic: Option<bool>,
    pub underline: Option<bool>,
    pub strikethrough: Option<bool>,
    pub font_name: Option<String>,
    pub font_size: Option<f64>,
    pub font_color: Option<String>,
    pub fill: Option<String>,
    pub border: Option<String>,
    pub border_color: Option<String>,
    pub border_bottom: Option<String>,
    pub align: Option<String>,
    pub valign: Option<String>,
    pub wrap: Option<bool>,
    pub indent: Option<u8>,
    pub rotation: Option<u8>,
    pub num_format: Option<String>,
}

#[napi(object)]
#[derive(Default)]
pub struct NativeSheetOptions {
    pub freeze_rows: Option<u32>,
    pub freeze_columns: Option<u32>,
    pub column_widths: Option<Vec<f64>>,
    pub hidden: Option<bool>,
    pub show_grid_lines: Option<bool>,
    pub tab_color: Option<String>,
}

fn parse_color(s: &str) -> Result<Color> {
    Color::hex(s).ok_or_else(|| invalid(format!("invalid color {s:?}; expected '#RRGGBB' or '#AARRGGBB'")))
}

fn parse_border(s: &str) -> Result<BorderStyle> {
    Ok(match s {
        "none" => BorderStyle::None,
        "thin" => BorderStyle::Thin,
        "medium" => BorderStyle::Medium,
        "dashed" => BorderStyle::Dashed,
        "dotted" => BorderStyle::Dotted,
        "thick" => BorderStyle::Thick,
        "double" => BorderStyle::Double,
        "hair" => BorderStyle::Hair,
        "mediumDashed" => BorderStyle::MediumDashed,
        "dashDot" => BorderStyle::DashDot,
        "mediumDashDot" => BorderStyle::MediumDashDot,
        "dashDotDot" => BorderStyle::DashDotDot,
        "mediumDashDotDot" => BorderStyle::MediumDashDotDot,
        "slantDashDot" => BorderStyle::SlantDashDot,
        other => return Err(invalid(format!("unknown border style {other:?}"))),
    })
}

fn parse_halign(s: &str) -> Result<HAlign> {
    Ok(match s {
        "general" => HAlign::General,
        "left" => HAlign::Left,
        "center" => HAlign::Center,
        "right" => HAlign::Right,
        "fill" => HAlign::Fill,
        "justify" => HAlign::Justify,
        "centerContinuous" => HAlign::CenterContinuous,
        "distributed" => HAlign::Distributed,
        other => return Err(invalid(format!("unknown align {other:?}"))),
    })
}

fn parse_valign(s: &str) -> Result<VAlign> {
    Ok(match s {
        "bottom" => VAlign::Bottom,
        "top" => VAlign::Top,
        "center" => VAlign::Center,
        "justify" => VAlign::Justify,
        "distributed" => VAlign::Distributed,
        other => return Err(invalid(format!("unknown valign {other:?}"))),
    })
}

fn build_style(o: NativeStyleOptions) -> Result<Style> {
    let mut style = Style::new();
    let (bold, italic) = (o.bold.unwrap_or(false), o.italic.unwrap_or(false));
    let (underline, strikethrough) = (o.underline.unwrap_or(false), o.strikethrough.unwrap_or(false));
    if bold || italic || underline || strikethrough || o.font_name.is_some() || o.font_size.is_some() || o.font_color.is_some() {
        let mut font = hypaxel::Font { bold, italic, underline, strikethrough, ..Default::default() };
        if let Some(name) = o.font_name {
            font.name = name.into();
        }
        if let Some(size) = o.font_size {
            font = font.size(size);
        }
        if let Some(c) = &o.font_color {
            font.color = Some(parse_color(c)?);
        }
        style = style.font(font);
    }
    if let Some(c) = &o.fill {
        style = style.fill(parse_color(c)?);
    }
    let color = o.border_color.as_deref().map(parse_color).transpose()?;
    if o.border.is_some() || o.border_bottom.is_some() {
        let mut border = match &o.border {
            Some(s) => Border::all(parse_border(s)?, color),
            None => Border::default(),
        };
        if let Some(s) = &o.border_bottom {
            border.bottom = BorderLine { style: parse_border(s)?, color };
        }
        style = style.border(border);
    }
    let (wrap, indent, rotation) = (o.wrap.unwrap_or(false), o.indent.unwrap_or(0), o.rotation.unwrap_or(0));
    if o.align.is_some() || o.valign.is_some() || wrap || indent != 0 || rotation != 0 {
        style = style.alignment(Alignment {
            horizontal: parse_halign(o.align.as_deref().unwrap_or("general"))?,
            vertical: parse_valign(o.valign.as_deref().unwrap_or("bottom"))?,
            wrap_text: wrap,
            shrink_to_fit: false,
            indent,
            rotation,
        });
    }
    if let Some(code) = o.num_format {
        style = style.num_format(code);
    }
    Ok(style)
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, len: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(len).filter(|&end| end <= self.data.len()).ok_or_else(|| invalid("truncated cell batch"))?;
        let bytes = &self.data[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    fn u32(&mut self) -> Result<u32> {
        let bytes = self.take(4)?;
        Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn f64(&mut self) -> Result<f64> {
        let bytes = self.take(8)?;
        Ok(f64::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]]))
    }

    fn str(&mut self) -> Result<&'a str> {
        let len = self.u32()? as usize;
        std::str::from_utf8(self.take(len)?).map_err(|_| invalid("cell batch contains invalid UTF-8"))
    }
}

/// In-memory XLSX writer. Output accumulates in a buffer that JavaScript takes with `drain`.
#[napi]
pub struct NativeWriter {
    inner: Option<XlsxWriter<Vec<u8>>>,
    styles: Vec<StyleId>,
}

impl NativeWriter {
    fn inner(&mut self) -> Result<&mut XlsxWriter<Vec<u8>>> {
        self.inner.as_mut().ok_or_else(|| err("the workbook is already finished"))
    }

    fn style(&self, index: u32) -> Result<StyleId> {
        self.styles.get(index as usize).copied().ok_or_else(|| invalid("the style does not belong to this writer"))
    }
}

#[napi]
impl NativeWriter {
    #[napi(constructor)]
    pub fn new(options: Option<NativeWriterOptions>) -> Self {
        let o = options.unwrap_or_default();
        let flush_threshold = o.flush_threshold.unwrap_or(65536) as usize;
        let options = Options::new()
            .compression_level(o.compression_level.unwrap_or(1))
            .string_storage(if o.shared_strings.unwrap_or(false) { StringStorage::Shared } else { StringStorage::Inline })
            .flush_threshold(flush_threshold)
            .date_1904(o.date1904.unwrap_or(false))
            .cell_references(o.cell_references.unwrap_or(false));
        NativeWriter {
            inner: Some(XlsxWriter::with_options(Vec::with_capacity(flush_threshold * 2), options)),
            styles: vec![StyleId::DEFAULT],
        }
    }

    /// Registers a cell format and returns its handle. Handle 0 is the default style.
    #[napi]
    pub fn add_style(&mut self, options: NativeStyleOptions) -> Result<u32> {
        let style = build_style(options)?;
        let id = self.inner()?.add_style(&style);
        self.styles.push(id);
        Ok((self.styles.len() - 1) as u32)
    }

    #[napi]
    pub fn add_worksheet(&mut self, name: String, options: Option<NativeSheetOptions>) -> Result<()> {
        let o = options.unwrap_or_default();
        let mut sheet = SheetOptions::new().freeze_rows(o.freeze_rows.unwrap_or(0)).freeze_columns(o.freeze_columns.unwrap_or(0));
        if let Some(widths) = &o.column_widths {
            sheet = sheet.column_widths(widths);
        }
        if o.hidden.unwrap_or(false) {
            sheet = sheet.hidden();
        }
        if !o.show_grid_lines.unwrap_or(true) {
            sheet = sheet.hide_grid_lines();
        }
        if let Some(c) = &o.tab_color {
            sheet = sheet.tab_color(parse_color(c)?);
        }
        self.inner()?.add_worksheet_with(&name, &sheet).map_err(core)?;
        Ok(())
    }

    /// Writes a batch of cells encoded by the JavaScript `RowEncoder`. Returns the rows completed.
    #[napi]
    pub fn write_batch(&mut self, data: &[u8]) -> Result<u32> {
        let mut reader = Reader { data, pos: 0 };
        let mut rows = 0u32;
        let mut row_style = StyleId::DEFAULT;
        let mut next_style: Option<StyleId> = None;
        while reader.pos < data.len() {
            let tag = reader.take(1)?[0];
            if tag == TAG_STYLE {
                next_style = Some(self.style(reader.u32()?)?);
                continue;
            }
            if tag == TAG_ROW_STYLE {
                row_style = self.style(reader.u32()?)?;
                continue;
            }
            let style = next_style.take().unwrap_or(row_style);
            let mut sheet = self.inner()?.current_worksheet().ok_or_else(|| err("no open worksheet"))?;
            match tag {
                TAG_END_ROW => {
                    sheet.end_row().map_err(core)?;
                    row_style = StyleId::DEFAULT;
                    rows += 1;
                }
                TAG_BLANK => {
                    sheet.write_styled(Blank, style);
                }
                TAG_NUMBER => {
                    sheet.write_styled(reader.f64()?, style);
                }
                TAG_STRING => {
                    sheet.write_styled(reader.str()?, style);
                }
                TAG_TRUE => {
                    sheet.write_styled(true, style);
                }
                TAG_FALSE => {
                    sheet.write_styled(false, style);
                }
                TAG_DATE => {
                    let millis = reader.f64()?;
                    match millis.is_finite().then(|| DateTime::from_timestamp_millis(millis as i64)).flatten() {
                        Some(instant) => sheet.write_styled(instant.naive_utc(), style),
                        None => sheet.write_styled(Blank, style),
                    };
                }
                TAG_FORMULA => {
                    sheet.write_styled(Formula(reader.str()?), style);
                }
                TAG_SKIP => {
                    sheet.skip_cells(reader.u32()?);
                }
                other => return Err(invalid(format!("unknown cell tag {other}"))),
            }
        }
        Ok(rows)
    }

    /// Starts the next row explicitly, to set its height (points) or a row style.
    #[napi]
    pub fn begin_row(&mut self, height: Option<f64>, style: Option<u32>) -> Result<()> {
        let style = self.style(style.unwrap_or(0))?;
        let mut sheet = self.inner()?.current_worksheet().ok_or_else(|| err("no open worksheet"))?;
        sheet.begin_row(height, style).map_err(core)?;
        Ok(())
    }

    #[napi]
    pub fn skip_rows(&mut self, count: u32) -> Result<()> {
        let mut sheet = self.inner()?.current_worksheet().ok_or_else(|| err("no open worksheet"))?;
        sheet.skip_rows(count);
        Ok(())
    }

    #[napi]
    pub fn merge_cells(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<()> {
        let mut sheet = self.inner()?.current_worksheet().ok_or_else(|| err("no open worksheet"))?;
        sheet.merge_cells(first_row, first_col, last_row, last_col).map_err(core)?;
        Ok(())
    }

    #[napi]
    pub fn autofilter(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<()> {
        let mut sheet = self.inner()?.current_worksheet().ok_or_else(|| err("no open worksheet"))?;
        sheet.autofilter(first_row, first_col, last_row, last_col).map_err(core)?;
        Ok(())
    }

    /// 1-based number of the last written row of the current worksheet (0 before the first row).
    #[napi]
    pub fn row(&mut self) -> Result<u32> {
        let sheet = self.inner()?.current_worksheet().ok_or_else(|| err("no open worksheet"))?;
        Ok(sheet.row())
    }

    /// Number of compressed bytes waiting to be drained.
    #[napi]
    pub fn buffered(&mut self) -> Result<u32> {
        Ok(self.inner()?.get_ref().len() as u32)
    }

    /// Returns and removes the compressed bytes produced so far.
    ///
    /// The bytes are copied into a buffer owned by the JavaScript engine. Handing over the native
    /// allocation instead would hide its size from the garbage collector, which then frees the
    /// chunks too late and lets memory grow with the size of the file.
    #[napi]
    pub fn drain<'env>(&mut self, env: &'env Env) -> Result<BufferSlice<'env>> {
        let buffer = self.inner()?.get_mut();
        let chunk = BufferSlice::copy_from(env, buffer.as_slice())?;
        buffer.clear();
        Ok(chunk)
    }

    /// Completes the workbook and returns the remaining bytes.
    #[napi]
    pub fn finish<'env>(&mut self, env: &'env Env) -> Result<BufferSlice<'env>> {
        let inner = self.inner.take().ok_or_else(|| err("the workbook is already finished"))?;
        BufferSlice::copy_from(env, inner.finish().map_err(core)?.as_slice())
    }

    #[napi(getter)]
    pub fn finished(&self) -> bool {
        self.inner.is_none()
    }
}

/// Returns the column letters for a 1-based column index (1 → "A", 27 → "AA").
#[napi]
pub fn column_name(column: u32) -> Result<String> {
    if !(1..=hypaxel::MAX_COLUMNS).contains(&column) {
        return Err(invalid("column must be within 1..=16384"));
    }
    Ok(hypaxel::column_name(column))
}
