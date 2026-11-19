package hypaxel

import "errors"

// Errors returned by a Writer. Returned errors wrap one of these values with details such as the
// row and column, so test them with errors.Is.
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
	// ErrUnsupported reports a value that cannot be written as a cell or row.
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
)
