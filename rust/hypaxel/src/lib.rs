//! # Hypaxel
//!
//! High-performance, forward-only XLSX (Excel) writer.
//!
//! - **Constant memory:** cells are serialized and compressed immediately; a million-row export
//!   uses the same ~200 KiB of buffers as a ten-row one.
//! - **Streams anywhere:** any [`std::io::Write`] (it need not be seekable) or, with the `tokio`
//!   feature, any `tokio::io::AsyncWrite`, e.g. an HTTP response body. See `hypaxel-axum`.
//! - **No allocation per cell:** hand-written UTF-8 XML, table-driven escaping, `itoa`/`zmij`
//!   number formatting, integer-only date serials, our own streaming ZIP (with ZIP64).
//! - **Ergonomic:** rows from tuples (`sheet.add_row(("a", 1, 2.5))?`), any `T: Serialize` as a
//!   record (`writer.write_sheet_records("Orders", &orders)?`), chrono dates, styles, merged
//!   cells, frozen panes, auto filters, Excel tables, row grouping, auto-fit columns, formulas.
//!
//! ```
//! use hypaxel::{XlsxWriter, SheetOptions, Style, Styled, Formula, formats};
//!
//! let mut writer = XlsxWriter::new(Vec::new());
//! let header = writer.add_style(&Style::new().bold().fill(hypaxel::Color::LIGHT_GRAY));
//! let money = writer.add_style(&Style::new().num_format(formats::THOUSANDS2));
//!
//! let mut sheet = writer.add_worksheet_with("Sales", &SheetOptions::new().freeze_rows(1).column_widths(&[20.0, 12.0]))?;
//! sheet.add_row((Styled("Product", header), Styled("Revenue", header)))?;
//! sheet.add_row(("Widget", Styled(1234.5, money)))?;
//! sheet.add_row(("Gadget", Styled(99.99, money)))?;
//! sheet.write("Total").write_styled(Formula("SUM(B2:B3)"), money).end_row()?;
//!
//! let xlsx: Vec<u8> = writer.finish()?;
//! # Ok::<(), hypaxel::Error>(())
//! ```
//!
//! ## Features
//!
//! | Feature | Default | Enables |
//! | --- | --- | --- |
//! | `chrono` | yes | chrono dates/times/durations as native Excel values |
//! | `serde` | yes | `T: Serialize` → rows ([`Sheet::write_records`], [`XlsxWriter::write_sheet_records`]) |
//! | `tokio` | no | `*_async` methods for `AsyncWrite` destinations and `Stream` record sources |

#![warn(missing_debug_implementations)]
#![deny(unsafe_code)]

mod cell;
mod engine;
mod error;
mod fit;
mod options;
mod parts;
#[cfg(feature = "serde")]
mod record_io;
#[cfg(feature = "serde")]
mod ser;
mod style;
mod table;
mod writer;
mod xml;
mod zip;

pub use cell::{Blank, Formula, IntoCell, IntoRow, Styled};
pub use engine::{CellWriter, MAX_COLUMNS, MAX_ROWS, cell_ref, column_name};
pub use error::{Error, Result};
pub use options::{AutoFit, Column, Options, RowOptions, SheetOptions, StringStorage};
pub use style::{Alignment, Border, BorderLine, BorderStyle, Color, Font, HAlign, Style, StyleId, VAlign, formats};
pub use table::{Table, TableStyle};
pub use writer::{Sheet, XlsxWriter};

/// Serde integration: record options and `serialize_with` helpers for native dates.
///
/// (Named `records` rather than `serde` so that `use hypaxel::*` never shadows the serde crate.)
#[cfg(feature = "serde")]
pub mod records {
    pub use crate::ser::RecordOptions;
    #[cfg(feature = "chrono")]
    pub use crate::ser::{ExcelTemporal, excel_date};
}

/// The XLSX media type.
pub const CONTENT_TYPE: &str = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

impl std::fmt::Debug for CellWriter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CellWriter").field("row", &self.row()).field("column", &self.column()).finish_non_exhaustive()
    }
}

impl<W> std::fmt::Debug for XlsxWriter<W> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("XlsxWriter").field("worksheets", &self.worksheet_count()).finish_non_exhaustive()
    }
}

impl<W> std::fmt::Debug for Sheet<'_, W> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Sheet").field("row", &self.row()).field("column", &self.column()).finish_non_exhaustive()
    }
}
