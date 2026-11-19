package hypaxel

import (
	"bytes"
	"io"
	"iter"
	"slices"
	"strconv"
	"unicode/utf8"
)

// maxRowText bounds the decoded text of one row.
const maxRowText = 256 << 20

type cellType uint8

const (
	cellNumber cellType = iota
	cellShared
	cellFormulaString
	cellInlineString
	cellBool
	cellError
	cellISODate
	cellUnknown
)

func parseCellType(t []byte) cellType {
	switch string(t) {
	case "n":
		return cellNumber
	case "s":
		return cellShared
	case "str":
		return cellFormulaString
	case "inlineStr":
		return cellInlineString
	case "b":
		return cellBool
	case "e":
		return cellError
	case "d":
		return cellISODate
	}
	return cellUnknown
}

type rawCell struct {
	num    float64
	column uint32
	// Byte offsets into the row's text, or start is the index into the shared string table.
	start  uint32
	end    uint32
	kind   ValueKind
	date   DateKind
	shared bool
}

const (
	rowsBeforeData uint8 = iota
	rowsInData
	rowsDone
)

// Rows streams the rows of one worksheet, front to back, like a [bufio.Scanner]:
//
//	rows, err := r.Rows("Orders")
//	if err != nil {
//		return err
//	}
//	defer rows.Close()
//	for rows.Next() {
//		row := rows.Row()
//		fmt.Println(row.Index(), row.Get(1))
//	}
//	return rows.Err()
//
// A Rows is not safe for concurrent use; open one per goroutine.
type Rows struct {
	x        *xmlReader
	rc       io.ReadCloser
	styles   []DateKind
	row      Row
	scratch  []byte
	state    uint8
	previous uint32
	err      error
}

// Next advances to the next row that holds at least one value; rows that are missing from the
// file or only carry formatting are skipped. It returns false at the end of the sheet or on an
// error, and closes the Rows then.
func (r *Rows) Next() bool {
	if r.state == rowsDone {
		return false
	}
	ok, err := r.advance()
	if err != nil {
		r.err = err
		r.finish()
		return false
	}
	if !ok {
		r.finish()
	}
	return ok
}

// Row returns the current row. It is overwritten by the next call to Next; the strings and
// values taken from it stay valid.
func (r *Rows) Row() *Row {
	return &r.row
}

// Err returns the error that stopped Next, if any.
func (r *Rows) Err() error {
	return r.err
}

// Close releases the worksheet stream. It is not needed after Next has returned false, and
// may be called more than once.
func (r *Rows) Close() error {
	if r.rc != nil {
		r.state = rowsDone
		r.rc.Close()
		r.rc = nil
	}
	return nil
}

// All returns an iterator over the remaining rows, for use with range. The *Row is reused
// between iterations. After the last row, a final iteration yields the error that stopped
// reading, if any. The Rows is closed when the loop ends.
func (r *Rows) All() iter.Seq2[*Row, error] {
	return func(yield func(*Row, error) bool) {
		defer r.Close()
		for r.Next() {
			if !yield(&r.row, nil) {
				return
			}
		}
		if r.err != nil {
			yield(nil, r.err)
		}
	}
}

// finish reads the rest of the ZIP entry, so that its size and CRC-32 are verified, and closes it.
func (r *Rows) finish() {
	if r.rc == nil {
		return
	}
	if r.err == nil && !r.x.eof {
		if _, err := io.Copy(io.Discard, r.rc); err != nil {
			r.err = sourceError(r.x.part, err)
		}
	}
	r.Close()
}

func (r *Rows) advance() (bool, error) {
	x := r.x
	for {
		switch r.state {
		case rowsDone:
			return false, nil
		case rowsBeforeData:
			if err := r.findSheetData(); err != nil {
				return false, err
			}
			continue
		}
		ev, err := x.next()
		if err != nil {
			return false, err
		}
		switch ev {
		case xmlStart, xmlEmpty:
			if string(x.name) != "row" {
				if ev == xmlStart {
					if err := x.skipElement(); err != nil {
						return false, err
					}
				}
				continue
			}
		case xmlEnd, xmlEOF:
			r.state = rowsDone
			continue
		default:
			continue
		}
		number := r.previous + 1
		if raw, ok := attr(x.attrs, "r"); ok {
			if number, ok = parseUint32(raw); !ok {
				return false, x.malformed("a row has an invalid row number")
			}
		}
		if number < 1 || number > MaxRows {
			return false, x.malformed("row number %d is outside 1..1048576", number)
		}
		r.previous = number
		if ev == xmlEmpty {
			continue
		}
		r.row.index = int(number)
		if err := r.readCells(); err != nil {
			return false, err
		}
		if len(r.row.cells) > 0 {
			return true, nil
		}
	}
}

func (r *Rows) findSheetData() error {
	x := r.x
	for {
		ev, err := x.next()
		if err != nil {
			return err
		}
		switch ev {
		case xmlEOF:
			r.state = rowsDone
			return nil
		case xmlEmpty:
			if string(x.name) == "sheetData" {
				r.state = rowsDone
				return nil
			}
		case xmlStart:
			switch string(x.name) {
			case "sheetData":
				r.state = rowsInData
				return nil
			case "worksheet", "dialogsheet", "macrosheet":
			default:
				if err := x.skipElement(); err != nil {
					return err
				}
			}
		}
	}
}

func (r *Rows) readCells() error {
	x := r.x
	row := &r.row
	row.cells = row.cells[:0]
	row.text = row.text[:0]
	row.strValid = false
	column := uint32(0)
	sorted := true
	for {
		var c cellTag
		ev, ok := c.parse(x)
		if !ok {
			var err error
			if ev, err = x.next(); err != nil {
				return err
			}
			switch ev {
			case xmlStart, xmlEmpty:
				if string(x.name) != "c" {
					if ev == xmlStart {
						if err := x.skipElement(); err != nil {
							return err
						}
					}
					continue
				}
				c.attributes(x.attrs)
			case xmlEnd:
				if !sorted {
					sortCells(row)
				}
				return nil
			case xmlEOF:
				return x.unexpectedEOF()
			default:
				continue
			}
		}
		reference, typ, style, hasReference := c.reference, c.typ, c.style, c.hasReference
		next := column + 1
		if hasReference {
			next = parseColumn(reference)
		}
		if next < 1 || next > MaxColumns {
			return x.malformed("row %d has a cell with an invalid reference", row.index)
		}
		ct := cellNumber
		if typ != nil {
			if ct = parseCellType(typ); ct == cellUnknown {
				return x.malformed("row %d has a cell with an unknown type", row.index)
			}
		}
		var styleIndex uint32
		if style != nil {
			styleIndex, _ = parseDigits(style)
		}
		if next <= column {
			sorted = false
		}
		column = next
		if ev == xmlEmpty {
			continue
		}
		cell, ok, err := r.readCell(ct, styleIndex)
		if err != nil {
			return err
		}
		if ok {
			if len(row.cells) >= MaxColumns {
				return x.malformed("row %d has more than 16384 cells", row.index)
			}
			cell.column = column
			row.cells = append(row.cells, cell)
		}
		if len(row.text) > maxRowText {
			return partError(x.part, ErrLimitExceeded, "row %d holds more than %d bytes of text", row.index, maxRowText)
		}
	}
}

// cellTag holds the raw attributes of a <c> start tag, valid until the parser advances.
type cellTag struct {
	reference, typ, style []byte
	hasReference          bool
}

func (c *cellTag) set(name, value []byte) {
	switch string(localName(name)) {
	case "r":
		c.reference, c.hasReference = value, true
	case "t":
		c.typ = value
	case "s":
		c.style = value
	}
}

func (c *cellTag) attributes(attrs []byte) {
	for it := (attrIter{attrs}); ; {
		name, value, ok := it.next()
		if !ok {
			return
		}
		c.set(name, value)
	}
}

// parse reads an unprefixed <c> start tag in a single pass over the buffered input, the shape
// nearly every cell has. It consumes nothing and returns false when the input has another
// shape or the tag is not completely buffered; the caller then uses the general parser.
func (c *cellTag) parse(x *xmlReader) (xmlEvent, bool) {
	b := x.buf[x.pos:x.end]
	if len(b) < 3 || b[0] != '<' || b[1] != 'c' || !(isXMLSpace(b[2]) || b[2] == '>' || b[2] == '/') {
		return 0, false
	}
	for i := 2; ; {
		for i < len(b) && isXMLSpace(b[i]) {
			i++
		}
		if i >= len(b) {
			return 0, false
		}
		switch b[i] {
		case '>':
			x.pos += i + 1
			x.depth++
			return xmlStart, true
		case '/':
			if i+1 < len(b) && b[i+1] == '>' {
				x.pos += i + 2
				return xmlEmpty, true
			}
			return 0, false
		}
		start := i
		for i < len(b) && b[i] != '=' && !isXMLSpace(b[i]) {
			i++
		}
		name := b[start:i]
		for i < len(b) && isXMLSpace(b[i]) {
			i++
		}
		if i >= len(b) || b[i] != '=' {
			return 0, false
		}
		i++
		for i < len(b) && isXMLSpace(b[i]) {
			i++
		}
		if i >= len(b) || (b[i] != '"' && b[i] != '\'') {
			return 0, false
		}
		end := indexByte(b[i+1:], b[i])
		if end < 0 {
			return 0, false
		}
		if !bytes.HasPrefix(name, []byte("xmlns")) {
			c.set(name, b[i+1:i+1+end])
		}
		i += end + 2
	}
}

// sortCells orders cells by column; of cells with the same column, the last one wins.
func sortCells(row *Row) {
	slices.SortStableFunc(row.cells, func(a, b rawCell) int { return int(a.column) - int(b.column) })
	out := row.cells[:0]
	for _, c := range row.cells {
		if n := len(out); n > 0 && out[n-1].column == c.column {
			out[n-1] = c
		} else {
			out = append(out, c)
		}
	}
	row.cells = out
}

// readCell reads the children of a <c> element up to its end tag.
func (r *Rows) readCell(ct cellType, style uint32) (rawCell, bool, error) {
	x := r.x
	row := &r.row
	isText := ct == cellFormulaString || ct == cellInlineString || ct == cellError
	textStart := len(row.text)
	hasValue := false
	r.scratch = r.scratch[:0]
	var raw []byte
	for {
		var name string
		if x.consume("</c>") {
			x.depth--
			break
		} else if x.consume("<v>") {
			x.depth++
			name = "v"
		} else if x.consume("<is>") {
			x.depth++
			name = "is"
		} else {
			ev, err := x.next()
			if err != nil {
				return rawCell{}, false, err
			}
			if ev == xmlEnd {
				break
			}
			if ev == xmlEOF {
				return rawCell{}, false, x.unexpectedEOF()
			}
			if ev != xmlStart {
				continue
			}
			name = string(x.name)
		}
		var err error
		switch name {
		case "v":
			hasValue = true
			if isText {
				if row.text, err = x.readText(row.text, true); err != nil {
					return rawCell{}, false, err
				}
				continue
			}
			var text []byte
			var ok bool
			b := x.buf[x.pos:x.end]
			if lt := indexByte(b, '<'); lt >= 0 && len(b) >= lt+4 && string(b[lt:lt+4]) == "</v>" {
				text, ok = b[:lt], true
				x.pos += lt + 4
				x.depth--
			} else if text, ok, err = x.rawText(); err != nil {
				return rawCell{}, false, err
			}
			if ok {
				// Copied, because reading the end tag may refill the parser's buffer.
				if bytes.IndexByte(text, '&') >= 0 {
					var msg string
					if r.scratch, msg = decodeText(r.scratch[:0], text, true, false); msg != "" {
						return rawCell{}, false, x.malformed("%s", msg)
					}
				} else {
					r.scratch = append(r.scratch[:0], text...)
				}
				raw = r.scratch
				continue
			}
			if r.scratch, err = x.readText(r.scratch[:0], false); err != nil {
				return rawCell{}, false, err
			}
			raw = r.scratch
		case "is":
			if ct != cellInlineString {
				if err := x.skipElement(); err != nil {
					return rawCell{}, false, err
				}
				continue
			}
			hasValue = true
			if row.text, err = readRichText(x, row.text, "is", maxRowText); err != nil {
				if err == errTextLimit {
					err = partError(x.part, ErrLimitExceeded, "row %d holds more than %d bytes of text", row.index, maxRowText)
				}
				return rawCell{}, false, err
			}
		default:
			if err := x.skipElement(); err != nil {
				return rawCell{}, false, err
			}
		}
	}
	if !hasValue {
		row.text = row.text[:textStart]
		return rawCell{}, false, nil
	}

	start, end := uint32(textStart), uint32(len(row.text))
	raw = trimSpace(raw)
	switch ct {
	case cellFormulaString, cellInlineString:
		return rawCell{kind: ValueString, start: start, end: end}, true, nil
	case cellError:
		return rawCell{kind: ValueError, start: start, end: end}, true, nil
	case cellNumber:
		if len(raw) == 0 {
			return rawCell{}, false, nil
		}
		n, ok := parseNumber(raw)
		if !ok {
			return rawCell{}, false, r.badValue("number", raw)
		}
		if int(style) < len(r.styles) {
			if kind := r.styles[style]; kind != DateKindNone {
				return rawCell{kind: ValueDateTime, num: n, date: kind}, true, nil
			}
		}
		return rawCell{kind: ValueNumber, num: n}, true, nil
	case cellShared:
		i, ok := parseDigits(raw)
		if !ok || int(i) >= row.sst.len() {
			return rawCell{}, false, r.badValue("shared string index", raw)
		}
		return rawCell{kind: ValueString, start: i, shared: true}, true, nil
	case cellBool:
		switch string(raw) {
		case "1", "true":
			return rawCell{kind: ValueBool, num: 1}, true, nil
		case "0", "false":
			return rawCell{kind: ValueBool}, true, nil
		}
		return rawCell{}, false, r.badValue("boolean", raw)
	default:
		if serial, kind, ok := isoToSerial(string(raw), row.date1904); ok {
			return rawCell{kind: ValueDateTime, num: serial, date: kind}, true, nil
		}
		row.text = append(row.text, raw...)
		return rawCell{kind: ValueString, start: start, end: uint32(len(row.text))}, true, nil
	}
}

func (r *Rows) badValue(what string, raw []byte) error {
	shown := raw
	for i, n := 0, 0; i < len(raw); n++ {
		if n == 40 {
			shown = raw[:i]
			break
		}
		_, size := utf8.DecodeRune(raw[i:])
		i += size
	}
	return r.x.malformed("row %d has an invalid %s %s", r.row.index, what, strconv.Quote(string(shown)))
}

// parseNumber parses a cell number. Integers of up to 15 digits, exact in a float64, take a
// fast path.
func parseNumber(raw []byte) (float64, bool) {
	digits := raw
	negative := len(raw) > 0 && raw[0] == '-'
	if negative {
		digits = raw[1:]
	}
	if len(digits) > 0 && len(digits) <= 15 {
		var n int64
		integer := true
		for _, c := range digits {
			if c < '0' || c > '9' {
				integer = false
				break
			}
			n = n*10 + int64(c-'0')
		}
		if integer {
			if negative {
				n = -n
			}
			return float64(n), true
		}
	}
	for _, c := range raw {
		// Excel writes decimal numbers only; strconv would also accept hex, underscores, NaN
		// and infinities.
		if !(c >= '0' && c <= '9' || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
			return 0, false
		}
	}
	n, err := strconv.ParseFloat(string(raw), 64)
	return n, err == nil
}

// parseColumn parses the column letters of a cell reference such as AB12; 0 means invalid.
func parseColumn(reference []byte) uint32 {
	column := uint32(0)
	letters := 0
	for _, c := range reference {
		switch {
		case c >= 'A' && c <= 'Z':
			column = column*26 + uint32(c-'A'+1)
		case c >= 'a' && c <= 'z':
			column = column*26 + uint32(c-'a'+1)
		default:
			return column
		}
		if letters++; letters > 3 {
			return 0
		}
	}
	return column
}

// Row is one row of a worksheet, owned by the [Rows] that returned it and overwritten by its
// next call to Next.
type Row struct {
	index    int
	cells    []rawCell
	text     []byte
	str      string
	strValid bool
	sst      *stringTable
	date1904 bool
}

// Index returns the 1-based row number as shown in Excel.
func (r *Row) Index() int { return r.index }

// Len returns the number of cells with a value.
func (r *Row) Len() int { return len(r.cells) }

// LastColumn returns the 1-based column of the last cell with a value.
func (r *Row) LastColumn() int {
	if len(r.cells) == 0 {
		return 0
	}
	return int(r.cells[len(r.cells)-1].column)
}

// Cell returns the 1-based column and the value of the i-th cell with a value, 0 <= i < Len.
func (r *Row) Cell(i int) (column int, v Value) {
	c := &r.cells[i]
	return int(c.column), r.value(c)
}

// Cells returns an iterator over the 1-based column and the value of each cell with a value,
// in column order.
func (r *Row) Cells() iter.Seq2[int, Value] {
	return func(yield func(int, Value) bool) {
		for i := range r.cells {
			if !yield(int(r.cells[i].column), r.value(&r.cells[i])) {
				return
			}
		}
	}
}

// Get returns the value in a 1-based column, or an empty Value.
func (r *Row) Get(column int) Value {
	i, found := slices.BinarySearchFunc(r.cells, column, func(c rawCell, column int) int { return int(c.column) - column })
	if !found {
		return Value{}
	}
	return r.value(&r.cells[i])
}

// AppendValues appends the values of columns 1 to LastColumn to dst, with empty Values for the
// gaps, and returns the extended slice.
func (r *Row) AppendValues(dst []Value) []Value {
	column := uint32(1)
	for i := range r.cells {
		for ; column < r.cells[i].column; column++ {
			dst = append(dst, Value{})
		}
		dst = append(dst, r.value(&r.cells[i]))
		column++
	}
	return dst
}

func (r *Row) value(c *rawCell) Value {
	switch c.kind {
	case ValueString, ValueError:
		if c.shared {
			return Value{kind: ValueString, text: r.sst.get(c.start)}
		}
		if !r.strValid {
			// One string per row holds all of its inline text, so taking values allocates at
			// most once per row and the strings stay valid after the row is reused.
			r.str = string(r.text)
			r.strValid = true
		}
		return Value{kind: c.kind, text: r.str[c.start:c.end]}
	case ValueDateTime:
		return Value{kind: ValueDateTime, num: c.num, date: c.date, date1904: r.date1904}
	}
	return Value{kind: c.kind, num: c.num}
}
