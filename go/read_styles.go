package hypaxel

import (
	"errors"
	"math"
	"strings"
)

// builtinDateKind classifies a built-in number format id.
func builtinDateKind(id uint32) DateKind {
	switch {
	case id >= 14 && id <= 17, id >= 27 && id <= 31, id >= 34 && id <= 36, id >= 50 && id <= 54, id == 57, id == 58:
		return DateKindDate
	case id == 22:
		return DateKindDateTime
	case id >= 18 && id <= 21, id == 32, id == 33, id == 45, id == 47, id == 55, id == 56:
		return DateKindTime
	case id == 46:
		return DateKindDuration
	}
	return DateKindNone
}

// formatDateKind classifies a format code such as yyyy-mm-dd, [h]:mm or #,##0.00 "days".
//
// Only the first section (positive numbers) is considered. Quoted text, escaped characters,
// padding and fill characters, and bracketed colors, conditions and locales are ignored.
func formatDateKind(code string) DateKind {
	var date, clock, minuteOrMonth, elapsed bool
	for i := 0; i < len(code); i++ {
		switch c := code[i]; c {
		case ';':
			i = len(code)
		case '"':
			i++
			for i < len(code) && code[i] != '"' {
				i++
			}
		case '\\', '_', '*':
			i++
		case '[':
			end := strings.IndexByte(code[i:], ']')
			if end < 0 {
				end = len(code)
			} else {
				end += i
			}
			inner := code[i+1 : end]
			elapsed = elapsed || inner != "" && strings.Trim(strings.ToLower(inner), "hms") == ""
			i = end
		case 'y', 'Y', 'd', 'D', 'e':
			date = true
		case 'h', 'H', 's', 'S':
			clock = true
		case 'm', 'M':
			minuteOrMonth = true
		case 'g', 'G':
			if len(code)-i >= 7 && strings.EqualFold(code[i:i+7], "general") {
				i += 6
			}
		}
	}
	// m is a month next to dates and a minute next to hours or seconds; alone it is a month.
	date = date || minuteOrMonth && !clock
	switch {
	case elapsed:
		return DateKindDuration
	case date && clock:
		return DateKindDateTime
	case date:
		return DateKindDate
	case clock:
		return DateKindTime
	}
	return DateKindNone
}

// parseStyles returns the date classification of every cell format (cellXfs entry).
func parseStyles(x *xmlReader) ([]DateKind, error) {
	type custom struct {
		id   uint32
		kind DateKind
	}
	var customs []custom
	var kinds []DateKind
	inCellXfs := false
	for {
		ev, err := x.next()
		if err != nil {
			return nil, err
		}
		switch ev {
		case xmlEOF:
			return kinds, nil
		case xmlEnd:
			if string(x.name) == "cellXfs" {
				inCellXfs = false
			}
			continue
		case xmlStart, xmlEmpty:
		default:
			continue
		}
		start := ev == xmlStart
		switch name := string(x.name); {
		case name == "numFmt":
			raw, ok := attr(x.attrs, "numFmtId")
			id, idOK := parseUint32(raw)
			code, codeOK := decodedAttr(x.attrs, "formatCode")
			if ok && idOK && codeOK {
				customs = append(customs, custom{id, formatDateKind(code)})
			}
		case name == "cellXfs" && start:
			inCellXfs = true
		case name == "xf" && inCellXfs:
			raw, _ := attr(x.attrs, "numFmtId")
			id, _ := parseUint32(raw)
			kind := builtinDateKind(id)
			for i := len(customs) - 1; i >= 0; i-- {
				if customs[i].id == id {
					kind = customs[i].kind
					break
				}
			}
			kinds = append(kinds, kind)
		}
		if start {
			if name := string(x.name); name != "cellXfs" && name != "styleSheet" && name != "numFmts" {
				if err := x.skipElement(); err != nil {
					return nil, err
				}
			}
		}
	}
}

// parseUint32 parses a decimal integer surrounded by optional whitespace.
func parseUint32(raw []byte) (uint32, bool) {
	return parseDigits(trimSpace(raw))
}

// parseDigits parses a non-negative decimal integer that fits into a uint32.
func parseDigits(raw []byte) (uint32, bool) {
	if len(raw) == 0 || len(raw) > 10 {
		return 0, false
	}
	var n uint64
	for _, c := range raw {
		if c < '0' || c > '9' {
			return 0, false
		}
		n = n*10 + uint64(c-'0')
	}
	return uint32(n), n <= math.MaxUint32
}

// stringTable is the shared string table, stored as one string plus end offsets.
type stringTable struct {
	data string
	ends []uint32
}

func (s *stringTable) len() int { return len(s.ends) }

func (s *stringTable) get(i uint32) string {
	start := uint32(0)
	if i > 0 {
		start = s.ends[i-1]
	}
	return s.data[start:s.ends[i]]
}

func parseSharedStrings(x *xmlReader, maxBytes int64) (*stringTable, error) {
	// Offsets are uint32; one more text token must still fit after the limit check.
	limit := int(min(maxBytes, math.MaxUint32-maxXMLToken))
	var data []byte
	var ends []uint32
	for {
		ev, err := x.next()
		if err != nil {
			return nil, err
		}
		if ev == xmlEOF {
			break
		}
		if string(x.name) != "si" || (ev != xmlStart && ev != xmlEmpty) {
			continue
		}
		if ev == xmlStart {
			if data, err = readRichText(x, data, "si", limit); err != nil {
				if err == errTextLimit {
					err = partError(x.part, ErrLimitExceeded,
						"the shared string table exceeds %d bytes (see WithMaxSharedStringsBytes)", limit)
				}
				return nil, err
			}
		}
		ends = append(ends, uint32(len(data)))
	}
	// One copy to an exactly sized string; the growing buffer is garbage afterwards.
	return &stringTable{data: string(data), ends: ends[:len(ends):len(ends)]}, nil
}

var errTextLimit = errors.New("text limit")

// readRichText appends the text of an <si> or <is> element whose start tag was just read: plain
// <t> or rich-text runs <r><t>, without phonetic runs. It returns errTextLimit once dst holds
// more than limit bytes.
func readRichText(x *xmlReader, dst []byte, element string, limit int) ([]byte, error) {
	for {
		var ev xmlEvent
		var name string
		if x.consume("<t>") {
			x.depth++
			ev, name = xmlStart, "t"
		} else {
			var err error
			if ev, err = x.next(); err != nil {
				return dst, err
			}
			name = string(x.name)
		}
		var err error
		switch ev {
		case xmlStart:
			switch name {
			case "t":
				if dst, err = x.readText(dst, true); err != nil {
					return dst, err
				}
				if len(dst) > limit {
					return dst, errTextLimit
				}
			case "rPh", "rPr", "phoneticPr":
				if err := x.skipElement(); err != nil {
					return dst, err
				}
			}
		case xmlEnd:
			if name == element {
				return dst, nil
			}
		case xmlEOF:
			return dst, x.unexpectedEOF()
		}
	}
}
