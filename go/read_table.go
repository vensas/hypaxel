package hypaxel

import (
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
)

// maxSheetTables bounds the tables read from one worksheet.
const maxSheetTables = 4096

// ColumnWidth is a run of adjacent columns that share one width.
type ColumnWidth struct {
	// FirstColumn and LastColumn are 1-based and inclusive.
	FirstColumn, LastColumn int
	// Width is in characters of the default font, as in [SheetOptions].
	Width float64
	// Hidden reports that the columns are hidden.
	Hidden bool
}

// SheetLayout holds the worksheet settings stored ahead of the rows. It is returned by
// [Rows.Layout].
type SheetLayout struct {
	// ColumnWidths lists the column widths the sheet defines, explicit and auto-fitted alike,
	// in file order. Columns that are not covered have the default width.
	ColumnWidths []ColumnWidth
	// OutlineSummaryAbove reports that summary rows sit above their groups.
	OutlineSummaryAbove bool
	// RowOutlineLevels is the deepest row outline level the sheet declares, 0 without grouping.
	RowOutlineLevels int
}

// ColumnWidth returns the width of a 1-based column, and false if the sheet leaves it at the
// default.
func (l *SheetLayout) ColumnWidth(column int) (float64, bool) {
	for _, c := range l.ColumnWidths {
		if column >= c.FirstColumn && column <= c.LastColumn {
			return c.Width, true
		}
	}
	return 0, false
}

// ColumnHidden reports whether a 1-based column is hidden.
func (l *SheetLayout) ColumnHidden(column int) bool {
	for _, c := range l.ColumnWidths {
		if column >= c.FirstColumn && column <= c.LastColumn {
			return c.Hidden
		}
	}
	return false
}

// Tables returns the Excel tables of the sheet with this name, matched exactly and then
// ignoring case. See [Reader.TablesAt].
func (r *Reader) Tables(name string) ([]Table, error) {
	index, err := r.sheetIndex(name)
	if err != nil {
		return nil, err
	}
	return r.TablesAt(index)
}

// TablesAt returns the Excel tables of the sheet at a 0-based position in [Reader.Sheets], as
// the [Table] values that [Sheet.AddTable] accepts.
//
// Tables live in their own parts, which are read on each call; reading rows does not touch
// them. They are returned from top to bottom, then left to right, whatever order the file lists
// them in. A style that is not one of Excel's built-in styles is returned as [TableStyleNone],
// and TotalsRow is set for a table whose last row is a totals row.
func (r *Reader) TablesAt(index int) ([]Table, error) {
	if index < 0 || index >= len(r.sheets) {
		return nil, fmt.Errorf("%w: no sheet at index %d", ErrSheetNotFound, index)
	}
	part := r.sheets[index].part
	if part == "" {
		return nil, nil
	}
	rels, err := r.relationships(relsPath(part))
	if err != nil {
		return nil, err
	}
	var tables []Table
	for i := range rels {
		if !rels[i].is("table") {
			continue
		}
		if len(tables) >= maxSheetTables {
			return nil, partError(part, ErrLimitExceeded, "the sheet has more than %d tables", maxSheetTables)
		}
		t, err := r.readTable(rels[i].target)
		if err != nil {
			return nil, err
		}
		tables = append(tables, t)
	}
	sort.SliceStable(tables, func(i, j int) bool {
		a, b := &tables[i], &tables[j]
		return a.FirstRow < b.FirstRow || a.FirstRow == b.FirstRow && a.FirstColumn < b.FirstColumn
	})
	return tables, nil
}

func (r *Reader) readTable(part string) (Table, error) {
	x, rc, err := r.open(part)
	if err != nil {
		return Table{}, err
	}
	defer rc.Close()
	t := Table{Style: TableStyleNone, NoFilterButtons: true, NoRowStripes: true}
	root := true
	for {
		ev, err := x.next()
		if err != nil {
			return Table{}, err
		}
		if ev == xmlEOF {
			break
		}
		if ev != xmlStart && ev != xmlEmpty {
			continue
		}
		descend := false
		name := string(x.name)
		switch {
		case root:
			if name != "table" {
				return Table{}, x.malformed("the part is not a table")
			}
			root = false
			ref, _ := attr(x.attrs, "ref")
			rng, ok := parseRange(ref)
			if !ok {
				return Table{}, x.malformed("the table has no valid cell range")
			}
			t.FirstRow, t.FirstColumn, t.LastRow, t.LastColumn = rng[0], rng[1], rng[2], rng[3]
			if t.Name, ok = decodedAttr(x.attrs, "displayName"); !ok {
				t.Name, _ = decodedAttr(x.attrs, "name")
			}
			if count, ok := attr(x.attrs, "headerRowCount"); ok {
				t.NoHeaderRow = string(trimSpace(count)) == "0"
			}
			if count, ok := attr(x.attrs, "totalsRowCount"); ok {
				n, ok := parseUint32(count)
				t.TotalsRow = ok && n > 0
			}
			descend = true
		case name == "autoFilter":
			t.NoFilterButtons = false
		case name == "tableColumns":
			descend = true
		case name == "tableColumn":
			if len(t.Columns) >= MaxColumns {
				return Table{}, x.malformed("the table has more than 16384 columns")
			}
			column := ""
			if raw, ok := attr(x.attrs, "name"); ok {
				if out, msg := decodeText(nil, raw, true, true); msg == "" {
					column = string(out)
				}
			}
			t.Columns = append(t.Columns, column)
		case name == "tableStyleInfo":
			style, _ := attr(x.attrs, "name")
			t.Style = parseTableStyle(string(trimSpace(style)))
			t.NoRowStripes = !flagAttr(x.attrs, "showRowStripes")
			t.ColumnStripes = flagAttr(x.attrs, "showColumnStripes")
			t.HighlightFirstColumn = flagAttr(x.attrs, "showFirstColumn")
			t.HighlightLastColumn = flagAttr(x.attrs, "showLastColumn")
		}
		if ev == xmlStart && !descend {
			if err := x.skipElement(); err != nil {
				return Table{}, err
			}
		}
	}
	if root {
		return Table{}, x.malformed("the part is not a table")
	}
	return t, nil
}

func isTrue(raw []byte) bool {
	s := string(trimSpace(raw))
	return s == "1" || s == "true"
}

func flagAttr(attrs []byte, name string) bool {
	raw, _ := attr(attrs, name)
	return isTrue(raw)
}

// parseTableStyle maps a built-in style name such as TableStyleMedium9; custom styles have no
// counterpart and give TableStyleNone.
func parseTableStyle(name string) TableStyle {
	rest, ok := strings.CutPrefix(name, "TableStyle")
	if !ok {
		return TableStyleNone
	}
	digits := strings.IndexAny(rest, "0123456789")
	if digits < 0 {
		return TableStyleNone
	}
	n, ok := parseDigits([]byte(rest[digits:]))
	if !ok {
		return TableStyleNone
	}
	for family := tableLight; family <= tableDark; family++ {
		f := tableStyleFamilies[family]
		if rest[:digits] != f.name || n < 1 || int(n) > f.count {
			continue
		}
		return newTableStyle(family, int(n))
	}
	return TableStyleNone
}

// parseRange parses A1:C10, or a single cell, into first row, first column, last row and last
// column.
func parseRange(raw []byte) ([4]int, bool) {
	s := string(trimSpace(raw))
	first, last, found := strings.Cut(s, ":")
	if !found {
		last = first
	}
	r1, c1, ok1 := parseCellRef(first)
	r2, c2, ok2 := parseCellRef(last)
	if !ok1 || !ok2 || r1 > r2 || c1 > c2 {
		return [4]int{}, false
	}
	return [4]int{r1, c1, r2, c2}, true
}

func parseCellRef(s string) (row, column int, ok bool) {
	letters, digits := 0, 0
	for i := 0; i < len(s); i++ {
		c := s[i] &^ 0x20
		switch {
		case s[i] == '$':
		case c >= 'A' && c <= 'Z' && digits == 0 && letters < 3:
			letters++
			column = column*26 + int(c-'A') + 1
		case isDigit(s[i]) && letters > 0 && digits < 7:
			digits++
			row = row*10 + int(s[i]-'0')
		default:
			return 0, 0, false
		}
	}
	return row, column, row >= 1 && row <= MaxRows && column >= 1 && column <= MaxColumns
}

func parseOutlineLevel(raw []byte) uint8 {
	n, ok := parseUint32(raw)
	if !ok {
		return 0
	}
	return uint8(min(n, 7))
}

// parseMeasure parses a width or height: a finite, non-negative decimal number.
func parseMeasure(raw []byte) (float64, bool) {
	n, err := strconv.ParseFloat(string(trimSpace(raw)), 64)
	if err != nil || math.IsInf(n, 0) || math.IsNaN(n) || n < 0 {
		return 0, false
	}
	return n, true
}
