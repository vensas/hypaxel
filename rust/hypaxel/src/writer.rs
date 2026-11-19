use std::io::Write;

use crate::cell::{IntoCell, IntoRow};
use crate::engine::CellWriter;
use crate::error::Result;
use crate::options::{Options, RowOptions, SheetOptions};
use crate::style::{Style, StyleId};
use crate::table::Table;

/// Forward-only XLSX writer.
///
/// Rows go top to bottom, cells left to right, one worksheet at a time. Every cell is serialized
/// immediately and compressed into a small buffer that is flushed to `W` whenever it passes
/// [`Options::flush_threshold`], so memory stays constant regardless of the number of rows.
///
/// `W` is any [`std::io::Write`] (files, `Vec<u8>`, sockets — it need not be seekable) or, with the
/// `tokio` feature, any `tokio::io::AsyncWrite`; the `*_async` methods are used in that case.
///
/// ```
/// use hypaxel::{XlsxWriter, Style, Styled, Formula};
///
/// let mut writer = XlsxWriter::new(Vec::new());
/// let bold = writer.add_style(&Style::new().bold());
///
/// let mut sheet = writer.add_worksheet("Sales")?;
/// sheet.add_row((Styled("Product", bold), Styled("Units", bold), Styled("Price", bold)))?;
/// sheet.add_row(("Widget", 3, 9.99))?;
/// sheet.add_row(("Gadget", 5, 19.5))?;
/// sheet.add_row(("Total", Formula("SUM(B2:B3)")))?;
///
/// let bytes: Vec<u8> = writer.finish()?;
/// # assert!(bytes.starts_with(b"PK"));
/// # Ok::<(), hypaxel::Error>(())
/// ```
pub struct XlsxWriter<W> {
    pub(crate) cells: CellWriter,
    pub(crate) out: W,
}

impl<W> XlsxWriter<W> {
    /// Creates a writer with default [`Options`].
    pub fn new(out: W) -> Self {
        Self::with_options(out, Options::default())
    }

    pub fn with_options(out: W, options: Options) -> Self {
        XlsxWriter { cells: CellWriter::new(options), out }
    }

    /// Registers a cell format. Identical styles share a handle.
    pub fn add_style(&mut self, style: &Style) -> StyleId {
        self.cells.add_style(style)
    }

    /// Adds a worksheet with default options and returns a handle for writing it. The previous
    /// worksheet is completed and cannot be written to anymore.
    pub fn add_worksheet(&mut self, name: &str) -> Result<Sheet<'_, W>> {
        self.add_worksheet_with(name, &SheetOptions::default())
    }

    /// Adds a worksheet with column widths, frozen panes, etc.
    pub fn add_worksheet_with(&mut self, name: &str, options: &SheetOptions) -> Result<Sheet<'_, W>> {
        self.cells.take_error()?;
        self.cells.start_sheet(name, options)?;
        Ok(Sheet { writer: self })
    }

    /// Re-borrows the worksheet currently being written, if any.
    pub fn current_worksheet(&mut self) -> Option<Sheet<'_, W>> {
        if self.cells.has_open_sheet() { Some(Sheet { writer: self }) } else { None }
    }

    /// Number of worksheets added so far.
    pub fn worksheet_count(&self) -> usize {
        self.cells.sheets.len()
    }

    /// Gives access to the destination.
    pub fn get_ref(&self) -> &W {
        &self.out
    }

    /// Gives mutable access to the destination, e.g. to take bytes already flushed into a `Vec<u8>`.
    /// Writing to it directly would corrupt the workbook.
    pub fn get_mut(&mut self) -> &mut W {
        &mut self.out
    }
}

impl<W: Write> XlsxWriter<W> {
    /// Completes the workbook, writes everything to the destination, flushes it and returns it.
    pub fn finish(mut self) -> Result<W> {
        self.cells.finish()?;
        self.drain()?;
        self.out.flush()?;
        Ok(self.out)
    }

    /// Writes buffered output to the destination now.
    pub fn flush(&mut self) -> Result<()> {
        self.drain()
    }

    #[inline]
    fn drain_if_ready(&mut self) -> Result<()> {
        if self.cells.output_ready() {
            self.drain()?;
        }
        Ok(())
    }

    fn drain(&mut self) -> Result<()> {
        let output = self.cells.output();
        if !output.is_empty() {
            self.out.write_all(output)?;
            output.clear();
        }
        Ok(())
    }
}

/// The worksheet currently being written. Cell methods are infallible and chainable; problems such
/// as over-long text are reported by the next `end_row` / `add_row` / `finish` call.
pub struct Sheet<'a, W> {
    pub(crate) writer: &'a mut XlsxWriter<W>,
}

impl<W> Sheet<'_, W> {
    #[inline]
    pub(crate) fn cells(&mut self) -> &mut CellWriter {
        &mut self.writer.cells
    }

    /// Current (or last written) 1-based row number; 0 before the first row.
    pub fn row(&self) -> u32 {
        self.writer.cells.row()
    }

    /// 1-based column of the last written cell; 0 at the start of a row.
    pub fn column(&self) -> u32 {
        self.writer.cells.column()
    }

    /// Starts the next row explicitly (cells start one implicitly), e.g. to set a height or style.
    /// A following [`add_row`](Self::add_row) fills this row rather than starting another.
    pub fn begin_row(&mut self, height: Option<f64>, style: StyleId) -> Result<&mut Self> {
        self.begin_row_with(&RowOptions { height, style, ..RowOptions::default() })
    }

    /// Starts the row with the given 1-based number, which must be greater than any row written so far.
    pub fn begin_row_at(&mut self, row: u32, height: Option<f64>, style: StyleId) -> Result<&mut Self> {
        self.begin_row_at_with(row, &RowOptions { height, style, ..RowOptions::default() })
    }

    /// Starts the next row with a height, style, outline (grouping) level or hidden state. Fill it
    /// with cell writes or a single [`add_row`](Self::add_row).
    ///
    /// ```
    /// use hypaxel::{RowOptions, SheetOptions, XlsxWriter};
    ///
    /// let mut writer = XlsxWriter::new(Vec::new());
    /// let mut sheet = writer.add_worksheet_with("Tree", &SheetOptions::new().row_outline_levels(1).outline_summary_above())?;
    /// sheet.add_row(("Region North", 300))?;
    /// sheet.begin_row_with(&RowOptions::new().outline_level(1))?.add_row(("Store A", 120))?;
    /// sheet.begin_row_with(&RowOptions::new().outline_level(1))?.write("Store B").write(180).end_row()?;
    /// writer.finish()?;
    /// # Ok::<(), hypaxel::Error>(())
    /// ```
    pub fn begin_row_with(&mut self, options: &RowOptions) -> Result<&mut Self> {
        let next = self.row() + 1;
        self.begin_row_at_with(next, options)
    }

    /// Like [`begin_row_with`](Self::begin_row_with) for the row with the given 1-based number.
    pub fn begin_row_at_with(&mut self, row: u32, options: &RowOptions) -> Result<&mut Self> {
        self.cells().begin_row_at(row, options)?;
        Ok(self)
    }

    /// Writes a value as the next cell.
    #[inline]
    pub fn write(&mut self, value: impl IntoCell) -> &mut Self {
        value.write_cell(self.cells(), StyleId::DEFAULT);
        self
    }

    /// Writes a value with a style as the next cell. Dates keep a date format: if the style has no
    /// number format, a variant with the default date format is used.
    #[inline]
    pub fn write_styled(&mut self, value: impl IntoCell, style: StyleId) -> &mut Self {
        value.write_cell(self.cells(), style);
        self
    }

    /// Leaves `count` cells empty.
    #[inline]
    pub fn skip_cells(&mut self, count: u32) -> &mut Self {
        self.cells().skip_cells(count);
        self
    }

    /// Leaves `count` rows empty; the next row is written below them.
    pub fn skip_rows(&mut self, count: u32) -> &mut Self {
        self.cells().skip_rows(count);
        self
    }

    /// Merges a range of cells (1-based, inclusive).
    pub fn merge_cells(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<&mut Self> {
        self.cells().merge(first_row, first_col, last_row, last_col)?;
        Ok(self)
    }

    /// Adds filter drop-downs to a range whose first row is the header (1-based, inclusive).
    pub fn autofilter(&mut self, first_row: u32, first_col: u32, last_row: u32, last_col: u32) -> Result<&mut Self> {
        self.cells().autofilter(first_row, first_col, last_row, last_col)?;
        Ok(self)
    }

    /// Turns a range of this worksheet into an Excel table (see [`Table`]). The range may include
    /// rows that are written later; it is recorded now and written when the worksheet completes.
    pub fn add_table(&mut self, table: &Table) -> Result<&mut Self> {
        self.cells().add_table(table)?;
        Ok(self)
    }

    #[inline]
    fn put_row(&mut self, row: impl IntoRow) {
        let cells = self.cells();
        cells.begin_next_row();
        row.write_cells(cells);
        cells.close_row();
    }
}

impl<W: Write> Sheet<'_, W> {
    /// Ends the current row and writes buffered output to the destination when it is large enough.
    #[inline]
    pub fn end_row(&mut self) -> Result<()> {
        self.cells().end_row();
        self.writer.cells.take_error()?;
        self.writer.drain_if_ready()
    }

    /// Writes a complete row: the row begun with `begin_row*` if it has no cells yet, otherwise the next one.
    #[inline]
    pub fn add_row(&mut self, row: impl IntoRow) -> Result<()> {
        self.put_row(row);
        self.writer.cells.take_error()?;
        self.writer.drain_if_ready()
    }
}

#[cfg(feature = "tokio")]
mod async_io {
    use tokio::io::{AsyncWrite, AsyncWriteExt};

    use super::*;

    impl<W: AsyncWrite + Unpin> XlsxWriter<W> {
        /// Completes the workbook, writes everything to the destination, flushes it and returns it.
        pub async fn finish_async(mut self) -> Result<W> {
            self.cells.finish()?;
            self.drain_async().await?;
            self.out.flush().await?;
            Ok(self.out)
        }

        /// Writes buffered output to the destination now.
        pub async fn flush_async(&mut self) -> Result<()> {
            self.drain_async().await
        }

        #[inline]
        pub(crate) async fn drain_if_ready_async(&mut self) -> Result<()> {
            if self.cells.output_ready() {
                self.drain_async().await?;
            }
            Ok(())
        }

        async fn drain_async(&mut self) -> Result<()> {
            let output = self.cells.output();
            if !output.is_empty() {
                self.out.write_all(output).await?;
                output.clear();
            }
            Ok(())
        }
    }

    impl<W: AsyncWrite + Unpin> Sheet<'_, W> {
        /// Ends the current row; writes buffered output to the destination when it is large enough.
        #[inline]
        pub async fn end_row_async(&mut self) -> Result<()> {
            self.cells().end_row();
            self.writer.cells.take_error()?;
            self.writer.drain_if_ready_async().await
        }

        /// Writes a complete row.
        #[inline]
        pub async fn add_row_async(&mut self, row: impl IntoRow) -> Result<()> {
            self.put_row(row);
            self.writer.cells.take_error()?;
            self.writer.drain_if_ready_async().await
        }
    }
}
