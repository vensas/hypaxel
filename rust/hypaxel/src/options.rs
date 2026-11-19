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
    /// Deepest row outline level used on this sheet (0–7). Excel sizes the outline bar from this
    /// value, which precedes the rows in the file, so it must be declared up front.
    pub row_outline_levels: u8,
    /// Puts the expand/collapse button of a row group above it (summary row first) instead of below.
    pub outline_summary_above: bool,
    /// Sizes columns without an explicit width to fit their content.
    pub autofit: Option<AutoFit>,
}

impl Default for SheetOptions {
    fn default() -> Self {
        SheetOptions {
            columns: BTreeMap::new(),
            frozen_rows: 0,
            frozen_columns: 0,
            show_grid_lines: true,
            hidden: false,
            tab_color: None,
            row_outline_levels: 0,
            outline_summary_above: false,
            autofit: None,
        }
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

    /// Declares the deepest row outline level (1–7) that [`RowOptions::outline_level`] will use.
    pub fn row_outline_levels(mut self, levels: u8) -> Self {
        self.row_outline_levels = levels;
        self
    }

    /// Places group buttons on the summary row above each group rather than below it.
    pub fn outline_summary_above(mut self) -> Self {
        self.outline_summary_above = true;
        self
    }

    /// Auto-fits columns that have no explicit width, measuring the first 1,000 rows.
    pub fn autofit_columns(mut self) -> Self {
        self.autofit = Some(AutoFit::default());
        self
    }

    pub fn autofit(mut self, autofit: AutoFit) -> Self {
        self.autofit = Some(autofit);
        self
    }
}

/// Column auto-fit settings.
///
/// Column widths precede the cell data in the file, so the writer holds back the sheet's XML until
/// `sample_rows` rows have been written (or the sheet ends), estimates the widths from those rows and
/// then streams as usual. Memory is bounded by the sample: at most `sample_rows` rows or 8 MiB of XML,
/// whichever comes first. Widths are estimated from the text, number format and font size of each
/// cell; formulas and merged cells are not measured.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AutoFit {
    /// Rows measured before the widths are fixed. Default: 1,000.
    pub sample_rows: u32,
    /// Upper bound for a fitted width, in characters. Default: 255 (Excel's maximum).
    pub max_width: f64,
}

impl Default for AutoFit {
    fn default() -> Self {
        AutoFit { sample_rows: 1000, max_width: 255.0 }
    }
}

impl AutoFit {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn sample_rows(mut self, rows: u32) -> Self {
        self.sample_rows = rows;
        self
    }

    pub fn max_width(mut self, width: f64) -> Self {
        self.max_width = width;
        self
    }
}

/// Settings for one row, used with [`Sheet::begin_row_with`](crate::Sheet::begin_row_with).
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct RowOptions {
    /// Height in points (0–409).
    pub height: Option<f64>,
    /// Format for empty cells in this row.
    pub style: StyleId,
    /// Outline (grouping) level 0–7; must not exceed [`SheetOptions::row_outline_levels`].
    pub outline_level: u8,
    pub hidden: bool,
    /// Marks the summary row of a collapsed group (the group's rows should be hidden).
    pub collapsed: bool,
}

impl RowOptions {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn height(mut self, points: f64) -> Self {
        self.height = Some(points);
        self
    }

    pub fn style(mut self, style: StyleId) -> Self {
        self.style = style;
        self
    }

    pub fn outline_level(mut self, level: u8) -> Self {
        self.outline_level = level;
        self
    }

    pub fn hidden(mut self) -> Self {
        self.hidden = true;
        self
    }

    pub fn collapsed(mut self) -> Self {
        self.collapsed = true;
        self
    }
}
