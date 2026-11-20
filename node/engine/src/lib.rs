//! The engine behind the hypaxel JavaScript packages, independent of the binding technology.
//!
//! The Node.js (napi) and WebAssembly (wasm-bindgen) bindings both wrap [`Engine`]: a writer that
//! keeps its output in memory and takes cells as a compact binary batch, so a whole batch of rows
//! crosses the JavaScript boundary in one call. The batch format is produced by `RowEncoder` in
//! `node/ts/core.ts`.

use std::fmt;

use chrono::DateTime;
use hypaxel::{
    Alignment, AutoFit, Blank, Border, BorderLine, BorderStyle, Color, Formula, HAlign, Options, StringStorage, Style, StyleId, Table,
    TableStyle, VAlign, XlsxWriter,
};

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

const DEFAULT_FLUSH_THRESHOLD: u32 = 65536;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// The caller passed an invalid argument.
    Invalid(String),
    /// The operation failed, e.g. because the writer is finished or the workbook is invalid.
    Failed(String),
}

impl Error {
    pub fn message(&self) -> &str {
        match self {
            Error::Invalid(message) | Error::Failed(message) => message,
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

fn failed(message: impl Into<String>) -> Error {
    Error::Failed(message.into())
}

fn invalid(message: impl Into<String>) -> Error {
    Error::Invalid(message.into())
}

fn core(e: hypaxel::Error) -> Error {
    failed(e.to_string())
}

#[derive(Debug, Default, Clone)]
#[cfg_attr(feature = "serde", derive(serde::Deserialize), serde(rename_all = "camelCase", default))]
pub struct WriterConfig {
    pub compression_level: Option<u32>,
    pub shared_strings: Option<bool>,
    pub flush_threshold: Option<u32>,
    pub date1904: Option<bool>,
    pub cell_references: Option<bool>,
}

#[derive(Debug, Default, Clone)]
#[cfg_attr(feature = "serde", derive(serde::Deserialize), serde(rename_all = "camelCase", default))]
pub struct StyleConfig {
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

#[derive(Debug, Default, Clone)]
#[cfg_attr(feature = "serde", derive(serde::Deserialize), serde(rename_all = "camelCase", default))]
pub struct SheetConfig {
    pub freeze_rows: Option<u32>,
    pub freeze_columns: Option<u32>,
    pub column_widths: Option<Vec<f64>>,
    pub hidden: Option<bool>,
    pub show_grid_lines: Option<bool>,
    pub tab_color: Option<String>,
    pub autofit: Option<bool>,
    pub autofit_sample_rows: Option<u32>,
    pub autofit_max_width: Option<f64>,
    pub row_outline_levels: Option<u8>,
    pub outline_summary_above: Option<bool>,
}

#[derive(Debug, Default, Clone)]
#[cfg_attr(feature = "serde", derive(serde::Deserialize), serde(rename_all = "camelCase", default))]
pub struct RowConfig {
    pub height: Option<f64>,
    pub style: Option<u32>,
    pub outline_level: Option<u8>,
    pub hidden: Option<bool>,
    pub collapsed: Option<bool>,
}

#[derive(Debug, Default, Clone)]
#[cfg_attr(feature = "serde", derive(serde::Deserialize), serde(rename_all = "camelCase", default))]
pub struct TableConfig {
    pub name: Option<String>,
    pub columns: Option<Vec<String>>,
    /// `TableStyleMedium2` etc.; `"none"` for no table style.
    pub style: Option<String>,
    pub header_row: Option<bool>,
    pub autofilter: Option<bool>,
    pub row_stripes: Option<bool>,
    pub column_stripes: Option<bool>,
    pub first_column: Option<bool>,
    pub last_column: Option<bool>,
}

fn parse_table_style(name: Option<&str>) -> Result<TableStyle> {
    let Some(name) = name else { return Ok(TableStyle::default()) };
    if name.eq_ignore_ascii_case("none") {
        return Ok(TableStyle::None);
    }
    let invalid = || invalid(format!("unknown table style {name:?}; expected e.g. 'TableStyleMedium2' or 'none'"));
    let rest = name.strip_prefix("TableStyle").ok_or_else(invalid)?;
    let (kind, number): (fn(u8) -> TableStyle, &str) = if let Some(n) = rest.strip_prefix("Light") {
        (TableStyle::Light, n)
    } else if let Some(n) = rest.strip_prefix("Medium") {
        (TableStyle::Medium, n)
    } else if let Some(n) = rest.strip_prefix("Dark") {
        (TableStyle::Dark, n)
    } else {
        return Err(invalid());
    };
    Ok(kind(number.parse().map_err(|_| invalid())?))
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

fn build_style(o: StyleConfig) -> Result<Style> {
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

struct BatchReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> BatchReader<'a> {
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

/// In-memory XLSX writer. Output accumulates in a buffer that the binding takes with [`Engine::output`].
pub struct Engine {
    inner: Option<XlsxWriter<Vec<u8>>>,
    styles: Vec<StyleId>,
}

impl Engine {
    pub fn new(o: WriterConfig) -> Self {
        let flush_threshold = o.flush_threshold.unwrap_or(DEFAULT_FLUSH_THRESHOLD) as usize;
        let options = Options::new()
            .compression_level(o.compression_level.unwrap_or(1))
            .string_storage(if o.shared_strings.unwrap_or(false) { StringStorage::Shared } else { StringStorage::Inline })
            .flush_threshold(flush_threshold)
            .date_1904(o.date1904.unwrap_or(false))
            .cell_references(o.cell_references.unwrap_or(false));
        Engine { inner: Some(XlsxWriter::with_options(Vec::with_capacity(flush_threshold * 2), options)), styles: vec![StyleId::DEFAULT] }
    }

    fn inner(&mut self) -> Result<&mut XlsxWriter<Vec<u8>>> {
        self.inner.as_mut().ok_or_else(|| failed("the workbook is already finished"))
    }

    fn sheet(&mut self) -> Result<hypaxel::Sheet<'_, Vec<u8>>> {
        self.inner()?.current_worksheet().ok_or_else(|| failed("no open worksheet"))
    }

    fn style(&self, index: u32) -> Result<StyleId> {
        self.styles.get(index as usize).copied().ok_or_else(|| invalid("the style does not belong to this writer"))
    }

    /// Registers a cell format and returns its handle. Handle 0 is the default style.
    pub fn add_style(&mut self, options: StyleConfig) -> Result<u32> {
        let style = build_style(options)?;
        let id = self.inner()?.add_style(&style);
        self.styles.push(id);
        Ok((self.styles.len() - 1) as u32)
    }

    pub fn add_worksheet(&mut self, name: &str, o: SheetConfig) -> Result<()> {
        let mut sheet = hypaxel::SheetOptions::new().freeze_rows(o.freeze_rows.unwrap_or(0)).freeze_columns(o.freeze_columns.unwrap_or(0));
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
        if o.autofit.unwrap_or(false) {
            let defaults = AutoFit::default();
            sheet = sheet.autofit(
                AutoFit::new()
                    .sample_rows(o.autofit_sample_rows.unwrap_or(defaults.sample_rows))
                    .max_width(o.autofit_max_width.unwrap_or(defaults.max_width)),
            );
        }
        sheet = sheet.row_outline_levels(o.row_outline_levels.unwrap_or(0));
        if o.outline_summary_above.unwrap_or(false) {
            sheet = sheet.outline_summary_above();
        }
        self.inner()?.add_worksheet_with(name, &sheet).map_err(core)?;
        Ok(())
    }

    /// Writes a batch of cells encoded by the JavaScript `RowEncoder`. Returns the rows completed.
    pub fn write_batch(&mut self, data: &[u8]) -> Result<u32> {
        let mut reader = BatchReader { data, pos: 0 };
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
            let mut sheet = self.sheet()?;
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

    /// Starts the next row explicitly, to set its height (points), style, outline level or hidden state.
    pub fn begin_row(&mut self, o: RowConfig) -> Result<()> {
        let row = hypaxel::RowOptions {
            height: o.height,
            style: self.style(o.style.unwrap_or(0))?,
            outline_level: o.outline_level.unwrap_or(0),
            hidden: o.hidden.unwrap_or(false),
            collapsed: o.collapsed.unwrap_or(false),
        };
        self.sheet()?.begin_row_with(&row).map_err(core)?;
        Ok(())
    }

    pub fn add_table(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32, o: TableConfig) -> Result<()> {
        let mut table = Table::new(first_row, first_col, last_row, last_col)
            .columns(o.columns.unwrap_or_default())
            .style(parse_table_style(o.style.as_deref())?)
            .header_row(o.header_row.unwrap_or(true))
            .autofilter(o.autofilter.unwrap_or(true))
            .row_stripes(o.row_stripes.unwrap_or(true))
            .column_stripes(o.column_stripes.unwrap_or(false))
            .highlight_first_column(o.first_column.unwrap_or(false))
            .highlight_last_column(o.last_column.unwrap_or(false));
        if let Some(name) = o.name {
            table = table.name(name);
        }
        self.sheet()?.add_table(&table).map_err(core)?;
        Ok(())
    }

    pub fn skip_rows(&mut self, count: u32) -> Result<()> {
        self.sheet()?.skip_rows(count);
        Ok(())
    }

    pub fn merge_cells(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<()> {
        self.sheet()?.merge_cells(first_row, first_col, last_row, last_col).map_err(core)?;
        Ok(())
    }

    pub fn autofilter(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<()> {
        self.sheet()?.autofilter(first_row, first_col, last_row, last_col).map_err(core)?;
        Ok(())
    }

    /// 1-based number of the last written row of the current worksheet (0 before the first row).
    pub fn row(&mut self) -> Result<u32> {
        Ok(self.sheet()?.row())
    }

    /// Number of compressed bytes waiting to be taken.
    pub fn buffered(&mut self) -> Result<u32> {
        Ok(self.inner()?.get_ref().len() as u32)
    }

    /// The compressed bytes produced so far. The binding copies them out and clears the buffer,
    /// which keeps its capacity for the next chunk.
    pub fn output(&mut self) -> Result<&mut Vec<u8>> {
        Ok(self.inner()?.get_mut())
    }

    /// Completes the workbook and returns the remaining bytes.
    pub fn finish(&mut self) -> Result<Vec<u8>> {
        let inner = self.inner.take().ok_or_else(|| failed("the workbook is already finished"))?;
        inner.finish().map_err(core)
    }

    pub fn finished(&self) -> bool {
        self.inner.is_none()
    }
}

/// Returns the column letters for a 1-based column index (1 → "A", 27 → "AA").
pub fn column_name(column: u32) -> Result<String> {
    if !(1..=hypaxel::MAX_COLUMNS).contains(&column) {
        return Err(invalid("column must be within 1..=16384"));
    }
    Ok(hypaxel::column_name(column))
}

#[cfg(test)]
mod tests {
    use std::io::{Cursor, Read};

    use super::*;

    fn string(tag: u8, value: &str, out: &mut Vec<u8>) {
        out.push(tag);
        out.extend_from_slice(&(value.len() as u32).to_le_bytes());
        out.extend_from_slice(value.as_bytes());
    }

    fn sheet_xml(bytes: Vec<u8>) -> String {
        let mut archive = zip::ZipArchive::new(Cursor::new(bytes)).unwrap();
        let mut xml = String::new();
        archive.by_name("xl/worksheets/sheet1.xml").unwrap().read_to_string(&mut xml).unwrap();
        xml
    }

    #[test]
    fn decodes_every_tag() {
        let mut engine = Engine::new(WriterConfig::default());
        let bold = engine.add_style(StyleConfig { bold: Some(true), ..Default::default() }).unwrap();
        engine.add_worksheet("Data", SheetConfig::default()).unwrap();

        let mut batch = Vec::new();
        string(TAG_STRING, "héllo", &mut batch);
        batch.push(TAG_NUMBER);
        batch.extend_from_slice(&1.5f64.to_le_bytes());
        batch.extend_from_slice(&[TAG_TRUE, TAG_FALSE, TAG_BLANK]);
        batch.push(TAG_SKIP);
        batch.extend_from_slice(&2u32.to_le_bytes());
        batch.push(TAG_STYLE);
        batch.extend_from_slice(&bold.to_le_bytes());
        string(TAG_FORMULA, "SUM(B1:B1)", &mut batch);
        batch.push(TAG_DATE);
        batch.extend_from_slice(&0f64.to_le_bytes());
        batch.push(TAG_END_ROW);

        assert_eq!(engine.write_batch(&batch).unwrap(), 1);
        assert_eq!(engine.row().unwrap(), 1);
        let xml = sheet_xml(engine.finish().unwrap());
        assert!(xml.contains("héllo"));
        assert!(xml.contains("<v>1.5</v>"));
        assert!(xml.contains("<f>SUM(B1:B1)</f>"));
        assert!(xml.contains("<v>25569</v>"), "the Unix epoch is Excel serial 25569: {xml}");
    }

    #[test]
    fn rejects_malformed_batches() {
        let mut engine = Engine::new(WriterConfig::default());
        engine.add_worksheet("Data", SheetConfig::default()).unwrap();
        assert!(matches!(engine.write_batch(&[TAG_NUMBER, 0, 0]), Err(Error::Invalid(m)) if m == "truncated cell batch"));
        assert!(matches!(engine.write_batch(&[TAG_STRING, 2, 0, 0, 0, 0xff, 0xfe]), Err(Error::Invalid(_))));
        assert!(matches!(engine.write_batch(&[99]), Err(Error::Invalid(m)) if m == "unknown cell tag 99"));
        assert!(matches!(engine.write_batch(&[TAG_STYLE, 7, 0, 0, 0]), Err(Error::Invalid(_))));
    }

    #[test]
    fn rejects_use_after_finish() {
        let mut engine = Engine::new(WriterConfig::default());
        engine.add_worksheet("Data", SheetConfig::default()).unwrap();
        engine.finish().unwrap();
        assert!(engine.finished());
        assert!(matches!(engine.finish(), Err(Error::Failed(_))));
        assert!(matches!(engine.write_batch(&[TAG_END_ROW]), Err(Error::Failed(_))));
    }

    #[test]
    fn rejects_invalid_options() {
        let mut engine = Engine::new(WriterConfig::default());
        assert!(engine.add_style(StyleConfig { fill: Some("red".into()), ..Default::default() }).is_err());
        assert!(engine.add_style(StyleConfig { border: Some("wavy".into()), ..Default::default() }).is_err());
        engine.add_worksheet("Data", SheetConfig::default()).unwrap();
        let table = TableConfig { style: Some("TableStyleFancy".into()), ..Default::default() };
        assert!(engine.add_table(1, 1, 2, 2, table).is_err());
        assert!(column_name(0).is_err());
        assert_eq!(column_name(27).unwrap(), "AA");
    }
}
