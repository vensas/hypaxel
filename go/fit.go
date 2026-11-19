package hypaxel

import (
	"math"
	"strconv"
	"strings"
	"unicode"
)

// Column auto-fit: width estimates for cell content in pixels of Calibri 11 at 96 DPI, the
// metric Excel's column widths are defined in (a width of w characters is 7w+5 pixels).

const (
	defaultSampleRows = 1000
	// maxSampleBytes ends the sample early once the held-back XML reaches this size.
	maxSampleBytes = 8 << 20
	digitPx        = 7
)

// Calibri 11 advance widths in pixels for ASCII 0x20..0x7E.
var asciiPx = [95]uint8{
	3, 3, 4, 7, 7, 10, 10, 2, 4, 4, 7, 7, 3, 4, 3, 5, // space ! " # $ % & ' ( ) * + , - . /
	7, 7, 7, 7, 7, 7, 7, 7, 7, 7, // 0-9
	3, 3, 7, 7, 7, 6, 13, // : ; < = > ? @
	9, 8, 8, 9, 7, 7, 9, 9, 4, 5, 8, 6, 12, 10, 10, 8, 10, 8, 7, 7, 9, 9, 13, 8, 7, 7, // A-Z
	4, 5, 4, 7, 7, 4, // [ \ ] ^ _ `
	7, 8, 6, 8, 8, 5, 7, 8, 4, 4, 7, 4, 12, 8, 8, 8, 8, 5, 6, 5, 8, 7, 11, 7, 7, 6, // a-z
	5, 7, 5, 7, // { | } ~
}

func charPx(r rune) float32 {
	if r >= 0x20 && r < 0x7F {
		return float32(asciiPx[r-0x20])
	}
	if isWide(r) {
		return 15
	}
	return 8
}

// isWide reports East Asian wide and fullwidth characters and emoji, which Excel renders about
// two digits wide.
func isWide(r rune) bool {
	switch {
	case r >= 0x1100 && r <= 0x115F, r >= 0x2E80 && r <= 0x303E, r >= 0x3041 && r <= 0x33FF,
		r >= 0x3400 && r <= 0x4DBF, r >= 0x4E00 && r <= 0x9FFF, r >= 0xA000 && r <= 0xA4CF,
		r >= 0xAC00 && r <= 0xD7A3, r >= 0xF900 && r <= 0xFAFF, r >= 0xFE30 && r <= 0xFE4F,
		r >= 0xFF00 && r <= 0xFF60, r >= 0xFFE0 && r <= 0xFFE6, r >= 0x1F300 && r <= 0x1F64F,
		r >= 0x1F900 && r <= 0x1F9FF, r >= 0x20000 && r <= 0x3FFFD:
		return true
	}
	return false
}

// textPx returns the width of the longest line of s.
func textPx(s string) float32 {
	var widest, line float32
	for _, r := range s {
		switch r {
		case '\n':
			widest = max(widest, line)
			line = 0
		case '\r':
		default:
			line += charPx(r)
		}
	}
	return max(widest, line)
}

type shapeKind uint8

const (
	// shapeGeneral is the General (or text) format: up to 11 characters of the shortest
	// representation.
	shapeGeneral shapeKind = iota
	// shapeConstant is a fixed-width rendering such as a date or scientific notation.
	shapeConstant
	shapeFixed
)

// numberShape is how a style renders numbers, reduced to what matters for width.
type numberShape struct {
	kind shapeKind
	// px is the constant width, or for shapeFixed the width of literal text.
	px               float32
	decimals         uint8
	minIntegerDigits uint8
	percent          uint8
	thousands        bool
}

type styleFit struct {
	known bool
	scale float32
	shape numberShape
}

func measureStyle(r *styleRegistry, style uint32) styleFit {
	if style >= uint32(len(r.xfs)) {
		return styleFit{known: true, scale: 1}
	}
	x := r.xfs[style]
	font := r.fonts[x.font]
	scale := float32(math.Round(font.Size*100)) / 1100
	if font.Bold {
		scale *= 1.1
	}
	var shape numberShape
	if x.numFmt >= firstCustomNumFmt {
		shape = customShape(r.numFmts[x.numFmt-firstCustomNumFmt])
	} else {
		shape = builtinShape(x.numFmt)
	}
	return styleFit{known: true, scale: scale, shape: shape}
}

func fixedShape(decimals uint8, thousands bool, percent uint8) numberShape {
	return numberShape{kind: shapeFixed, decimals: decimals, minIntegerDigits: 1, thousands: thousands, percent: percent}
}

func constantShape(px float32) numberShape {
	return numberShape{kind: shapeConstant, px: px}
}

func builtinShape(id uint32) numberShape {
	switch id {
	case 1:
		return fixedShape(0, false, 0)
	case 2:
		return fixedShape(2, false, 0)
	case 3:
		return fixedShape(0, true, 0)
	case 4:
		return fixedShape(2, true, 0)
	case 9:
		return fixedShape(0, false, 1)
	case 10:
		return fixedShape(2, false, 1)
	case 11:
		return constantShape(textPx("1.00E+00"))
	case 14:
		return constantShape(textPx("12/31/2026"))
	case 21:
		return constantShape(textPx("23:59:59"))
	case 22:
		return constantShape(textPx("12/31/2026 23:59"))
	case 46:
		return constantShape(textPx("100:00:00"))
	}
	return numberShape{}
}

func isPlaceholder(r rune) bool { return r == '0' || r == '#' || r == '?' }

func asciiLower(s string) string {
	b := []byte(s)
	for i, c := range b {
		if 'A' <= c && c <= 'Z' {
			b[i] = c + 'a' - 'A'
		}
	}
	return string(b)
}

// customShape estimates the rendering of a custom format code from its first section.
func customShape(code string) numberShape {
	section, _, _ := strings.Cut(code, ";")
	if strings.EqualFold(section, "general") || section == "@" || section == "" {
		return numberShape{}
	}

	// Separate literal text from format tokens.
	var tokens []rune
	var literalPx float32
	rs := []rune(section)
	for i := 0; i < len(rs); i++ {
		switch c := rs[i]; c {
		case '"':
			for i++; i < len(rs) && rs[i] != '"'; i++ {
				literalPx += charPx(rs[i])
			}
		case '\\', '_':
			if i+1 < len(rs) {
				i++
				literalPx += charPx(rs[i])
			}
		case '*':
			i++
		case '[':
			end := i + 1
			for end < len(rs) && rs[end] != ']' {
				end++
			}
			inner := string(rs[i+1 : end])
			i = end
			if currency, ok := strings.CutPrefix(inner, "$"); ok {
				symbol, _, _ := strings.Cut(currency, "-")
				literalPx += textPx(symbol)
			} else if strings.Trim(asciiLower(inner), "hms") == "" {
				tokens = append(tokens, 'h', 'h', 'h') // elapsed time: allow three digits
			}
		default:
			tokens = append(tokens, c)
		}
	}

	tok := string(tokens)
	numeric := strings.ContainsAny(tok, "0#?")
	lower := asciiLower(tok)
	if !numeric && strings.ContainsAny(lower, "ymdhs") {
		return constantShape(literalPx + datePx(lower))
	}
	if !numeric {
		return numberShape{}
	}
	if strings.Contains(lower, "e+") || strings.Contains(lower, "e-") {
		digits := float32(strings.Count(tok, "0") + strings.Count(tok, "#") + strings.Count(tok, "?"))
		return constantShape(literalPx + digits*digitPx + textPx(".E+"))
	}

	integer, fraction, _ := strings.Cut(tok, ".")
	decimals := 0
	for _, r := range fraction {
		if isPlaceholder(r) {
			decimals++
		}
	}
	minInteger := strings.Count(integer, "0") + strings.Count(integer, "?")
	var others, spaces float32
	for _, r := range tok {
		switch {
		case isPlaceholder(r) || r == ',' || r == '.' || r == '%':
		case unicode.IsSpace(r):
			spaces += charPx(r)
		default:
			others += charPx(r)
		}
	}
	return numberShape{
		kind:             shapeFixed,
		px:               literalPx + others + spaces,
		decimals:         uint8(min(decimals, 30)),
		minIntegerDigits: uint8(min(minInteger, 30)),
		thousands:        strings.Contains(integer, ","),
		percent:          uint8(min(strings.Count(tok, "%"), 2)),
	}
}

// datePx returns the width of a date/time format with each token at its widest rendering.
func datePx(tokens string) float32 {
	ampm := strings.Contains(tokens, "am/pm")
	rs := []rune(tokens)
	var px float32
	for i := 0; i < len(rs); {
		c := rs[i]
		if ampm && c == 'a' {
			// "am/pm" renders as "PM".
			i += 5
			px += textPx("PM")
			continue
		}
		run := 1
		for i+run < len(rs) && rs[i+run] == c {
			run++
		}
		i += run
		switch {
		case c == 'y' && run <= 2:
			px += 2 * digitPx
		case c == 'y':
			px += 4 * digitPx
		case c == 'm' && run == 3:
			px += textPx("Sep")
		case c == 'm' && run >= 4:
			px += textPx("September")
		case c == 'd' && run == 3:
			px += textPx("Wed")
		case c == 'd' && run >= 4:
			px += textPx("Wednesday")
		case c == 'm' || c == 'd' || c == 's':
			px += 2 * digitPx
		case c == 'h':
			px += float32(max(run, 2)) * digitPx
		case c == '0':
			px += float32(run) * digitPx
		default:
			px += float32(run) * charPx(c)
		}
	}
	return px
}

func generalPx(v float64) float32 {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return textPx("#NUM!")
	}
	magnitude := math.Abs(v)
	if magnitude != 0 && (magnitude < 1e-9 || magnitude >= 1e11) {
		if v < 0 {
			return textPx("-1.23457E+11")
		}
		return textPx("1.23457E+11")
	}
	var buf [32]byte
	var digits []byte
	switch {
	case v == math.Trunc(v):
		digits = strconv.AppendInt(buf[:0], int64(v), 10)
	case magnitude < 1e-5:
		digits = strconv.AppendFloat(buf[:0], v, 'e', -1, 64)
	default:
		digits = strconv.AppendFloat(buf[:0], v, 'f', -1, 64)
	}
	// General shows at most 11 characters, rounding the fraction away.
	var px float32
	for _, b := range digits[:min(len(digits), 11)] {
		px += charPx(rune(b))
	}
	return px
}

// fitState measures the cells of the current sheet's auto-fit sample.
type fitState struct {
	sampleRows int
	maxWidth   float64
	// columns are the explicit column definitions from the sheet options.
	columns []Column
	// widths holds the widest content per column (index = column - 1), in pixels.
	widths []float32
	styles []styleFit
	rows   int
	// colsAt is where <cols> belongs in the held-back XML.
	colsAt int
}

func newFitState(o AutoFit, columns []Column, colsAt int) *fitState {
	f := &fitState{sampleRows: o.SampleRows, maxWidth: o.MaxWidth, columns: append([]Column(nil), columns...), colsAt: colsAt}
	if f.sampleRows == 0 {
		f.sampleRows = defaultSampleRows
	}
	if f.maxWidth == 0 {
		f.maxWidth = 255
	}
	return f
}

func (f *fitState) sampleComplete(xmlLen int) bool {
	return f.rows >= f.sampleRows || xmlLen >= maxSampleBytes
}

func (f *fitState) style(r *styleRegistry, style StyleID) *styleFit {
	i := int(style)
	if i >= len(f.styles) {
		f.styles = append(f.styles, make([]styleFit, i+1-len(f.styles))...)
	}
	s := &f.styles[i]
	if !s.known {
		*s = measureStyle(r, uint32(style))
	}
	return s
}

func (f *fitState) observe(column uint32, px float32) {
	i := int(column) - 1
	if i >= len(f.widths) {
		f.widths = append(f.widths, make([]float32, i+1-len(f.widths))...)
	}
	f.widths[i] = max(f.widths[i], px)
}

func (f *fitState) text(r *styleRegistry, column uint32, style StyleID, s string) {
	f.observe(column, textPx(s)*f.style(r, style).scale)
}

func (f *fitState) boolean(r *styleRegistry, column uint32, style StyleID, v bool) {
	if v {
		f.text(r, column, style, "TRUE")
	} else {
		f.text(r, column, style, "FALSE")
	}
}

func (f *fitState) number(r *styleRegistry, column uint32, style StyleID, v float64) {
	fit := f.style(r, style)
	if math.IsNaN(v) || math.IsInf(v, 0) {
		f.observe(column, textPx("#NUM!")*fit.scale)
		return
	}
	var px float32
	switch s := &fit.shape; s.kind {
	case shapeGeneral:
		px = generalPx(v)
	case shapeConstant:
		px = s.px
	case shapeFixed:
		scaled := math.Abs(v) * math.Pow(100, float64(s.percent))
		unit := math.Pow(10, float64(s.decimals))
		integral := math.Trunc(math.Round(scaled*unit) / unit)
		digits := 0
		if integral >= 1 {
			digits = int(math.Floor(math.Log10(integral))) + 1
		}
		digits = max(digits, int(s.minIntegerDigits))
		px = float32(digits)*digitPx + s.px + float32(s.percent)*charPx('%')
		if s.thousands && digits > 3 {
			px += float32((digits-1)/3) * charPx(',')
		}
		if s.decimals > 0 {
			px += charPx('.') + float32(s.decimals)*digitPx
		}
		if v < 0 {
			px += charPx('-')
		}
	}
	f.observe(column, px*fit.scale)
}

// appendCols writes <cols> for the explicit columns and the fitted ones; explicit widths win.
func (f *fitState) appendCols(x []byte) []byte {
	wrote := false
	for i := range max(len(f.columns), len(f.widths)) {
		var column Column
		if i < len(f.columns) {
			column = f.columns[i]
		}
		var px float32
		if i < len(f.widths) {
			px = f.widths[i]
		}
		if column == (Column{}) && px <= 0 {
			continue
		}
		if !(column.Width > 0) && px > 0 {
			column.Width = f.widthFor(px)
		}
		if !wrote {
			x = append(x, "<cols>"...)
			wrote = true
		}
		x = appendCol(x, i+1, column)
	}
	if wrote {
		x = append(x, "</cols>"...)
	}
	return x
}

// widthFor returns the column width in characters for content px wide, with Excel's padding.
func (f *fitState) widthFor(px float32) float64 {
	chars := (float64(px) + 2) / 7
	return min(max(min(math.Ceil(chars*100)/100, f.maxWidth), 0), 255)
}
