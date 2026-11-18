package hypaxel

import (
	"math"
	"strconv"
	"unicode/utf8"
)

const (
	xmlDeclaration = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
	nsMain         = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
	nsRel          = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
	hexDigits      = "0123456789ABCDEF"
)

const (
	classPlain uint8 = iota
	classSpecial
	classHigh
)

// escapeClass marks the bytes that need attention: XML specials, '_' (Excel's _xHHHH_ escape),
// C0 control characters, and every non-ASCII byte, because a Go string may hold invalid UTF-8
// or U+FFFE/U+FFFF, none of which XML 1.0 can carry.
var escapeClass = func() (t [256]uint8) {
	for i := range 0x20 {
		if i != '\t' && i != '\n' && i != '\r' {
			t[i] = classSpecial
		}
	}
	for _, c := range []byte{'&', '<', '>', '"', '_'} {
		t[c] = classSpecial
	}
	for i := 0x80; i < 0x100; i++ {
		t[i] = classHigh
	}
	return t
}()

// appendEscaped appends s escaped for element content or a double-quoted attribute value.
// Characters XML 1.0 cannot represent become Excel's _xHHHH_ escape, literal text that looks
// like such an escape is protected with _x005F_, and invalid UTF-8 becomes U+FFFD.
func appendEscaped(dst []byte, s string) []byte {
	for i := 0; i < len(s); i++ {
		if escapeClass[s[i]] != classPlain {
			return appendEscapedSlow(dst, s, i)
		}
	}
	return append(dst, s...)
}

func appendEscapedSlow(dst []byte, s string, i int) []byte {
	plain := 0
	for i < len(s) {
		b := s[i]
		switch escapeClass[b] {
		case classPlain:
			i++
			continue
		case classHigh:
			r, size := utf8.DecodeRuneInString(s[i:])
			var replacement string
			switch {
			case r == utf8.RuneError && size == 1:
				replacement = "�"
			case r == 0xFFFE:
				replacement = "_xFFFE_"
			case r == 0xFFFF:
				replacement = "_xFFFF_"
			default:
				i += size
				continue
			}
			dst = append(dst, s[plain:i]...)
			dst = append(dst, replacement...)
			i += size
			plain = i
			continue
		}

		var replacement string
		switch b {
		case '&':
			replacement = "&amp;"
		case '<':
			replacement = "&lt;"
		case '>':
			replacement = "&gt;"
		case '"':
			replacement = "&quot;"
		case '_':
			if !looksLikeExcelEscape(s[i:]) {
				i++
				continue
			}
			replacement = "_x005F_"
		default:
			dst = append(dst, s[plain:i]...)
			dst = append(dst, '_', 'x', '0', '0', hexDigits[b>>4], hexDigits[b&0xF], '_')
			i++
			plain = i
			continue
		}
		dst = append(dst, s[plain:i]...)
		dst = append(dst, replacement...)
		i++
		plain = i
	}
	return append(dst, s[plain:]...)
}

func looksLikeExcelEscape(s string) bool {
	if len(s) < 7 || s[1] != 'x' || s[6] != '_' {
		return false
	}
	for _, c := range []byte(s[2:6]) {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
			return false
		}
	}
	return true
}

func isXMLSpace(b byte) bool {
	return b == ' ' || b == '\t' || b == '\n' || b == '\r'
}

func appendTextElement(dst []byte, s string) []byte {
	if len(s) > 0 && (isXMLSpace(s[0]) || isXMLSpace(s[len(s)-1])) {
		dst = append(dst, `<t xml:space="preserve">`...)
	} else {
		dst = append(dst, "<t>"...)
	}
	dst = appendEscaped(dst, s)
	return append(dst, "</t>"...)
}

func appendUint(dst []byte, v uint64) []byte {
	return strconv.AppendUint(dst, v, 10)
}

// appendFloat appends the shortest representation that round-trips; integral values skip the
// float formatter. bitSize is 32 for values that came from a float32, so 0.1 stays 0.1.
func appendFloat(dst []byte, v float64, bitSize int) []byte {
	if v == math.Trunc(v) && math.Abs(v) < 1<<53 {
		return strconv.AppendInt(dst, int64(v), 10)
	}
	return strconv.AppendFloat(dst, v, 'g', -1, bitSize)
}

// appendSerial appends an Excel date serial from whole days plus nanoseconds of the day using
// integer arithmetic only: up to 10 fractional digits (1e-10 day is 8.64 µs, finer than Excel's
// 1 ms resolution).
func appendSerial(dst []byte, day int64, nanosOfDay uint64) []byte {
	const unit = 8640
	const scale = 10_000_000_000
	fraction := (nanosOfDay + unit/2) / unit
	if fraction >= scale {
		day++
		fraction -= scale
	}
	dst = strconv.AppendInt(dst, day, 10)
	if fraction == 0 {
		return dst
	}
	var digits [10]byte
	for i := len(digits) - 1; i >= 0; i-- {
		digits[i] = '0' + byte(fraction%10)
		fraction /= 10
	}
	n := len(digits)
	for digits[n-1] == '0' {
		n--
	}
	dst = append(dst, '.')
	return append(dst, digits[:n]...)
}

func utf16Len(s string) int {
	n := 0
	for _, r := range s {
		n++
		if r >= 0x10000 {
			n++
		}
	}
	return n
}

func appendColumnName(dst []byte, column uint32) []byte {
	var buf [3]byte
	i := len(buf)
	for column > 0 {
		column--
		i--
		buf[i] = 'A' + byte(column%26)
		column /= 26
	}
	return append(dst, buf[i:]...)
}

func appendCellRef(dst []byte, row, column uint32) []byte {
	dst = appendColumnName(dst, column)
	return appendUint(dst, uint64(row))
}

func appendRange(dst []byte, r cellRange) []byte {
	dst = appendCellRef(dst, r.firstRow, r.firstColumn)
	dst = append(dst, ':')
	return appendCellRef(dst, r.lastRow, r.lastColumn)
}

// ColumnName returns the column letters for a 1-based column index (1 is "A", 27 is "AA"),
// or "" when the index is outside 1..16384.
func ColumnName(column int) string {
	if column < 1 || column > MaxColumns {
		return ""
	}
	return string(appendColumnName(nil, uint32(column)))
}

// CellRef returns an A1-style reference for a 1-based row and column, or "" when either is
// out of range.
func CellRef(row, column int) string {
	if row < 1 || row > MaxRows || column < 1 || column > MaxColumns {
		return ""
	}
	return string(appendCellRef(nil, uint32(row), uint32(column)))
}
