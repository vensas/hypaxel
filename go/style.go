package hypaxel

import (
	"fmt"
	"math"
	"strconv"
	"strings"
)

// StyleID is a handle to a cell format registered with [Writer.AddStyle]. It is only valid for
// the Writer that returned it.
type StyleID uint32

// DefaultStyle is the workbook's default ("Normal") format.
const DefaultStyle StyleID = 0

// Color is an ARGB color (0xAARRGGBB). The zero value means "not set".
type Color uint32

// Common colors.
const (
	Black     Color = 0xFF000000
	White     Color = 0xFFFFFFFF
	Red       Color = 0xFFFF0000
	Green     Color = 0xFF008000
	Blue      Color = 0xFF0000FF
	Yellow    Color = 0xFFFFFF00
	LightGray Color = 0xFFD9D9D9
	DarkGray  Color = 0xFF595959
)

// RGB returns an opaque color.
func RGB(r, g, b uint8) Color {
	return Color(0xFF000000 | uint32(r)<<16 | uint32(g)<<8 | uint32(b))
}

// ParseColor parses "#RRGGBB", "RRGGBB", "#AARRGGBB" or "AARRGGBB".
func ParseColor(s string) (Color, error) {
	hex := strings.TrimPrefix(s, "#")
	v, err := strconv.ParseUint(hex, 16, 32)
	if err != nil || (len(hex) != 6 && len(hex) != 8) {
		return 0, fmt.Errorf("%w: color %q is not RRGGBB or AARRGGBB", ErrInvalidArgument, s)
	}
	if len(hex) == 6 {
		v |= 0xFF000000
	}
	return Color(v), nil
}

func appendARGB(dst []byte, c Color) []byte {
	for shift := 28; shift >= 0; shift -= 4 {
		dst = append(dst, hexDigits[(c>>shift)&0xF])
	}
	return dst
}

// Font describes a font. The zero value is the workbook default, Calibri 11.
type Font struct {
	// Name of the font family; empty means Calibri.
	Name string
	// Size in points, 1 to 409; zero means 11.
	Size      float64
	Bold      bool
	Italic    bool
	Underline bool
	Strike    bool
	Color     Color
}

func (f Font) normalized() Font {
	if f.Name == "" {
		f.Name = "Calibri"
	}
	if !(f.Size > 0) {
		f.Size = 11
	}
	f.Size = math.Round(min(max(f.Size, 1), 409)*100) / 100
	return f
}

// BorderStyle is the line style of a border edge.
type BorderStyle uint8

// Border line styles.
const (
	BorderNone BorderStyle = iota
	BorderThin
	BorderMedium
	BorderDashed
	BorderDotted
	BorderThick
	BorderDouble
	BorderHair
	BorderMediumDashed
	BorderDashDot
	BorderMediumDashDot
	BorderDashDotDot
	BorderMediumDashDotDot
	BorderSlantDashDot
)

var borderStyleNames = [...]string{
	"none", "thin", "medium", "dashed", "dotted", "thick", "double", "hair", "mediumDashed",
	"dashDot", "mediumDashDot", "dashDotDot", "mediumDashDotDot", "slantDashDot",
}

// BorderLine is one edge of a border.
type BorderLine struct {
	Style BorderStyle
	Color Color
}

// Border describes the four edges of a cell. The zero value is no border.
type Border struct {
	Left, Right, Top, Bottom BorderLine
}

// BorderAll returns a border with the same line on all four edges.
func BorderAll(style BorderStyle, color Color) Border {
	line := BorderLine{Style: style, Color: color}
	return Border{Left: line, Right: line, Top: line, Bottom: line}
}

// HAlign is a horizontal alignment.
type HAlign uint8

// Horizontal alignments.
const (
	AlignGeneral HAlign = iota
	AlignLeft
	AlignCenter
	AlignRight
	AlignFill
	AlignJustify
	AlignCenterContinuous
	AlignDistributed
)

var hAlignNames = [...]string{"general", "left", "center", "right", "fill", "justify", "centerContinuous", "distributed"}

// VAlign is a vertical alignment.
type VAlign uint8

// Vertical alignments.
const (
	VAlignBottom VAlign = iota
	VAlignTop
	VAlignCenter
	VAlignJustify
	VAlignDistributed
)

var vAlignNames = [...]string{"bottom", "top", "center", "justify", "distributed"}

// Alignment describes how text is placed in a cell. The zero value is Excel's default.
type Alignment struct {
	Horizontal  HAlign
	Vertical    VAlign
	WrapText    bool
	ShrinkToFit bool
	Indent      uint8
	// Rotation in degrees, 0 to 180, or 255 for vertical text.
	Rotation uint8
}

// Style is a complete cell format. The zero value is the default format; set only what differs.
type Style struct {
	// NumberFormat is an Excel format code such as "0.00", "#,##0" or "yyyy-mm-dd".
	NumberFormat string
	Font         Font
	// Fill is a solid background color.
	Fill      Color
	Border    Border
	Alignment Alignment
}

// Commonly used number format codes.
const (
	FormatGeneral    = "General"
	FormatInteger    = "0"
	FormatDecimal2   = "0.00"
	FormatThousands  = "#,##0"
	FormatThousands2 = "#,##0.00"
	FormatPercent    = "0%"
	FormatPercent2   = "0.00%"
	FormatText       = "@"
	FormatDate       = "yyyy-mm-dd"
	FormatDateTime   = "yyyy-mm-dd hh:mm:ss"
	FormatTime       = "hh:mm:ss"
	FormatDuration   = "[h]:mm:ss"
)

type valueKind uint8

const (
	kindDate valueKind = iota
	kindDateTime
	kindDuration
	kindCount
)

// Built-in formats: short date and short date with time in the viewer's locale, and [h]:mm:ss.
var kindFormats = [kindCount]uint32{kindDate: 14, kindDateTime: 22, kindDuration: 46}

var builtinNumFmts = map[string]uint32{
	"General": 0, "0": 1, "0.00": 2, "#,##0": 3, "#,##0.00": 4, "0%": 9, "0.00%": 10, "0.00E+00": 11, "@": 49,
}

const firstCustomNumFmt = 164

type xf struct {
	numFmt, font, fill, border uint32
	alignment                  Alignment
}

type variantKey struct {
	style uint32
	kind  valueKind
}

type styleRegistry struct {
	fonts           []Font
	fontIDs         map[Font]uint32
	fills           []Color
	fillIDs         map[Color]uint32
	borders         []Border
	borderIDs       map[Border]uint32
	numFmts         []string
	numFmtIDs       map[string]uint32
	xfs             []xf
	xfIDs           map[xf]uint32
	styleIDs        map[Style]StyleID
	variants        map[variantKey]uint32
	defaultVariants [kindCount]uint32
}

func (r *styleRegistry) init() {
	r.fonts = []Font{Font{}.normalized()}
	r.fontIDs = map[Font]uint32{r.fonts[0]: 0}
	r.fillIDs = map[Color]uint32{}
	r.borderIDs = map[Border]uint32{}
	r.numFmtIDs = map[string]uint32{}
	r.xfs = []xf{{}}
	r.xfIDs = map[xf]uint32{{}: 0}
	r.styleIDs = map[Style]StyleID{}
	r.variants = map[variantKey]uint32{}
}

func (r *styleRegistry) add(style Style) StyleID {
	style.Font = style.Font.normalized()
	if id, ok := r.styleIDs[style]; ok {
		return id
	}
	var x xf
	if style.NumberFormat != "" {
		x.numFmt = r.numFmtID(style.NumberFormat)
	}
	font, ok := r.fontIDs[style.Font]
	if !ok {
		font = uint32(len(r.fonts))
		r.fonts = append(r.fonts, style.Font)
		r.fontIDs[style.Font] = font
	}
	x.font = font
	if style.Fill != 0 {
		fill, ok := r.fillIDs[style.Fill]
		if !ok {
			// Fill ids 0 and 1 are the two fills every workbook must define.
			r.fills = append(r.fills, style.Fill)
			fill = uint32(len(r.fills) + 1)
			r.fillIDs[style.Fill] = fill
		}
		x.fill = fill
	}
	if style.Border != (Border{}) {
		border, ok := r.borderIDs[style.Border]
		if !ok {
			r.borders = append(r.borders, style.Border)
			border = uint32(len(r.borders))
			r.borderIDs[style.Border] = border
		}
		x.border = border
	}
	x.alignment = style.Alignment
	id := StyleID(r.xfID(x))
	r.styleIDs[style] = id
	return id
}

// valueVariant returns a format able to display a date or duration: the style itself when it
// already has a number format, otherwise a variant of it with the matching built-in format.
func (r *styleRegistry) valueVariant(style uint32, kind valueKind) uint32 {
	if style == 0 {
		if cached := r.defaultVariants[kind]; cached != 0 {
			return cached
		}
	}
	return r.valueVariantSlow(style, kind)
}

func (r *styleRegistry) valueVariantSlow(style uint32, kind valueKind) uint32 {
	if style >= uint32(len(r.xfs)) || r.xfs[style].numFmt != 0 {
		return style
	}
	key := variantKey{style, kind}
	if id, ok := r.variants[key]; ok {
		return id
	}
	variant := r.xfs[style]
	variant.numFmt = kindFormats[kind]
	id := r.xfID(variant)
	r.variants[key] = id
	if style == 0 {
		r.defaultVariants[kind] = id
	}
	return id
}

func (r *styleRegistry) xfID(x xf) uint32 {
	if id, ok := r.xfIDs[x]; ok {
		return id
	}
	id := uint32(len(r.xfs))
	r.xfs = append(r.xfs, x)
	r.xfIDs[x] = id
	return id
}

func (r *styleRegistry) numFmtID(code string) uint32 {
	if id, ok := builtinNumFmts[code]; ok {
		return id
	}
	if id, ok := r.numFmtIDs[code]; ok {
		return id
	}
	id := uint32(firstCustomNumFmt + len(r.numFmts))
	r.numFmts = append(r.numFmts, code)
	r.numFmtIDs[code] = id
	return id
}

func (r *styleRegistry) appendXML(x []byte) []byte {
	x = append(x, xmlDeclaration...)
	x = append(x, `<styleSheet xmlns="`+nsMain+`">`...)

	if len(r.numFmts) > 0 {
		x = append(x, `<numFmts count="`...)
		x = appendUint(x, uint64(len(r.numFmts)))
		x = append(x, `">`...)
		for i, code := range r.numFmts {
			x = append(x, `<numFmt numFmtId="`...)
			x = appendUint(x, uint64(firstCustomNumFmt+i))
			x = append(x, `" formatCode="`...)
			x = appendEscaped(x, code)
			x = append(x, `"/>`...)
		}
		x = append(x, "</numFmts>"...)
	}

	x = append(x, `<fonts count="`...)
	x = appendUint(x, uint64(len(r.fonts)))
	x = append(x, `">`...)
	for _, font := range r.fonts {
		x = append(x, "<font>"...)
		if font.Bold {
			x = append(x, "<b/>"...)
		}
		if font.Italic {
			x = append(x, "<i/>"...)
		}
		if font.Strike {
			x = append(x, "<strike/>"...)
		}
		if font.Underline {
			x = append(x, "<u/>"...)
		}
		x = append(x, `<sz val="`...)
		x = appendFloat(x, font.Size, 64)
		x = append(x, `"/>`...)
		if font.Color != 0 {
			x = appendColorElement(x, "color", font.Color)
		}
		// No theme part is written, so fonts never reference theme colors or schemes.
		x = append(x, `<name val="`...)
		x = appendEscaped(x, font.Name)
		x = append(x, `"/><family val="2"/></font>`...)
	}

	x = append(x, `</fonts><fills count="`...)
	x = appendUint(x, uint64(len(r.fills)+2))
	x = append(x, `"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>`...)
	for _, color := range r.fills {
		x = append(x, `<fill><patternFill patternType="solid">`...)
		x = appendColorElement(x, "fgColor", color)
		x = append(x, `<bgColor indexed="64"/></patternFill></fill>`...)
	}

	x = append(x, `</fills><borders count="`...)
	x = appendUint(x, uint64(len(r.borders)+1))
	x = append(x, `"><border><left/><right/><top/><bottom/><diagonal/></border>`...)
	for _, border := range r.borders {
		x = append(x, "<border>"...)
		x = appendBorderLine(x, "left", border.Left)
		x = appendBorderLine(x, "right", border.Right)
		x = appendBorderLine(x, "top", border.Top)
		x = appendBorderLine(x, "bottom", border.Bottom)
		x = append(x, "<diagonal/></border>"...)
	}

	x = append(x, `</borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="`...)
	x = appendUint(x, uint64(len(r.xfs)))
	x = append(x, `">`...)
	for _, f := range r.xfs {
		x = append(x, `<xf numFmtId="`...)
		x = appendUint(x, uint64(f.numFmt))
		x = append(x, `" fontId="`...)
		x = appendUint(x, uint64(f.font))
		x = append(x, `" fillId="`...)
		x = appendUint(x, uint64(f.fill))
		x = append(x, `" borderId="`...)
		x = appendUint(x, uint64(f.border))
		x = append(x, `" xfId="0"`...)
		if f.numFmt != 0 {
			x = append(x, ` applyNumberFormat="1"`...)
		}
		if f.font != 0 {
			x = append(x, ` applyFont="1"`...)
		}
		if f.fill != 0 {
			x = append(x, ` applyFill="1"`...)
		}
		if f.border != 0 {
			x = append(x, ` applyBorder="1"`...)
		}
		if f.alignment == (Alignment{}) {
			x = append(x, "/>"...)
			continue
		}
		a := f.alignment
		x = append(x, ` applyAlignment="1"><alignment`...)
		if a.Horizontal != AlignGeneral && int(a.Horizontal) < len(hAlignNames) {
			x = append(x, ` horizontal="`...)
			x = append(x, hAlignNames[a.Horizontal]...)
			x = append(x, '"')
		}
		if a.Vertical != VAlignBottom && int(a.Vertical) < len(vAlignNames) {
			x = append(x, ` vertical="`...)
			x = append(x, vAlignNames[a.Vertical]...)
			x = append(x, '"')
		}
		if a.Rotation != 0 {
			x = append(x, ` textRotation="`...)
			x = appendUint(x, uint64(a.Rotation))
			x = append(x, '"')
		}
		if a.WrapText {
			x = append(x, ` wrapText="1"`...)
		}
		if a.Indent != 0 {
			x = append(x, ` indent="`...)
			x = appendUint(x, uint64(a.Indent))
			x = append(x, '"')
		}
		if a.ShrinkToFit {
			x = append(x, ` shrinkToFit="1"`...)
		}
		x = append(x, "/></xf>"...)
	}

	return append(x, `</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles><dxfs count="0"/><tableStyles count="0"/></styleSheet>`...)
}

func appendColorElement(x []byte, element string, color Color) []byte {
	x = append(x, '<')
	x = append(x, element...)
	x = append(x, ` rgb="`...)
	x = appendARGB(x, color)
	return append(x, `"/>`...)
}

func appendBorderLine(x []byte, element string, line BorderLine) []byte {
	x = append(x, '<')
	x = append(x, element...)
	if line.Style == BorderNone || int(line.Style) >= len(borderStyleNames) {
		return append(x, "/>"...)
	}
	x = append(x, ` style="`...)
	x = append(x, borderStyleNames[line.Style]...)
	x = append(x, `">`...)
	if line.Color != 0 {
		x = appendColorElement(x, "color", line.Color)
	} else {
		x = append(x, `<color indexed="64"/>`...)
	}
	x = append(x, "</"...)
	x = append(x, element...)
	return append(x, '>')
}
