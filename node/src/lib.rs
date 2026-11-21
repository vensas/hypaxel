//! Node.js bindings for the hypaxel XLSX writer and reader.
//!
//! The public API lives in `node/ts/`. This module adapts [`hypaxel_js_engine::Engine`], which the
//! WebAssembly build in `node/wasm` shares, to N-API.

use hypaxel_js_engine::{Engine, RowConfig, SheetConfig, StyleConfig, TableConfig, WriterConfig};
use napi::bindgen_prelude::*;
use napi_derive::napi;

mod read;

fn to_napi(e: hypaxel_js_engine::Error) -> Error {
    let status = match e {
        hypaxel_js_engine::Error::Invalid(_) => Status::InvalidArg,
        hypaxel_js_engine::Error::Failed(_) => Status::GenericFailure,
    };
    Error::new(status, e.to_string())
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

impl From<NativeWriterOptions> for WriterConfig {
    fn from(o: NativeWriterOptions) -> Self {
        WriterConfig {
            compression_level: o.compression_level,
            shared_strings: o.shared_strings,
            flush_threshold: o.flush_threshold,
            date1904: o.date1904,
            cell_references: o.cell_references,
        }
    }
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

impl From<NativeStyleOptions> for StyleConfig {
    fn from(o: NativeStyleOptions) -> Self {
        StyleConfig {
            bold: o.bold,
            italic: o.italic,
            underline: o.underline,
            strikethrough: o.strikethrough,
            font_name: o.font_name,
            font_size: o.font_size,
            font_color: o.font_color,
            fill: o.fill,
            border: o.border,
            border_color: o.border_color,
            border_bottom: o.border_bottom,
            align: o.align,
            valign: o.valign,
            wrap: o.wrap,
            indent: o.indent,
            rotation: o.rotation,
            num_format: o.num_format,
        }
    }
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
    pub autofit: Option<bool>,
    pub autofit_sample_rows: Option<u32>,
    pub autofit_max_width: Option<f64>,
    pub row_outline_levels: Option<u8>,
    pub outline_summary_above: Option<bool>,
}

impl From<NativeSheetOptions> for SheetConfig {
    fn from(o: NativeSheetOptions) -> Self {
        SheetConfig {
            freeze_rows: o.freeze_rows,
            freeze_columns: o.freeze_columns,
            column_widths: o.column_widths,
            hidden: o.hidden,
            show_grid_lines: o.show_grid_lines,
            tab_color: o.tab_color,
            autofit: o.autofit,
            autofit_sample_rows: o.autofit_sample_rows,
            autofit_max_width: o.autofit_max_width,
            row_outline_levels: o.row_outline_levels,
            outline_summary_above: o.outline_summary_above,
        }
    }
}

#[napi(object)]
#[derive(Default)]
pub struct NativeRowOptions {
    pub height: Option<f64>,
    pub style: Option<u32>,
    pub outline_level: Option<u8>,
    pub hidden: Option<bool>,
    pub collapsed: Option<bool>,
}

impl From<NativeRowOptions> for RowConfig {
    fn from(o: NativeRowOptions) -> Self {
        RowConfig { height: o.height, style: o.style, outline_level: o.outline_level, hidden: o.hidden, collapsed: o.collapsed }
    }
}

#[napi(object)]
#[derive(Default)]
pub struct NativeTableOptions {
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
    pub totals_row: Option<bool>,
}

impl From<NativeTableOptions> for TableConfig {
    fn from(o: NativeTableOptions) -> Self {
        TableConfig {
            name: o.name,
            columns: o.columns,
            style: o.style,
            header_row: o.header_row,
            autofilter: o.autofilter,
            row_stripes: o.row_stripes,
            column_stripes: o.column_stripes,
            first_column: o.first_column,
            last_column: o.last_column,
            totals_row: o.totals_row,
        }
    }
}

/// In-memory XLSX writer. Output accumulates in a buffer that JavaScript takes with `drain`.
#[napi]
pub struct NativeWriter {
    engine: Engine,
}

#[napi]
impl NativeWriter {
    #[napi(constructor)]
    pub fn new(options: Option<NativeWriterOptions>) -> Self {
        NativeWriter { engine: Engine::new(options.unwrap_or_default().into()) }
    }

    /// Registers a cell format and returns its handle. Handle 0 is the default style.
    #[napi]
    pub fn add_style(&mut self, options: NativeStyleOptions) -> Result<u32> {
        self.engine.add_style(options.into()).map_err(to_napi)
    }

    #[napi]
    pub fn add_worksheet(&mut self, name: String, options: Option<NativeSheetOptions>) -> Result<()> {
        self.engine.add_worksheet(&name, options.unwrap_or_default().into()).map_err(to_napi)
    }

    /// Writes a batch of cells encoded by the JavaScript `RowEncoder`. Returns the rows completed.
    #[napi]
    pub fn write_batch(&mut self, data: &[u8]) -> Result<u32> {
        self.engine.write_batch(data).map_err(to_napi)
    }

    /// Starts the next row explicitly, to set its height (points), style, outline level or hidden state.
    #[napi]
    pub fn begin_row(&mut self, options: Option<NativeRowOptions>) -> Result<()> {
        self.engine.begin_row(options.unwrap_or_default().into()).map_err(to_napi)
    }

    #[napi]
    pub fn add_table(
        &mut self,
        first_row: u32,
        first_col: u32,
        last_row: u32,
        last_col: u32,
        options: Option<NativeTableOptions>,
    ) -> Result<()> {
        self.engine.add_table(first_row, first_col, last_row, last_col, options.unwrap_or_default().into()).map_err(to_napi)
    }

    #[napi]
    pub fn skip_rows(&mut self, count: u32) -> Result<()> {
        self.engine.skip_rows(count).map_err(to_napi)
    }

    #[napi]
    pub fn merge_cells(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<()> {
        self.engine.merge_cells(first_row, first_col, last_row, last_col).map_err(to_napi)
    }

    #[napi]
    pub fn autofilter(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<()> {
        self.engine.autofilter(first_row, first_col, last_row, last_col).map_err(to_napi)
    }

    /// 1-based number of the last written row of the current worksheet (0 before the first row).
    #[napi]
    pub fn row(&mut self) -> Result<u32> {
        self.engine.row().map_err(to_napi)
    }

    /// Number of compressed bytes waiting to be drained.
    #[napi]
    pub fn buffered(&mut self) -> Result<u32> {
        self.engine.buffered().map_err(to_napi)
    }

    /// Returns and removes the compressed bytes produced so far.
    ///
    /// The bytes are copied into a buffer owned by the JavaScript engine. Handing over the native
    /// allocation instead would hide its size from the garbage collector, which then frees the
    /// chunks too late and lets memory grow with the size of the file.
    #[napi]
    pub fn drain<'env>(&mut self, env: &'env Env) -> Result<BufferSlice<'env>> {
        let buffer = self.engine.output().map_err(to_napi)?;
        let chunk = BufferSlice::copy_from(env, buffer.as_slice())?;
        buffer.clear();
        Ok(chunk)
    }

    /// Completes the workbook and returns the remaining bytes.
    #[napi]
    pub fn finish<'env>(&mut self, env: &'env Env) -> Result<BufferSlice<'env>> {
        BufferSlice::copy_from(env, self.engine.finish().map_err(to_napi)?.as_slice())
    }

    #[napi(getter)]
    pub fn finished(&self) -> bool {
        self.engine.finished()
    }
}

/// Returns the column letters for a 1-based column index (1 → "A", 27 → "AA").
#[napi]
pub fn column_name(column: u32) -> Result<String> {
    hypaxel_js_engine::column_name(column).map_err(to_napi)
}
