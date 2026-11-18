use std::collections::BTreeMap;

use crate::style::{Color, StyleId};

/// How string cell values are stored.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum StringStorage {
    /// Strings are written inline in each cell (the default): constant memory and the fastest option.
    #[default]
    Inline,
    /// Strings are de-duplicated into a shared string table: smaller files for repetitive text, but
    /// memory grows with the number of distinct strings.
    Shared,
}

/// Workbook-level options.
#[derive(Debug, Clone)]
pub struct Options {
    /// Deflate level 0–9. Default 1 (fastest); 6 gives ~10% smaller files at ~2–3x the CPU cost.
    pub compression_level: u32,
    /// Default: [`StringStorage::Inline`].
    pub string_storage: StringStorage,
    /// Compressed bytes buffered before they are written to the destination. Default: 64 KiB.
    pub flush_threshold: usize,
    /// Uncompressed XML buffered before it is compressed. Default: 64 KiB.
    pub xml_buffer_size: usize,
    /// When false (default) `r="B7"` references are only written for cells that don't directly follow
    /// the previous cell, which the format allows and mainstream readers support.
    pub cell_references: bool,
    /// Use the 1904 date system. Default: false (1900 system).
    pub date_1904: bool,
}

impl Default for Options {
    fn default() -> Self {
        Options {
            compression_level: 1,
            string_storage: StringStorage::Inline,
            flush_threshold: 64 * 1024,
            xml_buffer_size: 64 * 1024,
            cell_references: false,
            date_1904: false,
        }
    }
}

impl Options {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn compression_level(mut self, level: u32) -> Self {
        self.compression_level = level.min(9);
        self
    }

    pub fn string_storage(mut self, storage: StringStorage) -> Self {
        self.string_storage = storage;
        self
    }

    pub fn flush_threshold(mut self, bytes: usize) -> Self {
        self.flush_threshold = bytes.max(1024);
        self
    }

    pub fn cell_references(mut self, always: bool) -> Self {
        self.cell_references = always;
        self
    }

    pub fn date_1904(mut self, enabled: bool) -> Self {
        self.date_1904 = enabled;
        self
    }
}

/// Definition of one column.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Column {
    /// Width in characters of the default font.
    pub width: Option<f64>,
    /// Format for empty cells in this column.
    pub style: StyleId,
    pub hidden: bool,
}

impl Column {
    pub fn width(width: f64) -> Self {
        Column { width: Some(width), ..Default::default() }
    }
}

/// Worksheet settings that precede the cell data in the file, so they are fixed when the sheet is created.
#[derive(Debug, Clone)]
pub struct SheetOptions {
    /// Column definitions keyed by 1-based column index.
    pub columns: BTreeMap<u32, Column>,
    /// Rows frozen at the top (e.g. 1 to pin a header).
    pub frozen_rows: u32,
    /// Columns frozen at the left.
    pub frozen_columns: u32,
    pub show_grid_lines: bool,
    pub hidden: bool,
    pub tab_color: Option<Color>,
}

impl Default for SheetOptions {
    fn default() -> Self {
        SheetOptions { columns: BTreeMap::new(), frozen_rows: 0, frozen_columns: 0, show_grid_lines: true, hidden: false, tab_color: None }
    }
}

impl SheetOptions {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn freeze_rows(mut self, rows: u32) -> Self {
        self.frozen_rows = rows;
        self
    }

    pub fn freeze_columns(mut self, columns: u32) -> Self {
        self.frozen_columns = columns;
        self
    }

    /// Sets the widths of columns 1..=n.
    pub fn column_widths(mut self, widths: &[f64]) -> Self {
        for (i, &w) in widths.iter().enumerate() {
            self.columns.entry(i as u32 + 1).or_default().width = Some(w);
        }
        self
    }

    pub fn column(mut self, index: u32, column: Column) -> Self {
        self.columns.insert(index, column);
        self
    }

    pub fn hide_grid_lines(mut self) -> Self {
        self.show_grid_lines = false;
        self
    }

    pub fn hidden(mut self) -> Self {
        self.hidden = true;
        self
    }

    pub fn tab_color(mut self, color: Color) -> Self {
        self.tab_color = Some(color);
        self
    }
}
