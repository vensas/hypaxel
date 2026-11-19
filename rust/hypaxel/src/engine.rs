//! The sans-IO engine: turns cell calls into compressed XLSX bytes in memory. The public writers
//! decide when and how (sync/async) those bytes reach the destination.

use std::hash::BuildHasher;

use hashbrown::{DefaultHashBuilder, HashTable};

use crate::error::{Error, Result};
use crate::fit::{FitState, write_col};
use crate::options::{Options, RowOptions, SheetOptions, StringStorage};
use crate::style::{StyleId, StyleRegistry, ValueKind};
use crate::table::{Table, TableMeta, overlaps};
use crate::xml;
use crate::zip::ZipStream;

/// Maximum rows per worksheet.
pub const MAX_ROWS: u32 = 1_048_576;
/// Maximum columns per worksheet.
pub const MAX_COLUMNS: u32 = 16_384;
const MAX_TEXT: usize = 32_767;
/// Enough room for `<c r="XFD1048576" s="4294967295" t="inlineStr">` and a number in `<v>`.
const CELL_RESERVE: usize = 128;

// Day numbers (chrono's `num_days_from_ce`, 0001-01-01 = 1) of each date system's epoch.
#[cfg_attr(not(any(feature = "chrono", feature = "serde")), allow(dead_code))]
const EPOCH_1900: i64 = 693_594; // 1899-12-30
#[cfg_attr(not(any(feature = "chrono", feature = "serde")), allow(dead_code))]
const EPOCH_1904: i64 = 695_056; // 1904-01-01
#[cfg_attr(not(any(feature = "chrono", feature = "serde")), allow(dead_code))]
const MAX_SERIAL: i64 = 2_958_465; // 9999-12-31 in the 1900 system

pub(crate) struct SheetMeta {
    pub name: String,
    pub hidden: bool,
    pub autofilter: Option<(u32, u32, u32, u32)>,
}

/// Shared string table: each distinct string is stored once; the hash table holds indices.
struct SharedStrings {
    table: HashTable<u32>,
    strings: Vec<Box<str>>,
    hasher: DefaultHashBuilder,
    references: u64,
}

impl SharedStrings {
    fn new() -> Self {
        SharedStrings { table: HashTable::new(), strings: Vec::new(), hasher: DefaultHashBuilder::default(), references: 0 }
    }

    #[inline]
    fn index_of(&mut self, value: &str) -> u32 {
        self.references += 1;
        let hash = self.hasher.hash_one(value);
        let strings = &self.strings;
        if let Some(&index) = self.table.find(hash, |&i| &*strings[i as usize] == value) {
            return index;
        }
        let index = self.strings.len() as u32;
        self.strings.push(value.into());
        let (strings, hasher) = (&self.strings, &self.hasher);
        self.table.insert_unique(hash, index, |&i| hasher.hash_one(&*strings[i as usize]));
        index
    }
}

/// Writes cells. Obtained through [`Sheet`](crate::Sheet); exposed so that custom [`IntoCell`](crate::IntoCell)
/// implementations can delegate to existing ones.
pub struct CellWriter {
    opts: Options,
    zip: ZipStream,
    pub(crate) xml: Vec<u8>,
    styles: StyleRegistry,
    sst: Option<SharedStrings>,
    pub(crate) sheets: Vec<SheetMeta>,
    sheet_open: bool,
    row: u32,
    column: u32,
    last_column: u32,
    row_open: bool,
    /// The open row was started explicitly (`begin_row*`); until it has a cell, the next
    /// whole-row write fills it instead of starting another row.
    row_explicit: bool,
    row_ref: [u8; 8],
    row_ref_len: usize,
    merges: Vec<(u32, u32, u32, u32)>,
    error: Option<Error>,
    uses_formulas: bool,
    /// Present while the current sheet's auto-fit sample is held back.
    fit: Option<Box<FitState>>,
    row_outline_levels: u8,
    tables: Vec<TableMeta>,
    table_names: Vec<String>,
    table_count: u32,
}

impl CellWriter {
    pub(crate) fn new(opts: Options) -> Self {
        let zip = ZipStream::new(opts.compression_level, opts.flush_threshold + opts.flush_threshold / 2);
        let sst = (opts.string_storage == StringStorage::Shared).then(SharedStrings::new);
        CellWriter {
            xml: Vec::with_capacity(opts.xml_buffer_size + 4096),
            opts,
            zip,
            styles: StyleRegistry::new(),
            sst,
            sheets: Vec::new(),
            sheet_open: false,
            row: 0,
            column: 0,
            last_column: 0,
            row_open: false,
            row_explicit: false,
            row_ref: [0; 8],
            row_ref_len: 0,
            merges: Vec::new(),
            error: None,
            uses_formulas: false,
            fit: None,
            row_outline_levels: 0,
            tables: Vec::new(),
            table_names: Vec::new(),
            table_count: 0,
        }
    }

    // ------------------------------------------------------------------ output plumbing

    pub(crate) fn output(&mut self) -> &mut Vec<u8> {
        &mut self.zip.output
    }

    #[inline]
    pub(crate) fn output_ready(&self) -> bool {
        self.zip.output.len() >= self.opts.flush_threshold
    }

    fn compress_xml(&mut self) {
        if !self.xml.is_empty() {
            self.zip.write(&self.xml);
            self.xml.clear();
        }
    }

    #[inline]
    fn maybe_compress(&mut self) {
        if self.xml.len() >= self.opts.xml_buffer_size && self.fit.is_none() {
            self.compress_xml();
        }
    }

    fn write_part(&mut self, name: &str, build: impl FnOnce(&Self, &mut Vec<u8>)) {
        let mut buf = std::mem::take(&mut self.xml);
        buf.clear();
        build(self, &mut buf);
        self.zip.start_entry(name);
        self.zip.write(&buf);
        self.zip.finish_entry();
        buf.clear();
        self.xml = buf;
    }

    pub(crate) fn take_error(&mut self) -> Result<()> {
        match self.error.take() {
            Some(e) => Err(e),
            None => Ok(()),
        }
    }

    #[cold]
    fn fail(&mut self, error: Error) {
        if self.error.is_none() {
            self.error = Some(error);
        }
    }

    // ------------------------------------------------------------------ styles

    pub(crate) fn add_style(&mut self, style: &crate::Style) -> StyleId {
        self.styles.add(style)
    }

    // ------------------------------------------------------------------ worksheets

    pub(crate) fn start_sheet(&mut self, name: &str, options: &SheetOptions) -> Result<()> {
        validate_sheet_name(name)?;
        let lower = name.to_lowercase();
        if self.sheets.iter().any(|s| s.name.to_lowercase() == lower) {
            return Err(Error::DuplicateSheetName(name.to_owned()));
        }
        if options.frozen_rows >= MAX_ROWS || options.frozen_columns >= MAX_COLUMNS {
            return Err(Error::InvalidArgument("frozen panes are out of range"));
        }
        for column in options.columns.values() {
            if column.style.0 >= self.styles.len() {
                return Err(Error::InvalidStyle(column.style.0));
            }
        }
        if options.columns.keys().any(|&c| c == 0 || c > MAX_COLUMNS) {
            return Err(Error::InvalidArgument("column index must be within 1..=16384"));
        }
        if options.row_outline_levels > 7 {
            return Err(Error::InvalidArgument("row outline levels must be within 0..=7"));
        }
        if let Some(fit) = &options.autofit {
            if fit.sample_rows == 0 {
                return Err(Error::InvalidArgument("the auto-fit sample needs at least one row"));
            }
            if fit.max_width.is_nan() || fit.max_width <= 0.0 {
                return Err(Error::InvalidArgument("the auto-fit maximum width must be positive"));
            }
        }

        self.complete_sheet();
        let index = self.sheets.len();
        self.sheets.push(SheetMeta { name: name.to_owned(), hidden: options.hidden, autofilter: None });
        self.zip.start_entry(&format!("xl/worksheets/sheet{}.xml", index + 1));
        self.sheet_open = true;
        self.row = 0;
        self.column = 0;
        self.last_column = 0;
        self.row_open = false;
        self.row_explicit = false;
        self.merges.clear();
        self.row_outline_levels = options.row_outline_levels;
        self.write_sheet_header(index, options);
        Ok(())
    }

    fn write_sheet_header(&mut self, index: usize, o: &SheetOptions) {
        let x = &mut self.xml;
        x.extend_from_slice(xml::DECLARATION);
        x.extend_from_slice(b"<worksheet xmlns=\"");
        x.extend_from_slice(xml::NS_MAIN);
        x.extend_from_slice(b"\" xmlns:r=\"");
        x.extend_from_slice(xml::NS_REL);
        x.extend_from_slice(b"\">");
        if o.tab_color.is_some() || o.outline_summary_above {
            x.extend_from_slice(b"<sheetPr>");
            if let Some(color) = o.tab_color {
                x.extend_from_slice(b"<tabColor rgb=\"");
                x.extend_from_slice(format!("{:08X}", color.0).as_bytes());
                x.extend_from_slice(b"\"/>");
            }
            if o.outline_summary_above {
                x.extend_from_slice(b"<outlinePr summaryBelow=\"0\"/>");
            }
            x.extend_from_slice(b"</sheetPr>");
        }
        x.extend_from_slice(b"<sheetViews><sheetView workbookViewId=\"0\"");
        if index == 0 {
            x.extend_from_slice(b" tabSelected=\"1\"");
        }
        if !o.show_grid_lines {
            x.extend_from_slice(b" showGridLines=\"0\"");
        }
        if o.frozen_rows > 0 || o.frozen_columns > 0 {
            let pane: &[u8] = match (o.frozen_rows > 0, o.frozen_columns > 0) {
                (true, true) => b"bottomRight",
                (true, false) => b"bottomLeft",
                _ => b"topRight",
            };
            x.extend_from_slice(b"><pane");
            if o.frozen_columns > 0 {
                x.extend_from_slice(b" xSplit=\"");
                xml::push_int(x, o.frozen_columns);
                x.push(b'"');
            }
            if o.frozen_rows > 0 {
                x.extend_from_slice(b" ySplit=\"");
                xml::push_int(x, o.frozen_rows);
                x.push(b'"');
            }
            x.extend_from_slice(b" topLeftCell=\"");
            push_cell_ref(x, o.frozen_rows + 1, o.frozen_columns + 1);
            x.extend_from_slice(b"\" activePane=\"");
            x.extend_from_slice(pane);
            x.extend_from_slice(b"\" state=\"frozen\"/><selection pane=\"");
            x.extend_from_slice(pane);
            x.extend_from_slice(b"\"/></sheetView></sheetViews>");
        } else {
            x.extend_from_slice(b"/></sheetViews>");
        }
        x.extend_from_slice(b"<sheetFormatPr defaultRowHeight=\"15\"");
        if o.row_outline_levels > 0 {
            x.extend_from_slice(b" outlineLevelRow=\"");
            xml::push_int(x, o.row_outline_levels);
            x.push(b'"');
        }
        x.extend_from_slice(b"/>");
        if let Some(autofit) = o.autofit {
            // `<cols>` is inserted here once the sample has been measured.
            self.fit = Some(Box::new(FitState::new(autofit, o.columns.clone(), x.len())));
        } else if !o.columns.is_empty() {
            x.extend_from_slice(b"<cols>");
            for (&index, column) in &o.columns {
                write_col(x, index, column);
            }
            x.extend_from_slice(b"</cols>");
        }
        x.extend_from_slice(b"<sheetData>");
    }

    /// Inserts the fitted `<cols>` into the held-back XML and resumes streaming.
    #[cold]
    fn end_fit(&mut self) {
        let Some(fit) = self.fit.take() else { return };
        let mut cols = Vec::new();
        fit.write_cols(&mut cols);
        self.xml.splice(fit.cols_at..fit.cols_at, cols);
        if self.xml.len() >= self.opts.xml_buffer_size {
            self.compress_xml();
        }
        self.xml.shrink_to(self.opts.xml_buffer_size + 4096);
    }

    pub(crate) fn complete_sheet(&mut self) {
        if !self.sheet_open {
            return;
        }
        if self.row_open {
            self.close_row();
        }
        self.end_fit();
        let autofilter = self.sheets.last().and_then(|s| s.autofilter);
        let tables = std::mem::take(&mut self.tables);
        let x = &mut self.xml;
        x.extend_from_slice(b"</sheetData>");
        if let Some((r1, c1, r2, c2)) = autofilter {
            x.extend_from_slice(b"<autoFilter ref=\"");
            push_range(x, r1, c1, r2, c2);
            x.extend_from_slice(b"\"/>");
        }
        if !self.merges.is_empty() {
            x.extend_from_slice(b"<mergeCells count=\"");
            xml::push_int(x, self.merges.len());
            x.extend_from_slice(b"\">");
            for &(r1, c1, r2, c2) in &self.merges {
                x.extend_from_slice(b"<mergeCell ref=\"");
                push_range(x, r1, c1, r2, c2);
                x.extend_from_slice(b"\"/>");
            }
            x.extend_from_slice(b"</mergeCells>");
        }
        x.extend_from_slice(b"<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>");
        if !tables.is_empty() {
            x.extend_from_slice(b"<tableParts count=\"");
            xml::push_int(x, tables.len());
            x.extend_from_slice(b"\">");
            for i in 1..=tables.len() {
                x.extend_from_slice(b"<tablePart r:id=\"rId");
                xml::push_int(x, i);
                x.extend_from_slice(b"\"/>");
            }
            x.extend_from_slice(b"</tableParts>");
        }
        x.extend_from_slice(b"</worksheet>");
        self.compress_xml();
        self.zip.finish_entry();
        self.sheet_open = false;

        if !tables.is_empty() {
            for table in &tables {
                self.write_part(&format!("xl/tables/table{}.xml", table.id), |_, x| table.write_xml(x));
            }
            let sheet = self.sheets.len();
            self.write_part(&format!("xl/worksheets/_rels/sheet{sheet}.xml.rels"), |_, x| crate::parts::sheet_rels(x, &tables));
        }
    }

    pub(crate) fn has_open_sheet(&self) -> bool {
        self.sheet_open
    }

    /// Completes the workbook: remaining worksheet, styles, workbook, relationships, central directory.
    pub(crate) fn finish(&mut self) -> Result<()> {
        self.take_error()?;
        if self.sheets.is_empty() {
            self.start_sheet("Sheet1", &SheetOptions::default())?;
        }
        self.complete_sheet();
        if self.sheets.iter().all(|s| s.hidden) {
            return Err(Error::NoVisibleWorksheet);
        }

        let shared = self.sst.as_ref().is_some_and(|s| !s.strings.is_empty());
        if shared {
            self.write_part("xl/sharedStrings.xml", |w, x| {
                let sst = w.sst.as_ref().unwrap();
                x.extend_from_slice(xml::DECLARATION);
                x.extend_from_slice(b"<sst xmlns=\"");
                x.extend_from_slice(xml::NS_MAIN);
                x.extend_from_slice(b"\" count=\"");
                xml::push_int(x, sst.references);
                x.extend_from_slice(b"\" uniqueCount=\"");
                xml::push_int(x, sst.strings.len());
                x.extend_from_slice(b"\">");
                for s in &sst.strings {
                    x.extend_from_slice(b"<si>");
                    xml::text_element(x, s);
                    x.extend_from_slice(b"</si>");
                }
                x.extend_from_slice(b"</sst>");
            });
        }
        self.write_part("xl/styles.xml", |w, x| w.styles.write_xml(x));
        self.write_part("xl/workbook.xml", |w, x| crate::parts::workbook(x, &w.sheets, w.uses_formulas, w.opts.date_1904));
        self.write_part("xl/_rels/workbook.xml.rels", |w, x| crate::parts::workbook_rels(x, w.sheets.len(), shared));
        self.write_part("[Content_Types].xml", |w, x| crate::parts::content_types(x, w.sheets.len(), w.table_count, shared));
        self.write_part("_rels/.rels", |_, x| crate::parts::root_rels(x));
        self.zip.finish();
        Ok(())
    }

    // ------------------------------------------------------------------ rows

    /// Current (or last written) 1-based row number; 0 before the first row.
    pub fn row(&self) -> u32 {
        self.row
    }

    /// 1-based column of the last written cell in the current row; 0 at the start of a row.
    pub fn column(&self) -> u32 {
        self.column
    }

    pub(crate) fn begin_row_at(&mut self, row: u32, options: &RowOptions) -> Result<()> {
        if row == 0 || row > MAX_ROWS {
            return Err(Error::RowOutOfRange(row as u64));
        }
        if row <= self.row {
            return Err(Error::RowOrder { row, previous: self.row });
        }
        if options.style.0 >= self.styles.len() {
            return Err(Error::InvalidStyle(options.style.0));
        }
        if let Some(h) = options.height {
            if !(0.0..=409.0).contains(&h) {
                return Err(Error::InvalidArgument("row height must be within 0..=409 points"));
            }
        }
        if options.outline_level > 7 {
            return Err(Error::InvalidArgument("row outline level must be within 0..=7"));
        }
        if options.outline_level > self.row_outline_levels {
            return Err(Error::InvalidArgument(
                "row outline level exceeds the sheet's declared depth; declare the deepest level (row_outline_levels) when adding the worksheet",
            ));
        }
        if self.row_open {
            self.close_row();
        }
        self.open_row(row, Some(options));
        self.row_explicit = true;
        Ok(())
    }

    #[inline]
    fn open_row(&mut self, row: u32, options: Option<&RowOptions>) {
        self.row = row;
        self.column = 0;
        self.last_column = 0;
        self.row_open = true;
        let mut buf = itoa::Buffer::new();
        let digits = buf.format(row).as_bytes();
        self.row_ref[..digits.len()].copy_from_slice(digits);
        self.row_ref_len = digits.len();

        let x = &mut self.xml;
        x.extend_from_slice(b"<row r=\"");
        x.extend_from_slice(digits);
        x.push(b'"');
        if let Some(o) = options {
            if let Some(h) = o.height {
                x.extend_from_slice(b" ht=\"");
                xml::push_f64(x, h);
                x.extend_from_slice(b"\" customHeight=\"1\"");
            }
            if o.style.0 != 0 {
                x.extend_from_slice(b" s=\"");
                xml::push_int(x, o.style.0);
                x.extend_from_slice(b"\" customFormat=\"1\"");
            }
            if o.hidden {
                x.extend_from_slice(b" hidden=\"1\"");
            }
            if o.outline_level > 0 {
                x.extend_from_slice(b" outlineLevel=\"");
                xml::push_int(x, o.outline_level);
                x.push(b'"');
            }
            if o.collapsed {
                x.extend_from_slice(b" collapsed=\"1\"");
            }
        }
        x.push(b'>');
    }

    /// Row that the next whole-row write lands in.
    #[cfg_attr(not(feature = "serde"), allow(dead_code))]
    pub(crate) fn next_row(&self) -> u32 {
        if self.row_open && self.row_explicit && self.column == 0 { self.row } else { self.row + 1 }
    }

    #[inline]
    pub(crate) fn begin_next_row(&mut self) {
        if self.row_open {
            if self.row_explicit && self.column == 0 {
                return;
            }
            self.close_row();
        }
        if self.row >= MAX_ROWS {
            self.fail(Error::RowOutOfRange(self.row as u64 + 1));
            // Keep writing into the last row so the XML stays well-formed.
            self.row -= 1;
        }
        self.open_row(self.row + 1, None);
    }

    #[inline]
    pub(crate) fn close_row(&mut self) {
        self.xml.extend_from_slice(b"</row>");
        self.row_open = false;
        self.row_explicit = false;
        if let Some(fit) = self.fit.as_deref_mut() {
            fit.rows += 1;
            if fit.sample_complete(self.xml.len()) {
                self.end_fit();
            }
        }
        self.maybe_compress();
    }

    /// Ends the current row; with no open row, leaves an empty row.
    #[inline]
    pub(crate) fn end_row(&mut self) {
        if self.row_open {
            self.close_row();
        } else if self.row < MAX_ROWS {
            self.row += 1;
        }
    }

    pub(crate) fn skip_rows(&mut self, count: u32) {
        if self.row_open {
            self.close_row();
        }
        self.row = self.row.saturating_add(count).min(MAX_ROWS);
    }

    pub(crate) fn skip_cells(&mut self, count: u32) {
        if !self.row_open {
            self.begin_next_row();
        }
        let column = self.column.saturating_add(count);
        if column > MAX_COLUMNS {
            self.fail(Error::TooManyColumns { row: self.row });
            self.column = MAX_COLUMNS;
        } else {
            self.column = column;
        }
    }

    pub(crate) fn merge(&mut self, r1: u32, c1: u32, r2: u32, c2: u32) -> Result<()> {
        validate_range(r1, c1, r2, c2)?;
        if r1 == r2 && c1 == c2 {
            return Err(Error::InvalidArgument("a merged range must span more than one cell"));
        }
        if self.tables.iter().any(|t| overlaps(t.range, (r1, c1, r2, c2))) {
            return Err(Error::OverlappingRange("merged cells cannot overlap a table"));
        }
        self.merges.push((r1, c1, r2, c2));
        Ok(())
    }

    pub(crate) fn autofilter(&mut self, r1: u32, c1: u32, r2: u32, c2: u32) -> Result<()> {
        validate_range(r1, c1, r2, c2)?;
        if self.tables.iter().any(|t| overlaps(t.range, (r1, c1, r2, c2))) {
            return Err(Error::OverlappingRange("a worksheet auto filter cannot overlap a table (tables have their own filter)"));
        }
        if let Some(sheet) = self.sheets.last_mut() {
            sheet.autofilter = Some((r1, c1, r2, c2));
        }
        Ok(())
    }

    pub(crate) fn add_table(&mut self, table: &Table) -> Result<()> {
        let names = &self.table_names;
        let meta = TableMeta::new(table, self.table_count + 1, |name| {
            let lower = name.to_lowercase();
            names.contains(&lower)
        })?;
        if self.tables.iter().any(|t| overlaps(t.range, meta.range)) {
            return Err(Error::OverlappingRange("tables cannot overlap each other"));
        }
        if self.merges.iter().any(|&m| overlaps(m, meta.range)) {
            return Err(Error::OverlappingRange("a table cannot overlap merged cells"));
        }
        if self.sheets.last().and_then(|s| s.autofilter).is_some_and(|f| overlaps(f, meta.range)) {
            return Err(Error::OverlappingRange("a table cannot overlap the worksheet auto filter (tables have their own filter)"));
        }
        self.table_count += 1;
        self.table_names.push(meta.name.to_lowercase());
        self.tables.push(meta);
        Ok(())
    }

    // ------------------------------------------------------------------ cells

    /// Opens a `<c` element with reference and style attributes as needed. Returns false (and
    /// records an error) when the cell cannot be written; nothing is emitted in that case.
    #[inline(always)]
    fn begin_cell(&mut self, style: StyleId) -> bool {
        if !self.row_open {
            self.begin_next_row();
        }
        let column = self.column + 1;
        if column > MAX_COLUMNS {
            self.fail(Error::TooManyColumns { row: self.row });
            return false;
        }
        if style.0 >= self.styles.len() {
            self.column = column;
            self.fail(Error::InvalidStyle(style.0));
            return false;
        }
        self.column = column;

        let x = &mut self.xml;
        x.reserve(CELL_RESERVE);
        if column == self.last_column + 1 && !self.opts.cell_references {
            // References are optional; readers infer consecutive cells. Saves ~20% of the XML.
            x.extend_from_slice(b"<c");
        } else {
            x.extend_from_slice(b"<c r=\"");
            push_column_name(x, column);
            x.extend_from_slice(&self.row_ref[..self.row_ref_len]);
            x.push(b'"');
        }
        self.last_column = column;
        if style.0 != 0 {
            x.extend_from_slice(b" s=\"");
            xml::push_int(x, style.0);
            x.push(b'"');
        }
        true
    }

    /// Writes an integer cell (Excel stores numbers as f64: beyond ±2^53 precision is lost).
    #[inline]
    pub fn write_i64(&mut self, value: i64, style: StyleId) {
        if self.begin_cell(style) {
            if let Some(fit) = self.fit.as_deref_mut() {
                fit.number(&self.styles, self.column, style.0, value as f64);
            }
            let x = &mut self.xml;
            x.extend_from_slice(b"><v>");
            xml::push_int(x, value);
            x.extend_from_slice(b"</v></c>");
        }
    }

    /// Writes an unsigned integer cell.
    #[inline]
    pub fn write_u64(&mut self, value: u64, style: StyleId) {
        if self.begin_cell(style) {
            if let Some(fit) = self.fit.as_deref_mut() {
                fit.number(&self.styles, self.column, style.0, value as f64);
            }
            let x = &mut self.xml;
            x.extend_from_slice(b"><v>");
            xml::push_int(x, value);
            x.extend_from_slice(b"</v></c>");
        }
    }

    /// Writes a number cell; NaN and infinities become the `#NUM!` error.
    #[inline]
    pub fn write_f64(&mut self, value: f64, style: StyleId) {
        if self.begin_cell(style) {
            if let Some(fit) = self.fit.as_deref_mut() {
                fit.number(&self.styles, self.column, style.0, value);
            }
            let x = &mut self.xml;
            if value.is_finite() {
                x.extend_from_slice(b"><v>");
                xml::push_f64(x, value);
                x.extend_from_slice(b"</v></c>");
            } else {
                x.extend_from_slice(b" t=\"e\"><v>#NUM!</v></c>");
            }
        }
    }

    #[inline]
    pub fn write_bool(&mut self, value: bool, style: StyleId) {
        if self.begin_cell(style) {
            if let Some(fit) = self.fit.as_deref_mut() {
                fit.text(&self.styles, self.column, style.0, if value { "TRUE" } else { "FALSE" });
            }
            self.xml.extend_from_slice(if value { b" t=\"b\"><v>1</v></c>" } else { b" t=\"b\"><v>0</v></c>" });
        }
    }

    /// Writes a text cell.
    #[inline]
    pub fn write_str(&mut self, value: &str, style: StyleId) {
        // Excel counts UTF-16 code units; the UTF-8 byte length is an upper bound, so only long
        // strings pay for an exact count.
        if value.len() > MAX_TEXT {
            let len = value.encode_utf16().count();
            if len > MAX_TEXT {
                let (row, column) = (self.row.max(1), self.column + 1);
                self.fail(Error::TextTooLong { row, column, len });
                self.skip_cells(1);
                return;
            }
        }
        if !self.begin_cell(style) {
            return;
        }
        if let Some(fit) = self.fit.as_deref_mut() {
            fit.text(&self.styles, self.column, style.0, value);
        }
        match &mut self.sst {
            Some(sst) => {
                let index = sst.index_of(value);
                let x = &mut self.xml;
                x.extend_from_slice(b" t=\"s\"><v>");
                xml::push_int(x, index);
                x.extend_from_slice(b"</v></c>");
            }
            None => {
                let x = &mut self.xml;
                x.extend_from_slice(b" t=\"inlineStr\"><is>");
                xml::text_element(x, value);
                x.extend_from_slice(b"</is></c>");
                if value.len() > 4096 {
                    self.maybe_compress();
                }
            }
        }
    }

    /// Writes a formula (leading `=` optional). Excel computes it when the file is opened.
    pub fn write_formula(&mut self, formula: &str, style: StyleId) {
        let formula = formula.strip_prefix('=').unwrap_or(formula);
        if self.begin_cell(style) {
            self.uses_formulas = true;
            let x = &mut self.xml;
            x.extend_from_slice(b"><f>");
            xml::escape_into(x, formula);
            x.extend_from_slice(b"</f></c>");
        }
    }

    /// Writes an empty cell carrying a format, or skips the cell for the default style.
    #[inline]
    pub fn write_blank(&mut self, style: StyleId) {
        if style.0 == 0 {
            self.skip_cells(1);
        } else if self.begin_cell(style) {
            self.xml.extend_from_slice(b"/>");
        }
    }

    #[cfg_attr(not(any(feature = "chrono", feature = "serde")), allow(dead_code))]
    /// Writes a date/time from a day number (`num_days_from_ce` convention: 0001-01-01 = 1) and the
    /// nanoseconds of the day. Out-of-range dates are written as text produced by `fallback`.
    #[inline]
    pub(crate) fn write_temporal(
        &mut self,
        days_from_ce: i64,
        nanos: u64,
        kind: ValueKind,
        style: StyleId,
        fallback: impl FnOnce() -> String,
    ) {
        let day = if self.opts.date_1904 {
            let day = days_from_ce - EPOCH_1904;
            (0..=MAX_SERIAL - 1462).contains(&day).then_some(day)
        } else {
            let mut day = days_from_ce - EPOCH_1900;
            if day < 61 {
                day -= 1; // Excel's fictitious 1900-02-29
            }
            (1..=MAX_SERIAL).contains(&day).then_some(day)
        };
        let Some(day) = day else {
            self.write_str(&fallback(), style);
            return;
        };
        let style = StyleId(self.styles.value_variant(style.0, kind));
        if self.begin_cell(style) {
            if let Some(fit) = self.fit.as_deref_mut() {
                fit.number(&self.styles, self.column, style.0, day as f64);
            }
            let x = &mut self.xml;
            x.extend_from_slice(b"><v>");
            xml::push_serial(x, day, nanos);
            x.extend_from_slice(b"</v></c>");
        }
    }

    #[cfg_attr(not(any(feature = "chrono", feature = "serde")), allow(dead_code))]
    /// Writes a time of day or a duration expressed in days.
    #[inline]
    pub(crate) fn write_days(&mut self, days: f64, kind: ValueKind, style: StyleId) {
        let style = StyleId(self.styles.value_variant(style.0, kind));
        self.write_f64(days, style);
    }
}

fn validate_sheet_name(name: &str) -> Result<()> {
    let invalid = name.is_empty()
        || name.encode_utf16().count() > 31
        || name.contains([':', '\\', '/', '?', '*', '[', ']'])
        || name.starts_with('\'')
        || name.ends_with('\'')
        || name.eq_ignore_ascii_case("History");
    if invalid { Err(Error::InvalidSheetName(name.to_owned())) } else { Ok(()) }
}

fn validate_range(r1: u32, c1: u32, r2: u32, c2: u32) -> Result<()> {
    let rows_ok = (1..=MAX_ROWS).contains(&r1) && (r1..=MAX_ROWS).contains(&r2);
    let cols_ok = (1..=MAX_COLUMNS).contains(&c1) && (c1..=MAX_COLUMNS).contains(&c2);
    if rows_ok && cols_ok { Ok(()) } else { Err(Error::InvalidArgument("invalid cell range")) }
}

pub(crate) fn push_column_name(x: &mut Vec<u8>, mut column: u32) {
    let mut buf = [0u8; 3];
    let mut i = 3;
    while column > 0 {
        column -= 1;
        i -= 1;
        buf[i] = b'A' + (column % 26) as u8;
        column /= 26;
    }
    x.extend_from_slice(&buf[i..]);
}

pub(crate) fn push_cell_ref(x: &mut Vec<u8>, row: u32, column: u32) {
    push_column_name(x, column);
    xml::push_int(x, row);
}

pub(crate) fn push_range(x: &mut Vec<u8>, r1: u32, c1: u32, r2: u32, c2: u32) {
    push_cell_ref(x, r1, c1);
    x.push(b':');
    push_cell_ref(x, r2, c2);
}

/// Returns the column letters for a 1-based column index (1 → "A", 27 → "AA").
pub fn column_name(column: u32) -> String {
    assert!((1..=MAX_COLUMNS).contains(&column), "column must be within 1..=16384");
    let mut v = Vec::with_capacity(3);
    push_column_name(&mut v, column);
    String::from_utf8(v).unwrap()
}

/// Returns an A1-style reference for a 1-based row and column.
pub fn cell_ref(row: u32, column: u32) -> String {
    format!("{}{}", column_name(column), row)
}
