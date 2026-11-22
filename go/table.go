package hypaxel

import (
	"fmt"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// TableStyle is one of Excel's built-in table styles, or a custom style by name. The zero value
// is TableStyleMedium2, Excel's default for new tables. Styles compare equal with == when they
// are the same built-in style or custom styles with the same name.
type TableStyle struct {
	id     uint16
	custom string
}

// TableStyleNone applies no table formatting; filter buttons and structured references still
// work.
var TableStyleNone = TableStyle{id: tableNone << 8}

const (
	tableDefault = iota
	tableLight
	tableMedium
	tableDark
	tableNone
	tableCustom
)

var tableStyleFamilies = [...]struct {
	name  string
	count int
}{
	tableLight:  {"Light", 21},
	tableMedium: {"Medium", 28},
	tableDark:   {"Dark", 11},
}

// TableStyleLight returns TableStyleLight1 to TableStyleLight21.
func TableStyleLight(n int) TableStyle { return newTableStyle(tableLight, n) }

// TableStyleMedium returns TableStyleMedium1 to TableStyleMedium28.
func TableStyleMedium(n int) TableStyle { return newTableStyle(tableMedium, n) }

// TableStyleDark returns TableStyleDark1 to TableStyleDark11.
func TableStyleDark(n int) TableStyle { return newTableStyle(tableDark, n) }

// TableStyleCustom returns a style defined in the workbook's styles part (<tableStyles>), by
// name: 1-255 characters on one line, written as given. Excel falls back to no style when the
// workbook does not define it, so this is mainly useful for tables read from an existing
// workbook. [Sheet.AddTable] rejects an invalid name.
func TableStyleCustom(name string) TableStyle {
	return TableStyle{id: tableCustom << 8, custom: name}
}

// newTableStyle returns an invalid style, which [Sheet.AddTable] rejects, for n out of range.
// TableStyleMedium2 is always the zero value, so that equal styles compare equal.
func newTableStyle(family, n int) TableStyle {
	if n < 1 || n > tableStyleFamilies[family].count {
		n = 0
	}
	if family == tableMedium && n == 2 {
		return TableStyle{}
	}
	return TableStyle{id: uint16(family<<8 | n)}
}

// Custom returns the name of a custom style, and false for a built-in style or TableStyleNone.
func (t TableStyle) Custom() (string, bool) {
	return t.custom, t.id == tableCustom<<8
}

func (t TableStyle) valid() bool {
	family, n := int(t.id>>8), int(t.id&0xFF)
	switch family {
	case tableDefault, tableNone:
		return n == 0
	case tableLight, tableMedium, tableDark:
		return n >= 1 && n <= tableStyleFamilies[family].count
	case tableCustom:
		return n == 0 && validTableStyleName(t.custom)
	}
	return false
}

// validTableStyleName accepts 1-255 UTF-16 code units of valid UTF-8 without C0 control characters or
// U+FFFE/U+FFFF, which XML 1.0 cannot carry or which would not survive on one line.
func validTableStyleName(name string) bool {
	if name == "" || utf16Len(name) > 255 || !utf8.ValidString(name) {
		return false
	}
	for _, r := range name {
		if r < 0x20 || r == 0xFFFE || r == 0xFFFF {
			return false
		}
	}
	return true
}

// String returns the style name used in the file, such as "TableStyleMedium2" or a custom
// style's name, or "None".
func (t TableStyle) String() string {
	if name, ok := t.Custom(); ok {
		return name
	}
	if t.id == 0 {
		return "TableStyleMedium2"
	}
	if !t.valid() {
		return "TableStyle(" + strconv.Itoa(int(t.id)) + ")"
	}
	if t == TableStyleNone {
		return "None"
	}
	return "TableStyle" + tableStyleFamilies[t.id>>8].name + strconv.Itoa(int(t.id&0xFF))
}

// Table is an Excel table (a ListObject): a named, styled range with a header row and filter
// buttons, added with [Sheet.AddTable]. Ranges are 1-based and inclusive.
//
// With a header row (the default), Columns must list the header texts exactly as written in
// the range's first row: Excel identifies table columns by those names and repairs the file
// otherwise.
type Table struct {
	FirstRow, FirstColumn, LastRow, LastColumn int
	// Name is the workbook-unique name used in structured references; empty gives Table1,
	// Table2, and so on.
	Name string
	// Columns names the columns; required with a header row, otherwise Column1, Column2, ...
	// by default.
	Columns []string
	Style   TableStyle
	// NoHeaderRow declares that the range has no header row; the first row holds data.
	NoHeaderRow bool
	// NoFilterButtons removes the filter buttons from the header row.
	NoFilterButtons bool
	// NoRowStripes turns off banded rows.
	NoRowStripes  bool
	ColumnStripes bool
	// HighlightFirstColumn and HighlightLastColumn emphasize those columns.
	HighlightFirstColumn bool
	HighlightLastColumn  bool
	// TotalsRow reports that the last row of the range is the table's totals row. It is set by
	// the reader; the writer cannot write totals rows and rejects a table that has one.
	TotalsRow bool
}

// tableMeta is a validated table, kept until its worksheet completes.
type tableMeta struct {
	id                                  int
	name                                string
	rng                                 cellRange
	columns                             []string
	style                               TableStyle
	header, filter                      bool
	rowStripes, columnStripes           bool
	highlightFirstCol, highlightLastCol bool
}

// AddTable turns a range of the sheet into an Excel table. The range may include rows not
// written yet. It must not overlap another table, merged cells or the sheet's auto filter.
//
//	sheet.WriteRow("Product", "Units")
//	sheet.WriteRow("Widget", 3)
//	sheet.WriteRow("Gadget", 5)
//	err := sheet.AddTable(hypaxel.Table{
//		FirstRow: 1, FirstColumn: 1, LastRow: sheet.Row(), LastColumn: 2,
//		Name: "Orders", Columns: []string{"Product", "Units"},
//	})
func (s *Sheet) AddTable(t Table) error {
	if !s.usable() {
		return s.w.err
	}
	w := s.w
	meta, err := newTableMeta(&t, w.tableCount+1, w.tableNames)
	if err != nil {
		return err
	}
	for i := range w.tables {
		if overlaps(w.tables[i].rng, meta.rng) {
			return fmt.Errorf("%w: tables cannot overlap each other", ErrOverlappingRange)
		}
	}
	for _, merge := range w.merges {
		if overlaps(merge, meta.rng) {
			return fmt.Errorf("%w: a table cannot overlap merged cells", ErrOverlappingRange)
		}
	}
	if f := w.sheets[len(w.sheets)-1].autoFilter; f != nil && overlaps(*f, meta.rng) {
		return fmt.Errorf("%w: a table cannot overlap the worksheet auto filter (tables have their own filter)", ErrOverlappingRange)
	}
	w.tableCount++
	w.tableNames = append(w.tableNames, strings.ToLower(meta.name))
	w.tables = append(w.tables, meta)
	return nil
}

func (w *Writer) overlapsTable(r cellRange) bool {
	for i := range w.tables {
		if overlaps(w.tables[i].rng, r) {
			return true
		}
	}
	return false
}

// overlaps reports whether two ranges share at least one cell.
func overlaps(a, b cellRange) bool {
	return a.firstRow <= b.lastRow && b.firstRow <= a.lastRow && a.firstColumn <= b.lastColumn && b.firstColumn <= a.lastColumn
}

// newTableMeta validates t; taken holds the lower-cased names already used in the workbook.
func newTableMeta(t *Table, id int, taken []string) (tableMeta, error) {
	r, err := newRange(t.FirstRow, t.FirstColumn, t.LastRow, t.LastColumn)
	if err != nil {
		return tableMeta{}, err
	}
	header := !t.NoHeaderRow
	if header && r.lastRow == r.firstRow {
		return tableMeta{}, fmt.Errorf("%w: a table with a header row needs at least one data row", ErrInvalidArgument)
	}
	if t.TotalsRow {
		return tableMeta{}, fmt.Errorf("%w: tables with a totals row can be read but not written", ErrInvalidArgument)
	}
	if _, custom := t.Style.Custom(); custom && !t.Style.valid() {
		return tableMeta{}, fmt.Errorf("%w: invalid table style name (1-255 characters on one line)", ErrInvalidArgument)
	}
	if !t.Style.valid() {
		return tableMeta{}, fmt.Errorf("%w: table style number out of range (Light 1-21, Medium 1-28, Dark 1-11)", ErrInvalidArgument)
	}

	name := t.Name
	if name == "" {
		name = "Table" + strconv.Itoa(id)
	} else if !validTableName(name) {
		return tableMeta{}, fmt.Errorf("%w: %q", ErrInvalidTableName, name)
	}
	lower := strings.ToLower(name)
	for _, used := range taken {
		if used == lower {
			return tableMeta{}, fmt.Errorf("%w: %q", ErrDuplicateTableName, name)
		}
	}

	width := int(r.lastColumn - r.firstColumn + 1)
	columns := t.Columns
	if len(columns) == 0 {
		if header {
			return tableMeta{}, fmt.Errorf("%w: a table with a header row needs its column names (Table.Columns)", ErrInvalidArgument)
		}
		columns = make([]string, width)
		for i := range columns {
			columns[i] = "Column" + strconv.Itoa(i+1)
		}
	} else {
		columns = append([]string(nil), columns...)
	}
	if len(columns) != width {
		return tableMeta{}, fmt.Errorf("%w: the number of table column names must equal the width of the range", ErrInvalidArgument)
	}
	seen := make(map[string]struct{}, len(columns))
	for _, column := range columns {
		if column == "" || utf16Len(column) > 255 {
			return tableMeta{}, fmt.Errorf("%w: table column names must be 1-255 characters", ErrInvalidArgument)
		}
		key := strings.ToLower(column)
		if _, dup := seen[key]; dup {
			return tableMeta{}, fmt.Errorf("%w: table column names must be unique (case-insensitive)", ErrInvalidArgument)
		}
		seen[key] = struct{}{}
	}

	return tableMeta{
		id:                id,
		name:              name,
		rng:               r,
		columns:           columns,
		style:             t.Style,
		header:            header,
		filter:            header && !t.NoFilterButtons,
		rowStripes:        !t.NoRowStripes,
		columnStripes:     t.ColumnStripes,
		highlightFirstCol: t.HighlightFirstColumn,
		highlightLastCol:  t.HighlightLastColumn,
	}, nil
}

func (t *tableMeta) appendXML(x []byte) []byte {
	x = append(x, xmlDeclaration...)
	x = append(x, `<table xmlns="`+nsMain+`" id="`...)
	x = appendUint(x, uint64(t.id))
	x = append(x, `" name="`...)
	x = appendEscaped(x, t.name)
	x = append(x, `" displayName="`...)
	x = appendEscaped(x, t.name)
	x = append(x, `" ref="`...)
	x = appendRange(x, t.rng)
	x = append(x, '"')
	if !t.header {
		x = append(x, ` headerRowCount="0"`...)
	}
	x = append(x, ` totalsRowShown="0">`...)
	if t.filter {
		x = append(x, `<autoFilter ref="`...)
		x = appendRange(x, t.rng)
		x = append(x, `"/>`...)
	}
	x = append(x, `<tableColumns count="`...)
	x = appendUint(x, uint64(len(t.columns)))
	x = append(x, `">`...)
	for i, column := range t.columns {
		x = append(x, `<tableColumn id="`...)
		x = appendUint(x, uint64(i+1))
		x = append(x, `" name="`...)
		x = appendColumnNameAttr(x, column)
		x = append(x, `"/>`...)
	}
	x = append(x, "</tableColumns><tableStyleInfo"...)
	if t.style != TableStyleNone {
		x = append(x, ` name="`...)
		x = appendStyleNameAttr(x, t.style.String())
		x = append(x, '"')
	}
	x = appendFlag(x, ` showFirstColumn="`, t.highlightFirstCol)
	x = appendFlag(x, ` showLastColumn="`, t.highlightLastCol)
	x = appendFlag(x, ` showRowStripes="`, t.rowStripes)
	x = appendFlag(x, ` showColumnStripes="`, t.columnStripes)
	return append(x, "/></table>"...)
}

func appendFlag(x []byte, attr string, on bool) []byte {
	x = append(x, attr...)
	if on {
		return append(x, `1"`...)
	}
	return append(x, `0"`...)
}

// appendStyleNameAttr escapes only XML's specials, so that the name is stored exactly as given:
// validation already excludes what XML 1.0 cannot carry, and readers do not decode Excel's
// _xHHHH_ escapes in style names.
func appendStyleNameAttr(x []byte, s string) []byte {
	for i := 0; i < len(s); i++ {
		switch s[i] {
		case '&':
			x = append(x, "&amp;"...)
		case '<':
			x = append(x, "&lt;"...)
		case '>':
			x = append(x, "&gt;"...)
		case '"':
			x = append(x, "&quot;"...)
		default:
			x = append(x, s[i])
		}
	}
	return x
}

// appendColumnNameAttr escapes like appendEscaped but writes line breaks and tabs, which
// attribute normalization would turn into spaces, as _xHHHH_ escapes, as Excel does for table
// column names.
func appendColumnNameAttr(x []byte, s string) []byte {
	start := 0
	for i := 0; i < len(s); i++ {
		var ref string
		switch s[i] {
		case '\n':
			ref = "_x000A_"
		case '\r':
			ref = "_x000D_"
		case '\t':
			ref = "_x0009_"
		default:
			continue
		}
		x = appendEscaped(x, s[start:i])
		x = append(x, ref...)
		start = i + 1
	}
	return appendEscaped(x, s[start:])
}

// validTableName applies Excel's defined-name rules: a letter, '_' or '\' first, then only
// letters, digits, '_', '.' and '\', at most 255 characters, and not a cell reference.
func validTableName(name string) bool {
	for i, r := range name {
		letter := unicode.IsLetter(r) || r == '_' || r == '\\'
		if i == 0 && !letter {
			return false
		}
		if !letter && !unicode.IsNumber(r) && r != '.' {
			return false
		}
	}
	return name != "" && utf16Len(name) <= 255 && !looksLikeReference(name)
}

// looksLikeReference reports A1-style (up to XFD1048576) and R1C1-style (R, C, R2, RC3, ...)
// references, which are not valid names.
func looksLikeReference(name string) bool {
	upper := []byte(name)
	for i, b := range upper {
		if 'a' <= b && b <= 'z' {
			upper[i] = b - 'a' + 'A'
		}
	}
	letters := 0
	for letters < len(upper) && 'A' <= upper[letters] && upper[letters] <= 'Z' {
		letters++
	}
	digits := upper[letters:]
	if letters >= 1 && letters <= 3 && len(digits) > 0 && allDigits(digits) {
		column := 0
		for _, b := range upper[:letters] {
			column = column*26 + int(b-'A'+1)
		}
		row, err := strconv.ParseUint(string(digits), 10, 64)
		if column <= MaxColumns && err == nil && row >= 1 && row <= MaxRows {
			return true
		}
	}
	i := 0
	if i < len(upper) && upper[i] == 'R' {
		i++
		for i < len(upper) && isDigit(upper[i]) {
			i++
		}
	}
	if i < len(upper) && upper[i] == 'C' {
		i++
		for i < len(upper) && isDigit(upper[i]) {
			i++
		}
	}
	return i > 0 && i == len(upper)
}

func isDigit(b byte) bool { return '0' <= b && b <= '9' }

func allDigits(b []byte) bool {
	for _, c := range b {
		if !isDigit(c) {
			return false
		}
	}
	return true
}
