//! `Serialize`-based row writing on [`Sheet`] and [`XlsxWriter`].

use std::io::Write;

use serde::Serialize;

use crate::error::Result;
use crate::options::SheetOptions;
use crate::ser::{RecordContext, RecordOptions, default_header_style, estimate_widths, field_names};
use crate::style::StyleId;
use crate::writer::{Sheet, XlsxWriter};

impl<W> Sheet<'_, W> {
    fn header_style(&mut self, options: &RecordOptions) -> StyleId {
        match options.header_style {
            Some(style) => style,
            None => self.writer.cells.add_style(&default_header_style()),
        }
    }
}

impl<W> XlsxWriter<W> {
    /// Sheet options for a table of records: frozen header row and widths estimated from the field names.
    fn table_options<T: Serialize>(first: Option<&T>) -> Result<SheetOptions> {
        let mut options = SheetOptions::new().freeze_rows(1);
        if let Some(first) = first {
            options = options.column_widths(&estimate_widths(&field_names(first)?));
        }
        Ok(options)
    }
}

impl<W: Write> Sheet<'_, W> {
    /// Writes one row per record (see the [`records`](crate::records) module for the mapping rules),
    /// below any rows already written. The header is derived from the first record.
    pub fn write_records<I>(&mut self, records: I, options: &RecordOptions) -> Result<()>
    where
        I: IntoIterator,
        I::Item: Serialize,
    {
        let mut iter = records.into_iter().peekable();
        let header_style = self.header_style(options);
        let ctx = RecordContext::begin(&mut self.writer.cells, iter.peek(), options, header_style)?;
        for record in iter {
            ctx.write(&mut self.writer.cells, &record)?;
            self.end_row_checked()?;
        }
        ctx.end(&mut self.writer.cells)
    }

    #[inline]
    fn end_row_checked(&mut self) -> Result<()> {
        self.writer.cells.take_error()?;
        if self.writer.cells.output_ready() {
            self.writer.flush()?;
        }
        Ok(())
    }
}

impl<W: Write> XlsxWriter<W> {
    /// Adds a worksheet containing the records as a table: styled and frozen header row, auto
    /// filter and column widths estimated from the field names.
    pub fn write_sheet_records<I>(&mut self, name: &str, records: I) -> Result<()>
    where
        I: IntoIterator,
        I::Item: Serialize,
    {
        let mut iter = records.into_iter().peekable();
        let options = Self::table_options(iter.peek())?;
        let mut sheet = self.add_worksheet_with(name, &options)?;
        sheet.write_records(iter, &RecordOptions::new().autofilter(true))
    }
}

#[cfg(feature = "tokio")]
mod async_records {
    use futures_util::{Stream, StreamExt, pin_mut};
    use tokio::io::AsyncWrite;

    use super::*;

    impl<W: AsyncWrite + Unpin> Sheet<'_, W> {
        /// Async variant of [`Sheet::write_records`] for an in-memory iterator.
        pub async fn write_records_async<I>(&mut self, records: I, options: &RecordOptions) -> Result<()>
        where
            I: IntoIterator,
            I::Item: Serialize,
        {
            let mut iter = records.into_iter().peekable();
            let header_style = self.header_style(options);
            let ctx = RecordContext::begin(&mut self.writer.cells, iter.peek(), options, header_style)?;
            for record in iter {
                ctx.write(&mut self.writer.cells, &record)?;
                self.writer.cells.take_error()?;
                self.writer.drain_if_ready_async().await?;
            }
            ctx.end(&mut self.writer.cells)
        }

        /// Writes records from an async stream (e.g. `sqlx::query_as(..).fetch(&pool)` mapped to
        /// `T`), so rows flow from the database to the destination without being collected.
        pub async fn write_records_stream<S, T, E>(&mut self, records: S, options: &RecordOptions) -> Result<()>
        where
            S: Stream<Item = std::result::Result<T, E>>,
            T: Serialize,
            E: std::fmt::Display,
        {
            pin_mut!(records);
            let first = match records.next().await {
                Some(item) => Some(item.map_err(|e| crate::Error::Custom(e.to_string()))?),
                None => None,
            };
            let header_style = self.header_style(options);
            let ctx = RecordContext::begin(&mut self.writer.cells, first.as_ref(), options, header_style)?;
            if let Some(first) = first {
                ctx.write(&mut self.writer.cells, &first)?;
            }
            while let Some(item) = records.next().await {
                let record = item.map_err(|e| crate::Error::Custom(e.to_string()))?;
                ctx.write(&mut self.writer.cells, &record)?;
                self.writer.cells.take_error()?;
                self.writer.drain_if_ready_async().await?;
            }
            ctx.end(&mut self.writer.cells)
        }
    }

    impl<W: AsyncWrite + Unpin> XlsxWriter<W> {
        /// Async variant of [`XlsxWriter::write_sheet_records`].
        pub async fn write_sheet_records_async<I>(&mut self, name: &str, records: I) -> Result<()>
        where
            I: IntoIterator,
            I::Item: Serialize,
        {
            let mut iter = records.into_iter().peekable();
            let options = Self::table_options(iter.peek())?;
            let mut sheet = self.add_worksheet_with(name, &options)?;
            sheet.write_records_async(iter, &RecordOptions::new().autofilter(true)).await
        }

        /// Adds a table worksheet filled from a fallible async stream; the first item provides the
        /// header and column widths.
        pub async fn write_sheet_stream<S, T, E>(&mut self, name: &str, records: S) -> Result<()>
        where
            S: Stream<Item = std::result::Result<T, E>>,
            T: Serialize,
            E: std::fmt::Display,
        {
            pin_mut!(records);
            let first = match records.next().await {
                Some(item) => Some(item.map_err(|e| crate::Error::Custom(e.to_string()))?),
                None => None,
            };
            let options = Self::table_options(first.as_ref())?;
            let mut sheet = self.add_worksheet_with(name, &options)?;
            let rest = futures_util::stream::iter(first.map(Ok::<T, E>)).chain(records);
            sheet.write_records_stream(rest, &RecordOptions::new().autofilter(true)).await
        }
    }
}
