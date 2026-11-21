//! Streaming XLSX reader.

mod sheet;
mod sst;
mod styles;
mod table;
mod value;
mod xml;
mod zip;

use std::fs::File;
use std::io::{BufReader, Read, Seek};
use std::path::Path;
use std::sync::Arc;

pub use sheet::{Cell, ColumnWidth, Row, Rows, SheetLayout};
pub use value::{DateKind, ExcelDateTime, Value};

use self::sst::SharedStrings;
use self::styles::Styles;
use self::xml::{Event, XmlReader, decode_into};
use self::zip::{Directory, EntryReader};
use crate::error::{Error, Result};
use crate::table::Table;

/// Most tables read from one worksheet.
const MAX_TABLES: usize = 4096;

/// Limits that protect against oversized or malicious workbooks.
#[derive(Debug, Clone)]
pub struct ReadOptions {
    max_shared_strings_bytes: u64,
    include_empty_rows: bool,
}

impl Default for ReadOptions {
    fn default() -> Self {
        Self { max_shared_strings_bytes: 1 << 30, include_empty_rows: false }
    }
}

impl ReadOptions {
    pub fn new() -> Self {
        Self::default()
    }

    /// Largest amount of text the shared string table may hold (default 1 GiB, at most 4 GiB).
    ///
    /// The shared string table is the one part of a workbook that must be held in memory to read
    /// rows; everything else is streamed. Lower this when reading untrusted uploads.
    pub fn max_shared_strings_bytes(mut self, bytes: u64) -> Self {
        self.max_shared_strings_bytes = bytes;
        self
    }

    /// Also return the rows that the file stores without any value (default false), for example
    /// a spacer row with a height or an empty hidden row. They have no cells but carry their
    /// number and properties. Rows that the file does not store are never returned.
    pub fn include_empty_rows(mut self, include: bool) -> Self {
        self.include_empty_rows = include;
        self
    }
}

/// Whether a sheet is shown in Excel's tab bar.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum Visibility {
    Visible,
    /// Hidden; the user can unhide it.
    Hidden,
    /// Hidden; only VBA can unhide it.
    VeryHidden,
}

/// What a sheet contains.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum SheetKind {
    /// A grid of cells.
    Worksheet,
    /// A sheet that only holds a chart; it has no rows.
    Chartsheet,
    /// A dialog sheet or Excel 4 macro sheet.
    Other,
}

/// A sheet listed in the workbook.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SheetInfo {
    name: String,
    visibility: Visibility,
    kind: SheetKind,
    part: Option<String>,
}

impl SheetInfo {
    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn visibility(&self) -> Visibility {
        self.visibility
    }

    pub fn kind(&self) -> SheetKind {
        self.kind
    }
}

#[derive(Debug)]
struct Meta {
    dir: Directory,
    sheets: Vec<SheetInfo>,
    styles: Arc<Styles>,
    shared_strings_part: Option<String>,
    is_1904: bool,
    options: ReadOptions,
}

/// Reads XLSX workbooks from any `Read + Seek` source, one row at a time.
///
/// Opening a reader parses the package structure, the sheet list and the cell formats. Worksheet
/// rows are then streamed: memory use is bounded by the shared string table plus the widest row,
/// whatever the number of rows.
///
/// ```no_run
/// use hypaxel::{Value, XlsxReader};
///
/// let mut reader = XlsxReader::open("orders.xlsx")?;
/// println!("sheets: {:?}", reader.sheet_names().collect::<Vec<_>>());
///
/// let mut rows = reader.worksheet_at(0)?;
/// while let Some(row) = rows.next_row()? {
///     match row.get(2) {
///         Value::Number(n) => println!("row {}: {n}", row.number()),
///         Value::DateTime(d) => println!("row {}: {d}", row.number()),
///         other => println!("row {}: {other}", row.number()),
///     }
/// }
/// # Ok::<(), hypaxel::Error>(())
/// ```
pub struct XlsxReader<R> {
    source: R,
    meta: Arc<Meta>,
    shared_strings: Option<Arc<SharedStrings>>,
}

impl<R> std::fmt::Debug for XlsxReader<R> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("XlsxReader").field("sheets", &self.meta.sheets).field("is_1904", &self.meta.is_1904).finish_non_exhaustive()
    }
}

impl XlsxReader<BufReader<File>> {
    /// Opens a workbook file.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        Self::new(BufReader::new(File::open(path)?))
    }

    /// Opens a workbook file with custom limits.
    pub fn open_with(path: impl AsRef<Path>, options: &ReadOptions) -> Result<Self> {
        Self::with_options(BufReader::new(File::open(path)?), options)
    }
}

impl<R: Read + Seek> XlsxReader<R> {
    /// Reads the workbook structure from `source`. Wrap files in a [`BufReader`].
    pub fn new(source: R) -> Result<Self> {
        Self::with_options(source, &ReadOptions::default())
    }

    pub fn with_options(mut source: R, options: &ReadOptions) -> Result<Self> {
        let dir = Directory::read(&mut source)?;
        let mut package = Package { dir: &dir, source: &mut source };

        let workbook_part = match package.relationships("_rels/.rels")? {
            Some(rels) => rels.into_iter().find(|r| r.is("officeDocument")).map(|r| r.target),
            None => None,
        }
        .unwrap_or_else(|| "xl/workbook.xml".to_string());
        if dir.find(&workbook_part).is_none() {
            return Err(Error::InvalidFile("the package has no workbook part; it may be a Word or PowerPoint file".into()));
        }

        let rels = package.relationships(&rels_path(&workbook_part))?.unwrap_or_default();
        let (is_1904, listed) = package.workbook(&workbook_part)?;

        let sheets = listed
            .into_iter()
            .map(|(name, visibility, rel_id)| {
                let rel = rels.iter().find(|r| Some(&r.id) == rel_id.as_ref());
                let kind = match rel {
                    Some(r) if r.is("worksheet") => SheetKind::Worksheet,
                    Some(r) if r.is("chartsheet") => SheetKind::Chartsheet,
                    Some(_) => SheetKind::Other,
                    None => SheetKind::Worksheet,
                };
                SheetInfo { name, visibility, kind, part: rel.map(|r| r.target.clone()) }
            })
            .collect();

        let styles = match rels.iter().find(|r| r.is("styles")) {
            Some(r) if dir.find(&r.target).is_some() => {
                let mut xml = package.xml(&r.target)?;
                Styles::parse(&mut xml)?
            }
            _ => Styles::default(),
        };
        let shared_strings_part = rels.iter().find(|r| r.is("sharedStrings")).map(|r| r.target.clone()).filter(|p| dir.find(p).is_some());

        let meta = Meta { dir, sheets, styles: Arc::new(styles), shared_strings_part, is_1904, options: options.clone() };
        Ok(XlsxReader { source, meta: Arc::new(meta), shared_strings: None })
    }

    /// The sheets in workbook order, including hidden sheets and chart sheets.
    pub fn sheets(&self) -> &[SheetInfo] {
        &self.meta.sheets
    }

    /// The sheet names in workbook order.
    pub fn sheet_names(&self) -> impl Iterator<Item = &str> {
        self.meta.sheets.iter().map(|s| s.name.as_str())
    }

    /// True if the workbook uses the 1904 date system.
    pub fn is_1904(&self) -> bool {
        self.meta.is_1904
    }

    fn sheet_index(&self, name: &str) -> Result<usize> {
        let sheets = &self.meta.sheets;
        sheets
            .iter()
            .position(|s| s.name == name)
            .or_else(|| sheets.iter().position(|s| s.name.to_lowercase() == name.to_lowercase()))
            .ok_or_else(|| Error::SheetNotFound(format!("{name:?}")))
    }

    /// Loads the shared string table if it has not been loaded yet. Called by the worksheet
    /// methods; call it before [`with_source`](Self::with_source) to share the table.
    pub fn load_shared_strings(&mut self) -> Result<()> {
        if self.shared_strings.is_some() {
            return Ok(());
        }
        let table = match &self.meta.shared_strings_part {
            Some(part) => {
                let mut package = Package { dir: &self.meta.dir, source: &mut self.source };
                let mut xml = package.xml(part)?;
                SharedStrings::parse(&mut xml, self.meta.options.max_shared_strings_bytes)?
            }
            None => SharedStrings::default(),
        };
        self.shared_strings = Some(Arc::new(table));
        Ok(())
    }

    /// Heap memory held by the shared string table, in bytes (0 before it is loaded).
    pub fn shared_strings_memory(&self) -> usize {
        self.shared_strings.as_ref().map_or(0, |s| s.memory())
    }

    fn check_index(&self, index: usize) -> Result<()> {
        if index >= self.meta.sheets.len() {
            return Err(Error::SheetNotFound(format!("at index {index}")));
        }
        Ok(())
    }

    /// Streams the rows of the sheet with this name (matched exactly, then ignoring case).
    pub fn worksheet(&mut self, name: &str) -> Result<Rows<&mut R>> {
        let index = self.sheet_index(name)?;
        self.worksheet_at(index)
    }

    /// Streams the rows of the sheet at a 0-based position in [`sheets`](Self::sheets).
    pub fn worksheet_at(&mut self, index: usize) -> Result<Rows<&mut R>> {
        self.check_index(index)?;
        self.load_shared_strings()?;
        let sst = self.shared_strings.clone().unwrap_or_default();
        open_rows(&self.meta, sst, index, &mut self.source)
    }

    /// Like [`worksheet`](Self::worksheet), but the rows own the source.
    pub fn into_worksheet(self, name: &str) -> Result<Rows<R>> {
        let index = self.sheet_index(name)?;
        self.into_worksheet_at(index)
    }

    /// Like [`worksheet_at`](Self::worksheet_at), but the rows own the source.
    pub fn into_worksheet_at(mut self, index: usize) -> Result<Rows<R>> {
        self.check_index(index)?;
        self.load_shared_strings()?;
        let sst = self.shared_strings.clone().unwrap_or_default();
        let meta = self.meta.clone();
        open_rows(&meta, sst, index, self.source)
    }

    /// The Excel tables of the sheet with this name (matched exactly, then ignoring case).
    pub fn tables(&mut self, name: &str) -> Result<Vec<Table>> {
        let index = self.sheet_index(name)?;
        self.tables_at(index)
    }

    /// The Excel tables of the sheet at a 0-based position in [`sheets`](Self::sheets), as the
    /// [`Table`] values that [`Sheet::add_table`](crate::Sheet::add_table) accepts.
    ///
    /// Tables live in their own parts, which are read on each call; reading rows does not touch
    /// them. They are returned from top to bottom, then left to right, whatever order the file
    /// lists them in. A table style that is not one of Excel's built-in styles is reported as
    /// [`TableStyle::None`](crate::TableStyle::None).
    pub fn tables_at(&mut self, index: usize) -> Result<Vec<Table>> {
        self.check_index(index)?;
        let Some(part) = self.meta.sheets[index].part.as_deref() else {
            return Ok(Vec::new());
        };
        let mut package = Package { dir: &self.meta.dir, source: &mut self.source };
        let rels = package.relationships(&rels_path(part))?.unwrap_or_default();
        let mut tables = Vec::new();
        for rel in rels.iter().filter(|r| r.is("table")) {
            if tables.len() >= MAX_TABLES {
                return Err(Error::LimitExceeded(format!("{part} has more than {MAX_TABLES} tables")));
            }
            let mut xml = package.xml(&rel.target)?;
            tables.push(table::parse(&mut xml)?);
        }
        tables.sort_by_key(|t| (t.first_row, t.first_column));
        Ok(tables)
    }

    /// A reader for another handle to the same workbook (e.g. a second `File`), sharing the
    /// parsed structure and the shared string table. Use it to read several sheets at once, for
    /// example from different threads.
    pub fn with_source<S: Read + Seek>(&mut self, source: S) -> Result<XlsxReader<S>> {
        self.load_shared_strings()?;
        Ok(XlsxReader { source, meta: self.meta.clone(), shared_strings: self.shared_strings.clone() })
    }
}

fn open_rows<S: Read + Seek>(meta: &Meta, sst: Arc<SharedStrings>, index: usize, source: S) -> Result<Rows<S>> {
    let info = &meta.sheets[index];
    if info.kind == SheetKind::Chartsheet {
        return Err(Error::Unsupported(format!("{:?} is a chart sheet and has no cells", info.name)));
    }
    let part = info.part.as_deref().ok_or_else(|| Error::Malformed {
        part: "xl/workbook.xml".into(),
        message: format!("sheet {:?} has no relationship to a worksheet part", info.name),
    })?;
    let entry = meta.dir.find(part).ok_or_else(|| Error::Malformed {
        part: part.to_string(),
        message: format!("the worksheet part of sheet {:?} is missing", info.name),
    })?;
    let xml = XmlReader::new(Directory::open(entry, source)?, part);
    Ok(Rows::new(xml, sst, meta.styles.clone(), meta.is_1904, meta.options.include_empty_rows))
}

struct Relationship {
    id: String,
    kind: String,
    target: String,
}

impl Relationship {
    /// Matches the last segment of the relationship type, so that both Transitional and Strict
    /// Office Open XML namespaces are accepted.
    fn is(&self, kind: &str) -> bool {
        self.kind.rsplit('/').next() == Some(kind)
    }
}

struct Package<'a, R> {
    dir: &'a Directory,
    source: &'a mut R,
}

impl<R: Read + Seek> Package<'_, R> {
    fn xml(&mut self, part: &str) -> Result<XmlReader<EntryReader<&mut R>>> {
        let entry = self.dir.find(part).ok_or_else(|| Error::Malformed { part: part.into(), message: "the part is missing".into() })?;
        Ok(XmlReader::new(Directory::open(entry, &mut *self.source)?, part))
    }

    fn relationships(&mut self, part: &str) -> Result<Option<Vec<Relationship>>> {
        if self.dir.find(part).is_none() {
            return Ok(None);
        }
        let base = source_dir(part);
        let mut xml = self.xml(part)?;
        let mut rels = Vec::new();
        loop {
            let tag = match xml.next()? {
                Event::Start(t) | Event::Empty(t) if t.name == b"Relationship" => t,
                Event::Eof => break,
                _ => continue,
            };
            let attr = |name: &[u8]| {
                let mut s = String::new();
                tag.attr(name).and_then(|raw| decode_into(&mut s, raw, true, false).ok()).map(|_| s)
            };
            if attr(b"TargetMode").is_some_and(|m| m.eq_ignore_ascii_case("External")) {
                continue;
            }
            if let (Some(id), Some(kind), Some(target)) = (attr(b"Id"), attr(b"Type"), attr(b"Target")) {
                rels.push(Relationship { id, kind, target: resolve(&base, &target) });
            }
        }
        Ok(Some(rels))
    }

    /// Reads the date system and the `(name, visibility, relationship id)` of every sheet.
    #[allow(clippy::type_complexity)]
    fn workbook(&mut self, part: &str) -> Result<(bool, Vec<(String, Visibility, Option<String>)>)> {
        let mut xml = self.xml(part)?;
        let mut is_1904 = false;
        let mut sheets = Vec::new();
        let mut root = true;
        loop {
            let tag = match xml.next()? {
                Event::Start(t) | Event::Empty(t) => t,
                Event::Eof => break,
                _ => continue,
            };
            if std::mem::take(&mut root) && tag.name != b"workbook" {
                return Err(Error::InvalidFile("the package has no workbook part; it may be a Word or PowerPoint file".into()));
            }
            let decoded = |name: &[u8]| {
                let mut s = String::new();
                tag.attr(name).and_then(|raw| decode_into(&mut s, raw, true, false).ok()).map(|_| s)
            };
            match tag.name {
                b"workbookPr" => is_1904 = matches!(tag.attr(b"date1904"), Some(b"1" | b"true")),
                b"sheet" => {
                    let Some(name) = decoded(b"name") else {
                        return Err(Error::Malformed { part: part.into(), message: "a sheet has no name".into() });
                    };
                    let visibility = match tag.attr(b"state") {
                        Some(b"hidden") => Visibility::Hidden,
                        Some(b"veryHidden") => Visibility::VeryHidden,
                        _ => Visibility::Visible,
                    };
                    sheets.push((name, visibility, decoded(b"id")));
                }
                _ => {}
            }
        }
        Ok((is_1904, sheets))
    }
}

/// The directory of a part, e.g. `xl/` for `xl/_rels/workbook.xml.rels` (relationship targets are
/// relative to the source part, which lives one level above `_rels/`).
fn source_dir(rels_part: &str) -> String {
    let dir = rels_part.rsplit_once('/').map_or("", |(d, _)| d);
    let dir = dir.strip_suffix("_rels").unwrap_or(dir);
    dir.to_string()
}

fn rels_path(part: &str) -> String {
    match part.rsplit_once('/') {
        Some((dir, file)) => format!("{dir}/_rels/{file}.rels"),
        None => format!("_rels/{part}.rels"),
    }
}

/// Resolves a relationship target against the source part's directory.
fn resolve(base: &str, target: &str) -> String {
    let joined = match target.strip_prefix('/') {
        Some(absolute) => absolute.to_string(),
        None => format!("{base}{target}"),
    };
    let mut parts: Vec<&str> = Vec::new();
    for segment in joined.split(['/', '\\']) {
        match segment {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            s => parts.push(s),
        }
    }
    parts.join("/")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_relationship_targets() {
        assert_eq!(source_dir("_rels/.rels"), "");
        assert_eq!(source_dir("xl/_rels/workbook.xml.rels"), "xl/");
        assert_eq!(rels_path("xl/workbook.xml"), "xl/_rels/workbook.xml.rels");
        assert_eq!(resolve("xl/", "worksheets/sheet1.xml"), "xl/worksheets/sheet1.xml");
        assert_eq!(resolve("xl/", "/xl/worksheets/sheet1.xml"), "xl/worksheets/sheet1.xml");
        assert_eq!(resolve("xl/", "../customXml/item1.xml"), "customXml/item1.xml");
        assert_eq!(resolve("", "xl/workbook.xml"), "xl/workbook.xml");
    }
}
