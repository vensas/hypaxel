//! Excel tables (`ListObject`s): a named, styled range with a header row and filter buttons.

use std::borrow::Cow;

use crate::engine::{MAX_COLUMNS, MAX_ROWS, push_range};
use crate::error::{Error, Result};
use crate::xml;

/// Built-in table styles, as shown in Excel's table style gallery.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum TableStyle {
    /// No table formatting (filter buttons and structured references still work).
    None,
    /// `TableStyleLight1` ..= `TableStyleLight21`.
    Light(u8),
    /// `TableStyleMedium1` ..= `TableStyleMedium28`.
    Medium(u8),
    /// `TableStyleDark1` ..= `TableStyleDark11`.
    Dark(u8),
}

impl Default for TableStyle {
    /// `TableStyleMedium2`, Excel's default for new tables.
    fn default() -> Self {
        TableStyle::Medium(2)
    }
}

impl TableStyle {
    /// The style's name in the file, e.g. `TableStyleMedium2`; `None` for [`TableStyle::None`].
    pub fn name(self) -> Option<String> {
        match self {
            TableStyle::None => None,
            TableStyle::Light(n) => Some(format!("TableStyleLight{n}")),
            TableStyle::Medium(n) => Some(format!("TableStyleMedium{n}")),
            TableStyle::Dark(n) => Some(format!("TableStyleDark{n}")),
        }
    }

    fn is_valid(self) -> bool {
        match self {
            TableStyle::None => true,
            TableStyle::Light(n) => (1..=21).contains(&n),
            TableStyle::Medium(n) => (1..=28).contains(&n),
            TableStyle::Dark(n) => (1..=11).contains(&n),
        }
    }
}

/// An Excel table over a range of the current worksheet, added with
/// [`Sheet::add_table`](crate::Sheet::add_table).
///
/// With a header row (the default), `columns` must list the header texts exactly as written in the
/// range's first row: Excel identifies table columns by those names and repairs the file otherwise.
///
/// ```
/// use hypaxel::{Table, TableStyle, XlsxWriter};
///
/// let mut writer = XlsxWriter::new(Vec::new());
/// let mut sheet = writer.add_worksheet("Orders")?;
/// sheet.add_row(("Product", "Units"))?;
/// sheet.add_row(("Widget", 3))?;
/// sheet.add_row(("Gadget", 5))?;
/// let last = sheet.row();
/// sheet.add_table(&Table::new(1, 1, last, 2).name("Orders").columns(["Product", "Units"]).style(TableStyle::Medium(9)))?;
/// writer.finish()?;
/// # Ok::<(), hypaxel::Error>(())
/// ```
#[derive(Debug, Clone, PartialEq)]
pub struct Table {
    pub first_row: u32,
    pub first_column: u32,
    pub last_row: u32,
    pub last_column: u32,
    /// Workbook-unique name used in structured references; `None` gives `Table1`, `Table2`, ….
    pub name: Option<String>,
    /// Column names; required with a header row, otherwise `Column1`, `Column2`, … by default.
    pub columns: Vec<Cow<'static, str>>,
    pub style: TableStyle,
    /// The first row of the range holds the column names. Default: true.
    pub header_row: bool,
    /// Filter buttons in the header row. Default: true.
    pub autofilter: bool,
    /// Banded rows. Default: true.
    pub row_stripes: bool,
    /// Banded columns. Default: false.
    pub column_stripes: bool,
    /// Highlight the first column. Default: false.
    pub first_column_highlight: bool,
    /// Highlight the last column. Default: false.
    pub last_column_highlight: bool,
}

impl Table {
    /// A table over a range (1-based, inclusive) whose first row is the header.
    pub fn new(first_row: u32, first_column: u32, last_row: u32, last_column: u32) -> Self {
        Table {
            first_row,
            first_column,
            last_row,
            last_column,
            name: None,
            columns: Vec::new(),
            style: TableStyle::default(),
            header_row: true,
            autofilter: true,
            row_stripes: true,
            column_stripes: false,
            first_column_highlight: false,
            last_column_highlight: false,
        }
    }

    pub fn name(mut self, name: impl Into<String>) -> Self {
        self.name = Some(name.into());
        self
    }

    pub fn columns<I, S>(mut self, names: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<Cow<'static, str>>,
    {
        self.columns = names.into_iter().map(Into::into).collect();
        self
    }

    pub fn style(mut self, style: TableStyle) -> Self {
        self.style = style;
        self
    }

    pub fn header_row(mut self, enabled: bool) -> Self {
        self.header_row = enabled;
        self
    }

    pub fn autofilter(mut self, enabled: bool) -> Self {
        self.autofilter = enabled;
        self
    }

    pub fn row_stripes(mut self, enabled: bool) -> Self {
        self.row_stripes = enabled;
        self
    }

    pub fn column_stripes(mut self, enabled: bool) -> Self {
        self.column_stripes = enabled;
        self
    }

    pub fn highlight_first_column(mut self, enabled: bool) -> Self {
        self.first_column_highlight = enabled;
        self
    }

    pub fn highlight_last_column(mut self, enabled: bool) -> Self {
        self.last_column_highlight = enabled;
        self
    }

    pub(crate) fn range(&self) -> (u32, u32, u32, u32) {
        (self.first_row, self.first_column, self.last_row, self.last_column)
    }
}

/// A validated table, kept until its worksheet completes.
pub(crate) struct TableMeta {
    pub id: u32,
    pub name: String,
    pub range: (u32, u32, u32, u32),
    columns: Vec<String>,
    style: TableStyle,
    header_row: bool,
    autofilter: bool,
    row_stripes: bool,
    column_stripes: bool,
    first_column: bool,
    last_column: bool,
}

impl TableMeta {
    /// Validates `table`; `taken` reports whether a name is already used in the workbook.
    pub fn new(table: &Table, id: u32, taken: impl Fn(&str) -> bool) -> Result<Self> {
        let (r1, c1, r2, c2) = table.range();
        let rows_ok = (1..=MAX_ROWS).contains(&r1) && (r1..=MAX_ROWS).contains(&r2);
        let cols_ok = (1..=MAX_COLUMNS).contains(&c1) && (c1..=MAX_COLUMNS).contains(&c2);
        if !rows_ok || !cols_ok {
            return Err(Error::InvalidArgument("invalid cell range"));
        }
        if table.header_row && r2 == r1 {
            return Err(Error::InvalidArgument("a table with a header row needs at least one data row"));
        }
        if !table.style.is_valid() {
            return Err(Error::InvalidArgument("table style number out of range (Light 1–21, Medium 1–28, Dark 1–11)"));
        }

        let name = match &table.name {
            Some(name) => {
                validate_table_name(name)?;
                name.clone()
            }
            None => format!("Table{id}"),
        };
        if taken(&name) {
            return Err(Error::DuplicateTableName(name));
        }

        let width = (c2 - c1 + 1) as usize;
        let columns: Vec<String> = if table.columns.is_empty() {
            if table.header_row {
                return Err(Error::InvalidArgument("a table with a header row needs its column names (Table::columns)"));
            }
            (1..=width).map(|i| format!("Column{i}")).collect()
        } else {
            table.columns.iter().map(|c| c.to_string()).collect()
        };
        if columns.len() != width {
            return Err(Error::InvalidArgument("the number of table column names must equal the width of the range"));
        }
        for (i, column) in columns.iter().enumerate() {
            if column.is_empty() || column.encode_utf16().count() > 255 {
                return Err(Error::InvalidArgument("table column names must be 1–255 characters"));
            }
            let lower = column.to_lowercase();
            if columns[..i].iter().any(|c| c.to_lowercase() == lower) {
                return Err(Error::InvalidArgument("table column names must be unique (case-insensitive)"));
            }
        }

        Ok(TableMeta {
            id,
            name,
            range: (r1, c1, r2, c2),
            columns,
            style: table.style,
            header_row: table.header_row,
            autofilter: table.autofilter && table.header_row,
            row_stripes: table.row_stripes,
            column_stripes: table.column_stripes,
            first_column: table.first_column_highlight,
            last_column: table.last_column_highlight,
        })
    }

    pub fn write_xml(&self, x: &mut Vec<u8>) {
        let (r1, c1, r2, c2) = self.range;
        x.extend_from_slice(xml::DECLARATION);
        x.extend_from_slice(b"<table xmlns=\"");
        x.extend_from_slice(xml::NS_MAIN);
        x.extend_from_slice(b"\" id=\"");
        xml::push_int(x, self.id);
        x.extend_from_slice(b"\" name=\"");
        xml::escape_into(x, &self.name);
        x.extend_from_slice(b"\" displayName=\"");
        xml::escape_into(x, &self.name);
        x.extend_from_slice(b"\" ref=\"");
        push_range(x, r1, c1, r2, c2);
        x.push(b'"');
        if !self.header_row {
            x.extend_from_slice(b" headerRowCount=\"0\"");
        }
        x.extend_from_slice(b" totalsRowShown=\"0\">");
        if self.autofilter {
            x.extend_from_slice(b"<autoFilter ref=\"");
            push_range(x, r1, c1, r2, c2);
            x.extend_from_slice(b"\"/>");
        }
        x.extend_from_slice(b"<tableColumns count=\"");
        xml::push_int(x, self.columns.len());
        x.extend_from_slice(b"\">");
        for (i, column) in self.columns.iter().enumerate() {
            x.extend_from_slice(b"<tableColumn id=\"");
            xml::push_int(x, i + 1);
            x.extend_from_slice(b"\" name=\"");
            escape_attribute(x, column);
            x.extend_from_slice(b"\"/>");
        }
        x.extend_from_slice(b"</tableColumns><tableStyleInfo");
        if let Some(name) = self.style.name() {
            x.extend_from_slice(b" name=\"");
            x.extend_from_slice(name.as_bytes());
            x.push(b'"');
        }
        for (attr, on) in [
            (&b" showFirstColumn=\""[..], self.first_column),
            (b" showLastColumn=\"", self.last_column),
            (b" showRowStripes=\"", self.row_stripes),
            (b" showColumnStripes=\"", self.column_stripes),
        ] {
            x.extend_from_slice(attr);
            x.push(if on { b'1' } else { b'0' });
            x.push(b'"');
        }
        x.extend_from_slice(b"/></table>");
    }
}

/// Like [`xml::escape_into`], but writes line breaks and tabs (which attribute normalization would
/// turn into spaces) as `_xHHHH_` escapes, as Excel does for table column names.
fn escape_attribute(x: &mut Vec<u8>, text: &str) {
    let mut start = 0;
    for (i, b) in text.bytes().enumerate() {
        let reference: &[u8] = match b {
            b'\n' => b"_x000A_",
            b'\r' => b"_x000D_",
            b'\t' => b"_x0009_",
            _ => continue,
        };
        xml::escape_into(x, &text[start..i]);
        x.extend_from_slice(reference);
        start = i + 1;
    }
    xml::escape_into(x, &text[start..]);
}

/// Table names follow Excel's defined-name rules: they start with a letter, `_` or `\`, contain only
/// letters, digits, `_` and `.`, are at most 255 characters and cannot look like a cell reference.
fn validate_table_name(name: &str) -> Result<()> {
    let invalid = || Err(Error::InvalidTableName(name.to_owned()));
    let mut chars = name.chars();
    let Some(first) = chars.next() else { return invalid() };
    if !(first.is_alphabetic() || first == '_' || first == '\\') {
        return invalid();
    }
    if !chars.all(|c| c.is_alphanumeric() || c == '_' || c == '.' || c == '\\') {
        return invalid();
    }
    if name.encode_utf16().count() > 255 || looks_like_reference(name) {
        return invalid();
    }
    Ok(())
}

/// `A1`-style (`XFD1048576`), `R1C1`-style (`R`, `C`, `R2`, `RC3`, …) references are not valid names.
fn looks_like_reference(name: &str) -> bool {
    let upper = name.to_ascii_uppercase();
    let b = upper.as_bytes();
    let letters = b.iter().take_while(|c| c.is_ascii_uppercase()).count();
    let digits = &b[letters..];
    if (1..=3).contains(&letters) && !digits.is_empty() && digits.iter().all(u8::is_ascii_digit) {
        let column = b[..letters].iter().fold(0u32, |acc, &c| acc * 26 + (c - b'A' + 1) as u32);
        let row: u64 = std::str::from_utf8(digits).unwrap().parse().unwrap_or(u64::MAX);
        if column <= MAX_COLUMNS && (1..=MAX_ROWS as u64).contains(&row) {
            return true;
        }
    }
    let r1c1 = |s: &[u8]| -> bool {
        let mut i = 0;
        if s.get(i) == Some(&b'R') {
            i += 1;
            while s.get(i).is_some_and(u8::is_ascii_digit) {
                i += 1;
            }
        }
        if s.get(i) == Some(&b'C') {
            i += 1;
            while s.get(i).is_some_and(u8::is_ascii_digit) {
                i += 1;
            }
        }
        i > 0 && i == s.len()
    };
    r1c1(b)
}

/// True when two inclusive ranges share at least one cell.
pub(crate) fn overlaps(a: (u32, u32, u32, u32), b: (u32, u32, u32, u32)) -> bool {
    a.0 <= b.2 && b.0 <= a.2 && a.1 <= b.3 && b.1 <= a.3
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn table_names() {
        for ok in ["Orders", "_t", "Sales.2024", "Résumé", "R1C1x", "XFE1", "ABCD1", "\\name"] {
            assert!(validate_table_name(ok).is_ok(), "{ok}");
        }
        for bad in ["", "1abc", "A1", "xfd1048576", "R", "C", "r1c1", "RC", "R12", "C3", "has space", "a-b", "a:b"] {
            assert!(validate_table_name(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn overlap() {
        assert!(overlaps((1, 1, 5, 5), (5, 5, 6, 6)));
        assert!(!overlaps((1, 1, 5, 5), (6, 1, 9, 5)));
        assert!(!overlaps((1, 1, 5, 5), (1, 6, 5, 9)));
    }
}
