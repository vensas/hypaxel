package hypaxel

import (
	"fmt"
	"math"
	"strconv"
	"time"
)

// Sheet is the worksheet currently being written. It is returned by [Writer.AddSheet] and
// becomes invalid when the next worksheet is added or the Writer is closed; writing to it
// after that fails the Writer with [ErrSheetClosed].
//
// The Write methods append the next cell of the current row and start a row when none is
// open. They take an optional style and return nothing: a problem such as over-long text is
// recorded and returned by the next call that returns an error ([Sheet.EndRow],
// [Sheet.WriteRow], [Writer.Close], ...).
type Sheet struct {
	w *Writer
}

// RowOptions configures a row started with [Sheet.BeginRow].
type RowOptions struct {
	// Index is the 1-based row number, which must be greater than any row written so far.
	// Zero means the next row.
	Index int
	// Height in points, up to 409; zero keeps the default height.
	Height float64
	// Style applies to the cells of the row that are not written.
	Style StyleID
}

func styleOf(style []StyleID) StyleID {
	if len(style) > 0 {
		return style[0]
	}
	return DefaultStyle
}

// usable reports whether the sheet can take writes, recording ErrSheetClosed when it is stale.
func (s *Sheet) usable() bool {
	w := s.w
	if w.err != nil {
		return false
	}
	if w.current != s {
		w.err = ErrSheetClosed
		return false
	}
	return true
}

// Row returns the current (or last written) 1-based row number, 0 before the first row.
func (s *Sheet) Row() int {
	return int(s.w.row)
}

// Column returns the 1-based column of the last written cell, 0 at the start of a row.
func (s *Sheet) Column() int {
	return int(s.w.column)
}

// BeginRow starts a row explicitly, to give it a height, a style or a row number further
// down. Cell writes start the next row implicitly, so it is not needed otherwise.
func (s *Sheet) BeginRow(opts RowOptions) error {
	if !s.usable() {
		return s.w.err
	}
	w := s.w
	row := opts.Index
	if row == 0 {
		row = int(w.row) + 1
	}
	switch {
	case row < 1 || row > MaxRows:
		return fmt.Errorf("%w: row %d", ErrRowOutOfRange, row)
	case uint32(row) <= w.row:
		return fmt.Errorf("%w: row %d follows row %d", ErrRowOrder, row, w.row)
	case !w.validStyle(opts.Style):
		return fmt.Errorf("%w: row style %d", ErrInvalidStyle, opts.Style)
	case !(opts.Height >= 0 && opts.Height <= 409):
		return fmt.Errorf("%w: row height must be within 0..409 points", ErrInvalidArgument)
	}
	if w.rowOpen {
		w.closeRow()
	}
	w.openRow(uint32(row), opts.Height, opts.Style)
	return w.err
}

func (w *Writer) openRow(row uint32, height float64, style StyleID) {
	w.row = row
	w.column = 0
	w.lastColumn = 0
	w.rowOpen = true
	w.rowRefLen = len(strconv.AppendUint(w.rowRef[:0], uint64(row), 10))

	x := append(w.xml, `<row r="`...)
	x = append(x, w.rowRef[:w.rowRefLen]...)
	x = append(x, '"')
	if height > 0 {
		x = append(x, ` ht="`...)
		x = appendFloat(x, height, 64)
		x = append(x, `" customHeight="1"`...)
	}
	if style != 0 {
		x = append(x, ` s="`...)
		x = appendUint(x, uint64(style))
		x = append(x, `" customFormat="1"`...)
	}
	w.xml = append(x, '>')
}

func (w *Writer) beginNextRow() bool {
	if w.rowOpen {
		w.closeRow()
	}
	if w.row >= MaxRows {
		w.fail(fmt.Errorf("%w: row %d", ErrRowOutOfRange, uint64(w.row)+1))
		return false
	}
	w.openRow(w.row+1, 0, DefaultStyle)
	return true
}

// nextCell returns the position the next cell write goes to, for error messages.
func (w *Writer) nextCell() (row, column uint32) {
	if w.rowOpen {
		return w.row, w.column + 1
	}
	return w.row + 1, 1
}

func (w *Writer) closeRow() {
	w.xml = append(w.xml, "</row>"...)
	w.rowOpen = false
	if len(w.xml) >= xmlBufferSize {
		w.compress()
	}
}

// EndRow ends the current row. Without an open row it leaves an empty row. It returns the
// first error recorded since the Writer was created, including errors from the destination.
func (s *Sheet) EndRow() error {
	if !s.usable() {
		return s.w.err
	}
	w := s.w
	if w.rowOpen {
		w.closeRow()
	} else if w.row < MaxRows {
		w.row++
	}
	return w.err
}

// SkipRows leaves count rows empty; the next row is written below them.
func (s *Sheet) SkipRows(count int) {
	if !s.usable() {
		return
	}
	w := s.w
	if count < 0 {
		w.fail(fmt.Errorf("%w: negative row count", ErrInvalidArgument))
		return
	}
	if w.rowOpen {
		w.closeRow()
	}
	w.row = uint32(min(int64(w.row)+int64(count), MaxRows))
}

// SkipCells leaves count cells empty.
func (s *Sheet) SkipCells(count int) {
	if !s.usable() {
		return
	}
	w := s.w
	if count < 0 {
		w.fail(fmt.Errorf("%w: negative cell count", ErrInvalidArgument))
		return
	}
	if !w.rowOpen && !w.beginNextRow() {
		return
	}
	column := int64(w.column) + int64(count)
	if column > MaxColumns {
		w.fail(fmt.Errorf("%w: row %d", ErrTooManyColumns, w.row))
		return
	}
	w.column = uint32(column)
}

func newRange(firstRow, firstColumn, lastRow, lastColumn int) (cellRange, error) {
	if firstRow < 1 || lastRow < firstRow || lastRow > MaxRows ||
		firstColumn < 1 || lastColumn < firstColumn || lastColumn > MaxColumns {
		return cellRange{}, fmt.Errorf("%w: cell range", ErrInvalidArgument)
	}
	return cellRange{uint32(firstRow), uint32(firstColumn), uint32(lastRow), uint32(lastColumn)}, nil
}

// MergeCells merges a range of cells (1-based, inclusive). The range may lie anywhere on the
// sheet, including rows not written yet.
func (s *Sheet) MergeCells(firstRow, firstColumn, lastRow, lastColumn int) error {
	if !s.usable() {
		return s.w.err
	}
	r, err := newRange(firstRow, firstColumn, lastRow, lastColumn)
	if err != nil {
		return err
	}
	if firstRow == lastRow && firstColumn == lastColumn {
		return fmt.Errorf("%w: a merged range must span more than one cell", ErrInvalidArgument)
	}
	s.w.merges = append(s.w.merges, r)
	return nil
}

// AutoFilter adds filter drop-downs to a range whose first row is the header (1-based,
// inclusive). A later call replaces the range.
func (s *Sheet) AutoFilter(firstRow, firstColumn, lastRow, lastColumn int) error {
	if !s.usable() {
		return s.w.err
	}
	r, err := newRange(firstRow, firstColumn, lastRow, lastColumn)
	if err != nil {
		return err
	}
	s.w.sheets[len(s.w.sheets)-1].autoFilter = &r
	return nil
}

// beginCell opens a <c element with reference and style attributes as needed. It returns
// false, having recorded an error, when the cell cannot be written; nothing is emitted then.
func (s *Sheet) beginCell(style StyleID) bool {
	w := s.w
	if w.err != nil || w.current != s {
		return s.usable()
	}
	if !w.rowOpen && !w.beginNextRow() {
		return false
	}
	column := w.column + 1
	if column > MaxColumns {
		w.fail(fmt.Errorf("%w: row %d", ErrTooManyColumns, w.row))
		return false
	}
	if uint32(style) >= uint32(len(w.styles.xfs)) {
		w.fail(fmt.Errorf("%w: style %d at row %d, column %d", ErrInvalidStyle, style, w.row, column))
		return false
	}
	w.column = column

	x := w.xml
	if column == w.lastColumn+1 && !w.cfg.cellReferences {
		// References are optional; readers infer consecutive cells. Saves about 20% of the XML.
		x = append(x, "<c"...)
	} else {
		x = append(x, `<c r="`...)
		x = appendColumnName(x, column)
		x = append(x, w.rowRef[:w.rowRefLen]...)
		x = append(x, '"')
	}
	w.lastColumn = column
	if style != 0 {
		x = append(x, ` s="`...)
		x = appendUint(x, uint64(style))
		x = append(x, '"')
	}
	w.xml = x
	return true
}

// WriteInt writes an integer cell. Excel stores numbers as float64, so precision is lost
// beyond ±2^53.
func (s *Sheet) WriteInt(v int64, style ...StyleID) {
	if s.beginCell(styleOf(style)) {
		w := s.w
		x := append(w.xml, "><v>"...)
		x = strconv.AppendInt(x, v, 10)
		w.xml = append(x, "</v></c>"...)
	}
}

// WriteUint writes an unsigned integer cell.
func (s *Sheet) WriteUint(v uint64, style ...StyleID) {
	if s.beginCell(styleOf(style)) {
		w := s.w
		x := append(w.xml, "><v>"...)
		x = strconv.AppendUint(x, v, 10)
		w.xml = append(x, "</v></c>"...)
	}
}

// WriteFloat writes a number cell. NaN and infinities become the #NUM! error value.
func (s *Sheet) WriteFloat(v float64, style ...StyleID) {
	s.writeFloat(v, 64, styleOf(style))
}

func (s *Sheet) writeFloat(v float64, bitSize int, style StyleID) {
	if !s.beginCell(style) {
		return
	}
	w := s.w
	if math.IsNaN(v) || math.IsInf(v, 0) {
		w.xml = append(w.xml, ` t="e"><v>#NUM!</v></c>`...)
		return
	}
	x := append(w.xml, "><v>"...)
	x = appendFloat(x, v, bitSize)
	w.xml = append(x, "</v></c>"...)
}

// WriteBool writes a boolean cell.
func (s *Sheet) WriteBool(v bool, style ...StyleID) {
	if s.beginCell(styleOf(style)) {
		if v {
			s.w.xml = append(s.w.xml, ` t="b"><v>1</v></c>`...)
		} else {
			s.w.xml = append(s.w.xml, ` t="b"><v>0</v></c>`...)
		}
	}
}

// WriteString writes a text cell. Invalid UTF-8 is replaced with U+FFFD.
func (s *Sheet) WriteString(v string, style ...StyleID) {
	s.writeString(v, styleOf(style))
}

func (s *Sheet) writeString(v string, style StyleID) {
	w := s.w
	// Excel counts UTF-16 code units; the UTF-8 byte length is an upper bound, so only long
	// strings pay for an exact count.
	if len(v) > MaxCellLength && s.usable() {
		if n := utf16Len(v); n > MaxCellLength {
			row, column := w.nextCell()
			w.fail(fmt.Errorf("%w: %d characters at row %d, column %d", ErrTextTooLong, n, row, column))
			return
		}
	}
	if !s.beginCell(style) {
		return
	}
	if w.sst != nil {
		index := w.sst.indexOf(v)
		x := append(w.xml, ` t="s"><v>`...)
		x = appendUint(x, uint64(index))
		w.xml = append(x, "</v></c>"...)
		return
	}
	x := append(w.xml, ` t="inlineStr"><is>`...)
	x = appendTextElement(x, v)
	w.xml = append(x, "</is></c>"...)
	if len(v) > 4096 && len(w.xml) >= xmlBufferSize {
		w.compress()
	}
}

// WriteFormula writes a formula; the leading "=" is optional. No result is stored: Excel
// computes it when the file is opened.
func (s *Sheet) WriteFormula(formula string, style ...StyleID) {
	if len(formula) > 0 && formula[0] == '=' {
		formula = formula[1:]
	}
	if s.beginCell(styleOf(style)) {
		w := s.w
		w.usesFormulas = true
		x := append(w.xml, "><f>"...)
		x = appendEscaped(x, formula)
		w.xml = append(x, "</f></c>"...)
	}
}

// WriteBlank writes an empty cell that carries a format such as a border. With the default
// style it only skips the cell.
func (s *Sheet) WriteBlank(style ...StyleID) {
	s.writeBlank(styleOf(style))
}

func (s *Sheet) writeBlank(style StyleID) {
	if style == DefaultStyle {
		s.SkipCells(1)
	} else if s.beginCell(style) {
		s.w.xml = append(s.w.xml, "/>"...)
	}
}

// WriteTime writes a date and time using the wall clock of t's location, because Excel has no
// time zones. A style without a number format gets the viewer's locale date-time format. The
// zero time is written as a blank cell and a time Excel cannot represent as ISO 8601 text.
func (s *Sheet) WriteTime(t time.Time, style ...StyleID) {
	s.writeTemporal(t, kindDateTime, styleOf(style))
}

// WriteDate writes the date part of t, dropping the time of day, with a date-only format.
func (s *Sheet) WriteDate(t time.Time, style ...StyleID) {
	s.writeTemporal(t, kindDate, styleOf(style))
}

const (
	secondsPerDay = 86_400
	nanosPerDay   = 86_400_000_000_000
	// Days from each date system's day 0 (1899-12-30 and 1904-01-01) to the Unix epoch.
	unixEpoch1900 = 25_569
	unixEpoch1904 = 24_107
)

func (s *Sheet) writeTemporal(t time.Time, kind valueKind, style StyleID) {
	if t.IsZero() {
		s.writeBlank(style)
		return
	}
	w := s.w
	_, offset := t.Zone()
	seconds := t.Unix() + int64(offset)
	days := seconds / secondsPerDay
	secondOfDay := seconds % secondsPerDay
	if secondOfDay < 0 {
		secondOfDay += secondsPerDay
		days--
	}
	var nanos uint64
	if kind != kindDate {
		nanos = uint64(secondOfDay)*1_000_000_000 + uint64(t.Nanosecond())
	}

	var day int64
	var ok bool
	if w.cfg.date1904 {
		day = days + unixEpoch1904
		ok = day >= 0 && day <= maxSerial-1462
	} else {
		day = days + unixEpoch1900
		if day < 61 {
			// Excel's 1900 system contains a fictitious 1900-02-29.
			day--
		}
		ok = day >= 1 && day <= maxSerial
	}
	if !ok {
		layout := "2006-01-02T15:04:05.999999999"
		if kind == kindDate {
			layout = time.DateOnly
		}
		s.writeString(string(t.AppendFormat(w.scratch[:0], layout)), style)
		return
	}
	if s.beginCell(StyleID(w.styles.valueVariant(uint32(style), kind))) {
		x := append(w.xml, "><v>"...)
		x = appendSerial(x, day, nanos)
		w.xml = append(x, "</v></c>"...)
	}
}

// WriteDuration writes a duration as a number of days. A style without a number format gets
// the [h]:mm:ss format.
func (s *Sheet) WriteDuration(d time.Duration, style ...StyleID) {
	days := float64(d/time.Second)/secondsPerDay + float64(d%time.Second)/nanosPerDay
	s.writeFloat(days, 64, StyleID(s.w.styles.valueVariant(uint32(styleOf(style)), kindDuration)))
}
