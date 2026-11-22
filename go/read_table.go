package hypaxel

import (
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
)

// tableColumnCost is counted for each table column on top of its name against
// [WithMaxTableBytes].
const tableColumnCost = 32

// ColumnWidth is a run of adjacent columns that share one width or are hidden.
type ColumnWidth struct {
	// FirstColumn and LastColumn are 1-based and inclusive.
	FirstColumn, LastColumn int
	// Width is in characters of the default font, as in [SheetOptions]. It is 0 when HasWidth
	// is false.
	Width float64
	// HasWidth reports that the file stores a width; a hidden column range may store none.
	HasWidth bool
	// Hidden reports that the file sets the hidden flag.
	Hidden bool
}

// Visible reports false if the columns are hidden or have a width of 0; Excel shows neither.
func (c ColumnWidth) Visible() bool {
	return !c.Hidden && (!c.HasWidth || c.Width > 0)
}

// SheetLayout holds the worksheet settings stored ahead of the rows. It is returned by
// [Rows.Layout].
type SheetLayout struct {
	// ColumnWidths lists the column widths the sheet defines, explicit and auto-fitted alike,
	// and the hidden column ranges, with or without a width, in file order. Columns that are
	// not covered have the default width.
	ColumnWidths []ColumnWidth
	// OutlineSummaryAbove reports that summary rows sit above their groups.
	OutlineSummaryAbove bool
	// RowOutlineLevels is the deepest row outline level the sheet declares, 0 without grouping.
	RowOutlineLevels int
}

// ColumnWidth returns the width of a 1-based column, and false if the sheet leaves it at the
// default or stores no width for it.
func (l *SheetLayout) ColumnWidth(column int) (float64, bool) {
	for _, c := range l.ColumnWidths {
		if column >= c.FirstColumn && column <= c.LastColumn {
			return c.Width, c.HasWidth
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

// ColumnVisible reports false if a 1-based column is hidden or has a width of 0.
func (l *SheetLayout) ColumnVisible(column int) bool {
	for _, c := range l.ColumnWidths {
		if column >= c.FirstColumn && column <= c.LastColumn {
			return c.Visible()
		}
	}
	return true
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
// them in, and a part that the sheet references more than once is read once. A style that is not
// one of Excel's built-in styles is returned by name, as [TableStyleCustom] gives it, and
// TotalsRow is set for a table whose last row is a totals row. [WithMaxTables] and
// [WithMaxTableBytes] bound what one sheet may hold.
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
	var targets []string
	seen := make(map[string]struct{})
	count := 0
	for i := range rels {
		if !rels[i].is("table") {
			continue
		}
		if count++; count > r.cfg.maxTables {
			return nil, partError(part, ErrLimitExceeded, "the sheet has more than %d tables (see WithMaxTables)", r.cfg.maxTables)
		}
		if _, ok := seen[rels[i].target]; !ok {
			seen[rels[i].target] = struct{}{}
			targets = append(targets, rels[i].target)
		}
	}
	tables := make([]Table, 0, len(targets))
	budget := r.cfg.maxTableBytes
	for _, target := range targets {
		t, err := r.readTable(target, &budget)
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

// readTable reads one table part. budget is the number of name bytes the caller still accepts;
// it is reduced by what the table holds.
func (r *Reader) readTable(part string, budget *int) (Table, error) {
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
		cost := 0
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
			// Excel escapes table names like other text: T_x0041_ is stored as T_x005f_x0041_.
			if t.Name, ok = tableName(x.attrs, "displayName"); !ok {
				t.Name, _ = tableName(x.attrs, "name")
			}
			if count, ok := attr(x.attrs, "headerRowCount"); ok {
				t.NoHeaderRow = string(trimSpace(count)) == "0"
			}
			if count, ok := attr(x.attrs, "totalsRowCount"); ok {
				n, ok := parseUint32(count)
				t.TotalsRow = ok && n > 0
			}
			cost = len(t.Name)
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
			cost = len(column) + tableColumnCost
			t.Columns = append(t.Columns, column)
		case name == "tableStyleInfo":
			style, _ := decodedAttr(x.attrs, "name")
			t.Style = parseTableStyle(style)
			if name, ok := t.Style.Custom(); ok {
				cost = len(name)
			}
			t.NoRowStripes = !flagAttr(x.attrs, "showRowStripes")
			t.ColumnStripes = flagAttr(x.attrs, "showColumnStripes")
			t.HighlightFirstColumn = flagAttr(x.attrs, "showFirstColumn")
			t.HighlightLastColumn = flagAttr(x.attrs, "showLastColumn")
		}
		if *budget -= cost; *budget < 0 {
			return Table{}, partError(part, ErrLimitExceeded, "the tables of a worksheet hold more than %d bytes of names (see WithMaxTableBytes)", r.cfg.maxTableBytes)
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

// parseTableStyle returns the style with this name as stored in a file: a built-in style for
// names such as TableStyleMedium9, ignoring surrounding whitespace, TableStyleNone for an empty
// name, and otherwise a custom style with the name unchanged.
func parseTableStyle(name string) TableStyle {
	if name == "" {
		return TableStyleNone
	}
	if style, ok := builtInTableStyle(strings.TrimSpace(name)); ok {
		return style
	}
	return TableStyleCustom(name)
}

func builtInTableStyle(name string) (TableStyle, bool) {
	rest, ok := strings.CutPrefix(name, "TableStyle")
	if !ok {
		return TableStyle{}, false
	}
	digits := strings.IndexAny(rest, "0123456789")
	if digits < 0 {
		return TableStyle{}, false
	}
	n, ok := parseDigits([]byte(rest[digits:]))
	if !ok {
		return TableStyle{}, false
	}
	for family := tableLight; family <= tableDark; family++ {
		f := tableStyleFamilies[family]
		if rest[:digits] == f.name && n >= 1 && int(n) <= f.count {
			return newTableStyle(family, int(n)), true
		}
	}
	return TableStyle{}, false
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
	raw = trimSpace(raw)
	for _, c := range raw {
		if !isDigit(c) && c != '.' && c != '+' && c != '-' && c != 'e' && c != 'E' {
			return 0, false
		}
	}
	n, err := strconv.ParseFloat(string(raw), 64)
	if err != nil || math.IsInf(n, 0) || math.IsNaN(n) || n < 0 {
		return 0, false
	}
	return n, true
}

// tableName decodes a table name attribute, including Excel's _xHHHH_ escapes.
func tableName(attrs []byte, name string) (string, bool) {
	raw, ok := attr(attrs, name)
	if !ok {
		return "", false
	}
	out, msg := decodeText(nil, raw, true, true)
	return string(out), msg == ""
}
