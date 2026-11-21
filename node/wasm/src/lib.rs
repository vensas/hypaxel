//! WebAssembly bindings for the hypaxel XLSX writer and reader.
//!
//! Exposes the same surface as the N-API `NativeWriter`, `NativeWorkbook` and `NativeRows` in
//! `node/src/`, so the TypeScript layer in `node/ts/core.ts` drives either engine unchanged.

use hypaxel_js_engine::{BATCH_BYTES, Engine, ReadConfig, RowStream, Workbook};
use js_sys::Uint8Array;
use serde::de::DeserializeOwned;
use wasm_bindgen::prelude::*;

fn to_js(e: hypaxel_js_engine::Error) -> JsError {
    JsError::new(e.message())
}

fn options<T: DeserializeOwned + Default>(value: JsValue) -> Result<T, JsError> {
    if value.is_undefined() || value.is_null() {
        return Ok(T::default());
    }
    serde_wasm_bindgen::from_value(value).map_err(|e| JsError::new(&format!("invalid options: {e}")))
}

/// In-memory XLSX writer. Output accumulates in a buffer that JavaScript takes with `drain`.
#[wasm_bindgen]
pub struct WasmWriter {
    engine: Engine,
}

#[wasm_bindgen]
impl WasmWriter {
    #[wasm_bindgen(constructor)]
    pub fn new(options_value: JsValue) -> Result<WasmWriter, JsError> {
        Ok(WasmWriter { engine: Engine::new(options(options_value)?) })
    }

    /// Registers a cell format and returns its handle. Handle 0 is the default style.
    #[wasm_bindgen(js_name = addStyle)]
    pub fn add_style(&mut self, options_value: JsValue) -> Result<u32, JsError> {
        self.engine.add_style(options(options_value)?).map_err(to_js)
    }

    #[wasm_bindgen(js_name = addWorksheet)]
    pub fn add_worksheet(&mut self, name: &str, options_value: JsValue) -> Result<(), JsError> {
        self.engine.add_worksheet(name, options(options_value)?).map_err(to_js)
    }

    /// Writes a batch of cells encoded by the JavaScript `RowEncoder`. Returns the rows completed.
    #[wasm_bindgen(js_name = writeBatch)]
    pub fn write_batch(&mut self, data: &[u8]) -> Result<u32, JsError> {
        self.engine.write_batch(data).map_err(to_js)
    }

    #[wasm_bindgen(js_name = beginRow)]
    pub fn begin_row(&mut self, options_value: JsValue) -> Result<(), JsError> {
        self.engine.begin_row(options(options_value)?).map_err(to_js)
    }

    #[wasm_bindgen(js_name = addTable)]
    pub fn add_table(
        &mut self,
        first_row: u32,
        first_col: u32,
        last_row: u32,
        last_col: u32,
        options_value: JsValue,
    ) -> Result<(), JsError> {
        self.engine.add_table(first_row, first_col, last_row, last_col, options(options_value)?).map_err(to_js)
    }

    #[wasm_bindgen(js_name = skipRows)]
    pub fn skip_rows(&mut self, count: u32) -> Result<(), JsError> {
        self.engine.skip_rows(count).map_err(to_js)
    }

    #[wasm_bindgen(js_name = mergeCells)]
    pub fn merge_cells(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<(), JsError> {
        self.engine.merge_cells(first_row, first_col, last_row, last_col).map_err(to_js)
    }

    pub fn autofilter(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<(), JsError> {
        self.engine.autofilter(first_row, first_col, last_row, last_col).map_err(to_js)
    }

    /// 1-based number of the last written row of the current worksheet (0 before the first row).
    pub fn row(&mut self) -> Result<u32, JsError> {
        self.engine.row().map_err(to_js)
    }

    /// Number of compressed bytes waiting to be drained.
    pub fn buffered(&mut self) -> Result<u32, JsError> {
        self.engine.buffered().map_err(to_js)
    }

    /// Returns and removes the compressed bytes produced so far, copied out of WebAssembly memory.
    pub fn drain(&mut self) -> Result<Uint8Array, JsError> {
        let buffer = self.engine.output().map_err(to_js)?;
        let chunk = Uint8Array::from(buffer.as_slice());
        buffer.clear();
        Ok(chunk)
    }

    /// Completes the workbook and returns the remaining bytes.
    pub fn finish(&mut self) -> Result<Uint8Array, JsError> {
        Ok(Uint8Array::from(self.engine.finish().map_err(to_js)?.as_slice()))
    }

    #[wasm_bindgen(getter)]
    pub fn finished(&self) -> bool {
        self.engine.finished()
    }
}

/// An open workbook, read from bytes copied into WebAssembly memory.
#[wasm_bindgen]
pub struct WasmWorkbook {
    inner: Option<Workbook>,
}

#[wasm_bindgen]
impl WasmWorkbook {
    #[wasm_bindgen(js_name = openBytes)]
    pub fn open_bytes(bytes: Vec<u8>, options_value: JsValue) -> Result<WasmWorkbook, JsError> {
        let config: ReadConfig = options(options_value)?;
        Ok(WasmWorkbook { inner: Some(Workbook::open_bytes(bytes, &config).map_err(to_js)?) })
    }

    fn inner(&self) -> Result<&Workbook, JsError> {
        self.inner.as_ref().ok_or_else(|| JsError::new("the workbook is closed"))
    }

    /// `[{ name, visible, kind }]` in workbook order.
    pub fn sheets(&self) -> Result<JsValue, JsError> {
        serde_wasm_bindgen::to_value(self.inner()?.sheets()).map_err(|e| JsError::new(&e.to_string()))
    }

    #[wasm_bindgen(getter)]
    pub fn date1904(&self) -> Result<bool, JsError> {
        Ok(self.inner()?.date1904())
    }

    /// The Excel tables of the sheet at a 0-based index, as an array of objects.
    pub fn tables(&self, index: u32) -> Result<JsValue, JsError> {
        let tables = self.inner()?.tables(index).map_err(to_js)?;
        serde_wasm_bindgen::to_value(&tables).map_err(|e| JsError::new(&e.to_string()))
    }

    /// Rows of the sheet at a 0-based index.
    pub fn rows(&self, index: u32) -> Result<WasmRows, JsError> {
        Ok(WasmRows { inner: self.inner()?.rows(index).map_err(to_js)? })
    }

    /// Releases the workbook. Rows already obtained keep working.
    pub fn close(&mut self) {
        self.inner = None;
    }
}

/// One sheet's rows, in batches encoded as described in `node/engine/src/read.rs`.
#[wasm_bindgen]
pub struct WasmRows {
    inner: RowStream,
}

#[wasm_bindgen]
impl WasmRows {
    /// The next batch of rows, or `null` at the end of the sheet.
    #[wasm_bindgen(js_name = next)]
    pub fn next_batch(&mut self) -> Result<Option<Uint8Array>, JsError> {
        let mut out = Vec::with_capacity(BATCH_BYTES + BATCH_BYTES / 4);
        Ok(self.inner.next_batch(&mut out).map_err(to_js)?.then(|| Uint8Array::from(out.as_slice())))
    }

    /// Stops reading.
    pub fn close(&mut self) {
        self.inner.close();
    }
}

/// Returns the column letters for a 1-based column index (1 → "A", 27 → "AA").
#[wasm_bindgen(js_name = columnName)]
pub fn column_name(column: u32) -> Result<String, JsError> {
    hypaxel_js_engine::column_name(column).map_err(to_js)
}
