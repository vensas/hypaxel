use std::fmt;

/// Errors produced while writing a workbook.
///
/// Cell-level problems (text too long, too many columns, …) do not interrupt the fast cell-writing
/// methods. They are recorded and returned by the next fallible call (`end_row`, `add_row`,
/// `finish`, …), so chained cell writes stay cheap and branch-free for the caller.
#[derive(Debug)]
#[non_exhaustive]
pub enum Error {
    /// Writing to the destination failed.
    Io(std::io::Error),
    /// Worksheet names must be 1–31 characters, without `: \ / ? * [ ]` and not starting or ending with `'`.
    InvalidSheetName(String),
    /// Worksheet names are unique (case-insensitive).
    DuplicateSheetName(String),
    /// Rows must be written in ascending order.
    RowOrder { row: u32, previous: u32 },
    /// Row numbers are limited to 1..=1,048,576.
    RowOutOfRange(u64),
    /// A row cannot have more than 16,384 columns.
    TooManyColumns { row: u32 },
    /// A cell's text exceeds Excel's limit of 32,767 characters.
    TextTooLong { row: u32, column: u32, len: usize },
    /// The style id belongs to a different writer.
    InvalidStyle(u32),
    /// An argument is outside its allowed range.
    InvalidArgument(&'static str),
    /// A workbook needs at least one visible worksheet.
    NoVisibleWorksheet,
    /// A value that cannot be written as a cell (e.g. a nested struct in serde mapping).
    Unsupported(String),
    /// Custom error raised by a `Serialize` implementation.
    Custom(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Io(e) => write!(f, "I/O error: {e}"),
            Error::InvalidSheetName(n) => write!(f, "invalid worksheet name {n:?}"),
            Error::DuplicateSheetName(n) => write!(f, "a worksheet named {n:?} already exists"),
            Error::RowOrder { row, previous } => write!(f, "rows must ascend: row {row} follows row {previous}"),
            Error::RowOutOfRange(r) => write!(f, "row {r} is outside 1..=1048576"),
            Error::TooManyColumns { row } => write!(f, "row {row} exceeds 16384 columns"),
            Error::TextTooLong { row, column, len } => {
                write!(f, "cell at row {row}, column {column} has {len} characters; the limit is 32767")
            }
            Error::InvalidStyle(i) => write!(f, "style id {i} does not belong to this writer"),
            Error::InvalidArgument(m) => f.write_str(m),
            Error::NoVisibleWorksheet => f.write_str("a workbook needs at least one visible worksheet"),
            Error::Unsupported(m) => write!(f, "unsupported value: {m}"),
            Error::Custom(m) => f.write_str(m),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e)
    }
}

impl From<Error> for std::io::Error {
    fn from(e: Error) -> Self {
        match e {
            Error::Io(e) => e,
            other => std::io::Error::other(other),
        }
    }
}

/// Result alias for this crate.
pub type Result<T, E = Error> = std::result::Result<T, E>;
