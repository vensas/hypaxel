package hypaxel

import (
	"math"
	"testing"
)

func TestColors(t *testing.T) {
	if got := RGB(0x30, 0x54, 0x96); got != 0xFF305496 {
		t.Errorf("RGB = %#x", got)
	}
	for in, want := range map[string]Color{"#305496": 0xFF305496, "305496": 0xFF305496, "#80FFFFFF": 0x80FFFFFF, "00000000": 0} {
		got, err := ParseColor(in)
		if err != nil || got != want {
			t.Errorf("ParseColor(%q) = %#x, %v; want %#x", in, got, err, want)
		}
	}
	for _, bad := range []string{"", "#", "12345", "1234567", "GGGGGG", "#3054961122"} {
		if _, err := ParseColor(bad); err == nil {
			t.Errorf("ParseColor(%q) succeeded", bad)
		} else {
			wantErr(t, err, ErrInvalidArgument)
		}
	}
	if got := string(appendARGB(nil, 0x0A1B2C3D)); got != "0A1B2C3D" {
		t.Errorf("appendARGB = %q", got)
	}
}

func TestStyleDeduplication(t *testing.T) {
	w := NewWriter(nil)
	if id := w.AddStyle(Style{}); id != DefaultStyle {
		t.Errorf("the zero style got id %d", id)
	}
	if id := w.AddStyle(Style{Font: Font{Name: "Calibri", Size: 11}, NumberFormat: FormatGeneral}); id != DefaultStyle {
		t.Errorf("an explicit default style got id %d", id)
	}
	bold := w.AddStyle(Style{Font: Font{Bold: true}})
	if again := w.AddStyle(Style{Font: Font{Bold: true, Name: "Calibri", Size: 11.001}}); again != bold {
		t.Errorf("equal fonts got ids %d and %d", bold, again)
	}
	money := w.AddStyle(Style{NumberFormat: "#,##0.00 €"})
	other := w.AddStyle(Style{NumberFormat: "0.000"})
	if money == other || w.AddStyle(Style{NumberFormat: "#,##0.00 €"}) != money {
		t.Error("custom number formats are not de-duplicated")
	}
	filled := w.AddStyle(Style{Fill: Red})
	boxed := w.AddStyle(Style{Fill: Red, Border: BorderAll(BorderThin, 0)})
	if filled == boxed {
		t.Error("different styles share an id")
	}
	w.AddStyle(Style{Fill: Red, Border: BorderAll(BorderThin, 0), Font: Font{Size: math.NaN()}})
	r := &w.styles
	if len(r.fonts) != 2 || len(r.fills) != 1 || len(r.borders) != 1 || len(r.numFmts) != 2 || len(r.xfs) != 6 {
		t.Errorf("fonts=%d fills=%d borders=%d numFmts=%d xfs=%d", len(r.fonts), len(r.fills), len(r.borders), len(r.numFmts), len(r.xfs))
	}
	for size, want := range map[float64]float64{0: 11, -3: 11, 0.2: 1, 1000: 409, 10.456: 10.46} {
		if got := (Font{Size: size}).normalized().Size; got != want {
			t.Errorf("font size %v normalized to %v, want %v", size, got, want)
		}
	}
}

func TestValueVariants(t *testing.T) {
	w := NewWriter(nil)
	date := w.styles.valueVariant(0, kindDate)
	if date == 0 || w.styles.valueVariant(0, kindDate) != date || w.styles.xfs[date].numFmt != 14 {
		t.Errorf("default date variant = %d", date)
	}
	bold := uint32(w.AddStyle(Style{Font: Font{Bold: true}}))
	boldTime := w.styles.valueVariant(bold, kindDateTime)
	if boldTime == bold || w.styles.valueVariant(bold, kindDateTime) != boldTime {
		t.Error("a style without a number format needs one cached date variant")
	}
	if x := w.styles.xfs[boldTime]; x.numFmt != 22 || x.font != w.styles.xfs[bold].font {
		t.Errorf("variant = %+v", x)
	}
	formatted := uint32(w.AddStyle(Style{NumberFormat: FormatDate}))
	if w.styles.valueVariant(formatted, kindDateTime) != formatted {
		t.Error("a style with a number format must be used as is")
	}
	if w.styles.valueVariant(999, kindDuration) != 999 {
		t.Error("an unknown style must pass through to be reported by the cell write")
	}
}

func TestStylesXML(t *testing.T) {
	parts := book(t, func(w *Writer) {
		w.AddStyle(Style{
			NumberFormat: `0.0 "<kg>"`,
			Font:         Font{Name: "A&B", Size: 9.5, Bold: true, Italic: true, Underline: true, Strike: true, Color: Red},
			Fill:         Yellow,
			Border: Border{
				Left:  BorderLine{Style: BorderThin, Color: Blue},
				Right: BorderLine{Style: BorderDouble},
				Top:   BorderLine{Style: BorderStyle(200)},
			},
			Alignment: Alignment{Horizontal: AlignCenterContinuous, Vertical: VAlignDistributed, WrapText: true, ShrinkToFit: true, Indent: 3, Rotation: 255},
		})
		w.AddStyle(Style{NumberFormat: FormatPercent, Alignment: Alignment{Horizontal: HAlign(99), Vertical: VAlign(99), Indent: 1}})
	})
	contains(t, parts["xl/styles.xml"],
		`<numFmts count="1"><numFmt numFmtId="164" formatCode="0.0 &quot;&lt;kg&gt;&quot;"/></numFmts>`,
		`<fonts count="2">`,
		`<font><b/><i/><strike/><u/><sz val="9.5"/><color rgb="FFFF0000"/><name val="A&amp;B"/><family val="2"/></font>`,
		`<fills count="3">`,
		`<fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/><bgColor indexed="64"/></patternFill></fill>`,
		`<borders count="2">`,
		`<border><left style="thin"><color rgb="FF0000FF"/></left><right style="double"><color indexed="64"/></right><top/><bottom/><diagonal/></border>`,
		`<cellXfs count="3">`,
		`<xf numFmtId="164" fontId="1" fillId="2" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="centerContinuous" vertical="distributed" textRotation="255" wrapText="1" indent="3" shrinkToFit="1"/></xf>`,
		`<xf numFmtId="9" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment indent="1"/></xf>`,
	)
}
