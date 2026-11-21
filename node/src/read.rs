//! N-API bindings for the streaming reader. Rows can be read on the calling thread or, with
//! `nextAsync`, on the libuv thread pool so that parsing and decompression do not block the event
//! loop.

use std::ptr;
use std::sync::{Arc, Mutex, TryLockError};

use hypaxel_js_engine::{BATCH_BYTES, ReadConfig, RowStream, Workbook};
use napi::bindgen_prelude::*;
use napi::{Task, ValueType, sys};
use napi_derive::napi;

use crate::to_napi;

#[napi(object)]
#[derive(Default)]
pub struct NativeReadOptions {
    pub max_shared_strings_bytes: Option<f64>,
    pub include_empty_rows: Option<bool>,
}

impl From<NativeReadOptions> for ReadConfig {
    fn from(o: NativeReadOptions) -> Self {
        ReadConfig { max_shared_strings_bytes: o.max_shared_strings_bytes, include_empty_rows: o.include_empty_rows }
    }
}

#[napi(object)]
pub struct NativeSheetInfo {
    pub name: String,
    pub visible: String,
    pub kind: String,
}

#[napi(object)]
pub struct NativeTable {
    pub name: String,
    pub first_row: u32,
    pub first_column: u32,
    pub last_row: u32,
    pub last_column: u32,
    pub columns: Vec<String>,
    pub style: String,
    pub header_row: bool,
    pub autofilter: bool,
    pub row_stripes: bool,
    pub column_stripes: bool,
    pub highlight_first_column: bool,
    pub highlight_last_column: bool,
}

/// An open workbook. Created with `openPath` or `openBytes`.
#[napi]
pub struct NativeWorkbook {
    inner: Option<Workbook>,
}

#[napi]
impl NativeWorkbook {
    #[napi(factory)]
    pub fn open_path(path: String, options: Option<NativeReadOptions>) -> Result<Self> {
        let inner = Workbook::open_path(path, &options.unwrap_or_default().into()).map_err(to_napi)?;
        Ok(NativeWorkbook { inner: Some(inner) })
    }

    /// Opens a workbook from bytes, which are copied.
    #[napi(factory)]
    pub fn open_bytes(bytes: &[u8], options: Option<NativeReadOptions>) -> Result<Self> {
        let inner = Workbook::open_bytes(bytes, &options.unwrap_or_default().into()).map_err(to_napi)?;
        Ok(NativeWorkbook { inner: Some(inner) })
    }

    fn inner(&self) -> Result<&Workbook> {
        self.inner.as_ref().ok_or_else(|| Error::new(Status::GenericFailure, "the workbook is closed"))
    }

    #[napi]
    pub fn sheets(&self) -> Result<Vec<NativeSheetInfo>> {
        Ok(self
            .inner()?
            .sheets()
            .iter()
            .map(|s| NativeSheetInfo { name: s.name.clone(), visible: s.visible.to_string(), kind: s.kind.to_string() })
            .collect())
    }

    #[napi(getter)]
    pub fn date1904(&self) -> Result<bool> {
        Ok(self.inner()?.date1904())
    }

    /// The Excel tables of the sheet at a 0-based index.
    #[napi]
    pub fn tables(&self, index: u32) -> Result<Vec<NativeTable>> {
        let tables = self.inner()?.tables(index).map_err(to_napi)?;
        Ok(tables
            .into_iter()
            .map(|t| NativeTable {
                name: t.name,
                first_row: t.first_row,
                first_column: t.first_column,
                last_row: t.last_row,
                last_column: t.last_column,
                columns: t.columns,
                style: t.style,
                header_row: t.header_row,
                autofilter: t.autofilter,
                row_stripes: t.row_stripes,
                column_stripes: t.column_stripes,
                highlight_first_column: t.highlight_first_column,
                highlight_last_column: t.highlight_last_column,
            })
            .collect())
    }

    /// Rows of the sheet at a 0-based index.
    #[napi]
    pub fn rows(&self, index: u32) -> Result<NativeRows> {
        Ok(NativeRows { inner: Arc::new(Mutex::new(self.inner()?.rows(index).map_err(to_napi)?)) })
    }

    /// Releases the workbook's file handle. Rows already obtained keep their own.
    #[napi]
    pub fn close(&mut self) {
        self.inner = None;
    }
}

/// One sheet's rows, in batches encoded as described in `node/engine/src/read.rs`.
#[napi]
pub struct NativeRows {
    inner: Arc<Mutex<RowStream>>,
}

fn next_batch(inner: &Mutex<RowStream>) -> Result<Option<Vec<u8>>> {
    let mut rows = match inner.try_lock() {
        Ok(rows) => rows,
        Err(TryLockError::WouldBlock) => return Err(Error::new(Status::GenericFailure, "the next rows are already being read")),
        Err(TryLockError::Poisoned(_)) => return Err(Error::new(Status::GenericFailure, "the rows are unusable after an earlier failure")),
    };
    let mut out = Vec::with_capacity(BATCH_BYTES + BATCH_BYTES / 4);
    Ok(rows.next_batch(&mut out).map_err(to_napi)?.then_some(out))
}

#[napi]
impl NativeRows {
    /// The next batch of rows, or `null` at the end of the sheet.
    #[napi(ts_return_type = "Buffer | null")]
    pub fn next(&self) -> Result<Batch> {
        next_batch(&self.inner).map(Batch)
    }

    /// Stops reading and releases the file handle. Does nothing while `nextAsync` is pending.
    #[napi]
    pub fn close(&self) {
        if let Ok(mut rows) = self.inner.try_lock() {
            rows.close();
        }
    }

    /// Like `next`, but reads on the libuv thread pool.
    #[napi(ts_return_type = "Promise<Buffer | null>")]
    pub fn next_async(&self) -> AsyncTask<NextBatch> {
        AsyncTask::new(NextBatch { inner: self.inner.clone() })
    }
}

pub struct NextBatch {
    inner: Arc<Mutex<RowStream>>,
}

impl Task for NextBatch {
    type Output = Option<Vec<u8>>;
    type JsValue = Batch;

    fn compute(&mut self) -> Result<Self::Output> {
        next_batch(&self.inner)
    }

    fn resolve(&mut self, _env: Env, output: Self::Output) -> Result<Self::JsValue> {
        Ok(Batch(output))
    }
}

/// A batch handed to JavaScript as a `Buffer` copied into memory the garbage collector accounts
/// for (see `NativeWriter::drain`), or `null`.
pub struct Batch(Option<Vec<u8>>);

impl TypeName for Batch {
    fn type_name() -> &'static str {
        "Buffer | null"
    }

    fn value_type() -> ValueType {
        ValueType::Object
    }
}

impl ToNapiValue for Batch {
    unsafe fn to_napi_value(env: sys::napi_env, val: Self) -> Result<sys::napi_value> {
        let Some(bytes) = val.0 else { return unsafe { Null::to_napi_value(env, Null) } };
        let mut value = ptr::null_mut();
        check_status!(
            unsafe { sys::napi_create_buffer_copy(env, bytes.len(), bytes.as_ptr().cast(), ptr::null_mut(), &mut value) },
            "failed to create a Buffer for the rows"
        )?;
        Ok(value)
    }
}
