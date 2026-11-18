//! # hypaxel-axum
//!
//! Stream XLSX workbooks from [axum](https://docs.rs/axum) handlers. Rows are generated,
//! compressed and sent while the handler's data source is still producing them, so memory
//! stays constant (~200 KiB per download) no matter how large the export is.
//!
//! ```no_run
//! use axum::{Router, routing::get};
//! use hypaxel_axum::Xlsx;
//!
//! #[derive(serde::Serialize)]
//! struct Order { id: u32, customer: String, amount: f64 }
//!
//! async fn orders() -> Xlsx {
//!     let orders = (1..=100_000).map(|id| Order { id, customer: format!("Customer {id}"), amount: id as f64 * 1.5 });
//!     Xlsx::from_records("Orders", orders).filename("orders.xlsx")
//! }
//!
//! async fn report() -> Xlsx {
//!     Xlsx::from_fn(|writer| {
//!         let bold = writer.add_style(&hypaxel::Style::new().bold());
//!         let mut sheet = writer.add_worksheet("Summary")?;
//!         sheet.add_row((hypaxel::Styled("Region", bold), hypaxel::Styled("Revenue", bold)))?;
//!         sheet.add_row(("North", 1234.5))?;
//!         Ok(())
//!     })
//!     .filename("report.xlsx")
//! }
//!
//! let app: Router = Router::new().route("/orders.xlsx", get(orders)).route("/report.xlsx", get(report));
//! ```
//!
//! ## Execution model
//!
//! * [`Xlsx::from_fn`] and [`Xlsx::from_records`] run on Tokio's blocking pool (spreadsheet
//!   generation is CPU work) and hand compressed chunks to the response body through a bounded
//!   channel: a slow client applies backpressure instead of growing memory.
//! * [`Xlsx::from_async_fn`] and [`Xlsx::from_stream`] run as a Tokio task, for async data
//!   sources such as `sqlx` streams.
//!
//! If the client disconnects, the producer's next write fails and generation stops. If the
//! producer fails after the response has started, the body ends with an error and the client
//! sees an aborted download (the status code has already been sent).

#![warn(missing_docs, missing_debug_implementations)]
#![deny(unsafe_code)]

use std::fmt::{self, Write as _};
use std::future::Future;
use std::io;
use std::pin::Pin;

use axum::body::Body;
use axum::http::{HeaderValue, header};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use futures_util::{Stream, StreamExt};
use hypaxel::{Options, XlsxWriter};
use serde::Serialize;
use tokio::io::DuplexStream;
use tokio::sync::mpsc;

pub use hypaxel;

/// Destination of a blocking producer: compressed chunks go straight to the response body.
#[derive(Debug)]
pub struct BodyWriter {
    tx: mpsc::Sender<io::Result<Bytes>>,
}

impl io::Write for BodyWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.tx
            .blocking_send(Ok(Bytes::copy_from_slice(buf)))
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "client disconnected"))?;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Destination of an async producer.
pub type AsyncBodyWriter = DuplexStream;

type BlockingProducer = Box<dyn FnOnce(&mut XlsxWriter<BodyWriter>) -> hypaxel::Result<()> + Send>;
type AsyncProducer = Box<
    dyn FnOnce(XlsxWriter<AsyncBodyWriter>) -> Pin<Box<dyn Future<Output = hypaxel::Result<XlsxWriter<AsyncBodyWriter>>> + Send>> + Send,
>;

enum Producer {
    Blocking(BlockingProducer),
    Async(AsyncProducer),
}

/// An XLSX response. Build it with one of the constructors, optionally set a download
/// [`filename`](Xlsx::filename) and writer [`options`](Xlsx::options), and return it from a handler.
pub struct Xlsx {
    producer: Producer,
    filename: Option<String>,
    options: Options,
    channel_capacity: usize,
}

impl fmt::Debug for Xlsx {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let kind = match self.producer {
            Producer::Blocking(_) => "blocking",
            Producer::Async(_) => "async",
        };
        f.debug_struct("Xlsx").field("producer", &kind).field("filename", &self.filename).finish_non_exhaustive()
    }
}

impl Xlsx {
    fn new(producer: Producer) -> Self {
        Xlsx { producer, filename: None, options: Options::default(), channel_capacity: 8 }
    }

    /// Generates the workbook with synchronous code on Tokio's blocking pool. Worksheets, styles
    /// and rows are written through the provided writer; the workbook is completed afterwards.
    pub fn from_fn<F>(f: F) -> Self
    where
        F: FnOnce(&mut XlsxWriter<BodyWriter>) -> hypaxel::Result<()> + Send + 'static,
    {
        Self::new(Producer::Blocking(Box::new(f)))
    }

    /// A single worksheet with one row per record (styled, frozen header row and auto filter),
    /// generated on the blocking pool. Ideal for `Vec`s and lazy iterators.
    pub fn from_records<I>(sheet_name: impl Into<String>, records: I) -> Self
    where
        I: IntoIterator + Send + 'static,
        I::Item: Serialize,
    {
        let name = sheet_name.into();
        Self::from_fn(move |writer| writer.write_sheet_records(&name, records))
    }

    /// Generates the workbook with async code, e.g. to pull rows from a database. The closure
    /// receives the writer by value and must return it when done:
    ///
    /// ```no_run
    /// # use hypaxel_axum::Xlsx;
    /// Xlsx::from_async_fn(|mut writer| async move {
    ///     let mut sheet = writer.add_worksheet("Data")?;
    ///     for i in 0..10 {
    ///         sheet.add_row_async((i, i * i)).await?;
    ///     }
    ///     Ok(writer)
    /// });
    /// ```
    pub fn from_async_fn<F, Fut>(f: F) -> Self
    where
        F: FnOnce(XlsxWriter<AsyncBodyWriter>) -> Fut + Send + 'static,
        Fut: Future<Output = hypaxel::Result<XlsxWriter<AsyncBodyWriter>>> + Send + 'static,
    {
        Self::new(Producer::Async(Box::new(move |writer| Box::pin(f(writer)))))
    }

    /// A single worksheet filled from a fallible async stream (e.g. `sqlx::query_as(..).fetch(&pool)`),
    /// so rows flow from the database to the client without being collected.
    pub fn from_stream<S, T, E>(sheet_name: impl Into<String>, records: S) -> Self
    where
        S: Stream<Item = Result<T, E>> + Send + 'static,
        T: Serialize + Send + 'static,
        E: fmt::Display + Send + 'static,
    {
        let name = sheet_name.into();
        Self::from_async_fn(move |mut writer| async move {
            writer.write_sheet_stream(&name, records).await?;
            Ok(writer)
        })
    }

    /// Sends `Content-Disposition: attachment` with this file name (".xlsx" is appended if missing).
    pub fn filename(mut self, name: impl Into<String>) -> Self {
        let mut name = name.into();
        if !name.to_ascii_lowercase().ends_with(".xlsx") {
            name.push_str(".xlsx");
        }
        self.filename = Some(name);
        self
    }

    /// Writer options (compression level, string storage, flush threshold, …).
    pub fn options(mut self, options: Options) -> Self {
        self.options = options;
        self
    }

    fn body(self) -> Body {
        let options = self.options;
        match self.producer {
            Producer::Blocking(produce) => {
                let (tx, rx) = mpsc::channel(self.channel_capacity);
                let task = tokio::task::spawn_blocking(move || {
                    let mut writer = XlsxWriter::with_options(BodyWriter { tx }, options);
                    produce(&mut writer)?;
                    writer.finish().map(drop)
                });
                let data = futures_util::stream::unfold(rx, |mut rx| async move { rx.recv().await.map(|item| (item, rx)) });
                Body::from_stream(data.chain(outcome(task)))
            }
            Producer::Async(produce) => {
                let (tx, rx) = tokio::io::duplex(options.flush_threshold.max(16 * 1024));
                let task = tokio::spawn(async move {
                    let writer = produce(XlsxWriter::with_options(tx, options)).await?;
                    writer.finish_async().await.map(drop)
                    // Dropping the pipe's write half ends the data stream.
                });
                Body::from_stream(tokio_util::io::ReaderStream::new(rx).chain(outcome(task)))
            }
        }
    }
}

/// Runs after the data stream has ended and turns a failed (or panicked) producer into a body
/// error, so the client sees an aborted download rather than a truncated file that looks complete.
fn outcome(task: tokio::task::JoinHandle<hypaxel::Result<()>>) -> impl Stream<Item = io::Result<Bytes>> {
    futures_util::stream::once(async move {
        match task.await {
            Ok(Ok(())) => None,
            Ok(Err(e)) => Some(Err(io::Error::from(e))),
            Err(join) => Some(Err(io::Error::other(format!("xlsx producer failed: {join}")))),
        }
    })
    .filter_map(std::future::ready)
}

impl IntoResponse for Xlsx {
    fn into_response(self) -> Response {
        let disposition = self.filename.as_deref().map(content_disposition);
        let mut response = Response::new(self.body());
        let headers = response.headers_mut();
        headers.insert(header::CONTENT_TYPE, HeaderValue::from_static(hypaxel::CONTENT_TYPE));
        if let Some(value) = disposition.and_then(|d| HeaderValue::from_str(&d).ok()) {
            headers.insert(header::CONTENT_DISPOSITION, value);
        }
        headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
        response
    }
}

/// `attachment; filename="<ascii fallback>"; filename*=UTF-8''<percent-encoded>` (RFC 6266 / 5987).
fn content_disposition(name: &str) -> String {
    let fallback: String =
        name.chars().map(|c| if c.is_ascii() && !c.is_ascii_control() && c != '"' && c != '\\' { c } else { '_' }).collect();
    let mut value = format!("attachment; filename=\"{fallback}\"; filename*=UTF-8''");
    for &b in name.as_bytes() {
        if b.is_ascii_alphanumeric() || b"!#$&+-.^_`|~".contains(&b) {
            value.push(b as char);
        } else {
            let _ = write!(value, "%{b:02X}");
        }
    }
    value
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn content_disposition_encodes_non_ascii() {
        assert_eq!(content_disposition("orders.xlsx"), "attachment; filename=\"orders.xlsx\"; filename*=UTF-8''orders.xlsx");
        assert_eq!(
            content_disposition("Bericht März \"Q1\".xlsx"),
            "attachment; filename=\"Bericht M_rz _Q1_.xlsx\"; filename*=UTF-8''Bericht%20M%C3%A4rz%20%22Q1%22.xlsx"
        );
    }
}
