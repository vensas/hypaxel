package hypaxel

import "errors"

// Errors returned by a Writer and a Reader. Returned errors wrap one of these values with details
// such as the row and column, so test them with errors.Is.
var (
	// ErrInvalidSheetName reports a worksheet name that is empty, longer than 31 characters,
	// contains one of : \ / ? * [ ], starts or ends with an apostrophe, or is "History".
	ErrInvalidSheetName = errors.New("hypaxel: invalid worksheet name")
	// ErrDuplicateSheetName reports a worksheet name that is already in use (case-insensitive).
	ErrDuplicateSheetName = errors.New("hypaxel: duplicate worksheet name")
	// ErrRowOrder reports a row index that does not come after the rows already written.
	ErrRowOrder = errors.New("hypaxel: rows must be written in ascending order")
	// ErrRowOutOfRange reports a row outside 1..1,048,576.
	ErrRowOutOfRange = errors.New("hypaxel: row is outside 1..1048576")
	// ErrTooManyColumns reports a row with more than 16,384 columns.
	ErrTooManyColumns = errors.New("hypaxel: row exceeds 16384 columns")
	// ErrTextTooLong reports cell text longer than 32,767 UTF-16 code units.
	ErrTextTooLong = errors.New("hypaxel: cell text exceeds 32767 characters")
	// ErrInvalidStyle reports a StyleID that was not returned by this Writer's AddStyle.
	ErrInvalidStyle = errors.New("hypaxel: style does not belong to this writer")
	// ErrInvalidArgument reports an argument outside its allowed range.
	ErrInvalidArgument = errors.New("hypaxel: invalid argument")
	// ErrNoVisibleSheet reports a workbook whose worksheets are all hidden.
	ErrNoVisibleSheet = errors.New("hypaxel: a workbook needs at least one visible worksheet")
	// ErrUnsupported reports a value that cannot be written as a cell or row, or a workbook
	// feature the Reader cannot read, such as a chart sheet's cells or a UTF-16 part.
	ErrUnsupported = errors.New("hypaxel: unsupported value")
	// ErrSheetClosed reports a write to a worksheet that is no longer the current one.
	ErrSheetClosed = errors.New("hypaxel: worksheet is closed")
	// ErrInvalidTableName reports a table name that does not start with a letter, '_' or '\',
	// contains characters other than letters, digits, '_', '.' and '\', is longer than 255
	// characters, or looks like a cell reference such as "A1" or "R1C1".
	ErrInvalidTableName = errors.New("hypaxel: invalid table name")
	// ErrDuplicateTableName reports a table name already used in the workbook (case-insensitive).
	ErrDuplicateTableName = errors.New("hypaxel: duplicate table name")
	// ErrOverlappingRange reports a table that overlaps another table, merged cells or the
	// worksheet auto filter, or merged cells or an auto filter that overlap a table.
	ErrOverlappingRange = errors.New("hypaxel: overlapping range")
	// ErrClosed reports use of a Writer after Close.
	ErrClosed = errors.New("hypaxel: writer is closed")

	// ErrInvalidFile reports input that is not an XLSX workbook: not a ZIP archive, a legacy
	// .xls or password-protected (encrypted) workbook, or another Office document such as .docx.
	ErrInvalidFile = errors.New("hypaxel: not an XLSX workbook")
	// ErrSheetNotFound reports a sheet name or index that is not in the workbook.
	ErrSheetNotFound = errors.New("hypaxel: sheet not found")
	// ErrMalformed reports a damaged workbook part: invalid XML, a bad cell value, or a ZIP
	// entry that fails its size or CRC-32 check.
	ErrMalformed = errors.New("hypaxel: malformed workbook")
	// ErrRowsClosed reports a [Rows.Layout] call on a Rows that was closed before its layout was
	// read.
	ErrRowsClosed = errors.New("hypaxel: rows are closed")
	// ErrLimitExceeded reports a workbook that exceeds a safety limit of the Reader, such as
	// [WithMaxSharedStringsBytes] or the 16 MiB bound on a single XML token.
	ErrLimitExceeded = errors.New("hypaxel: read limit exceeded")
)

// ReadError is a problem found in one part of a workbook. It wraps [ErrMalformed],
// [ErrLimitExceeded] or [ErrUnsupported], and the ZIP or decompression error that caused it, if
// any.
type ReadError struct {
	// Part is the package part, such as "xl/worksheets/sheet1.xml".
	Part    string
	Message string
	kind    error
	cause   error
}

func (e *ReadError) Error() string {
	return "hypaxel: " + e.Part + ": " + e.Message
}

func (e *ReadError) Unwrap() []error {
	if e.cause != nil {
		return []error{e.kind, e.cause}
	}
	return []error{e.kind}
}
