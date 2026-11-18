package tests

import (
	"archive/zip"
	"bytes"
	"io"
	"math"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/xuri/excelize/v2"

	hypaxel "github.com/vensas/hypaxel/go"
)

func build(t *testing.T, write func(w *hypaxel.Writer), opts ...hypaxel.Option) *excelize.File {
	t.Helper()
	f, _ := buildRaw(t, write, opts...)
	return f
}

func buildRaw(t *testing.T, write func(w *hypaxel.Writer), opts ...hypaxel.Option) (*excelize.File, []byte) {
	t.Helper()
	var buf bytes.Buffer
	w := hypaxel.NewWriter(&buf, opts...)
	write(w)
	if err := w.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	data := buf.Bytes()
	f, err := excelize.OpenReader(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("excelize cannot open the workbook: %v", err)
	}
	t.Cleanup(func() { f.Close() })
	return f, data
}

func part(t *testing.T, workbook []byte, name string) string {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(workbook), int64(len(workbook)))
	check(t, err)
	file, err := zr.Open(name)
	check(t, err)
	defer file.Close()
	content, err := io.ReadAll(file)
	check(t, err)
	return string(content)
}

func addSheet(t *testing.T, w *hypaxel.Writer, name string, opts ...hypaxel.SheetOptions) *hypaxel.Sheet {
	t.Helper()
	var o hypaxel.SheetOptions
	if len(opts) > 0 {
		o = opts[0]
	}
	s, err := w.AddSheetOptions(name, o)
	if err != nil {
		t.Fatalf("AddSheet(%q): %v", name, err)
	}
	return s
}

func check(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func cell(t *testing.T, f *excelize.File, sheet, ref string, opts ...excelize.Options) string {
	t.Helper()
	v, err := f.GetCellValue(sheet, ref, opts...)
	check(t, err)
	return v
}

func cellType(t *testing.T, f *excelize.File, sheet, ref string) excelize.CellType {
	t.Helper()
	ct, err := f.GetCellType(sheet, ref)
	check(t, err)
	return ct
}

func styleAt(t *testing.T, f *excelize.File, sheet, ref string) *excelize.Style {
	t.Helper()
	id, err := f.GetCellStyle(sheet, ref)
	check(t, err)
	style, err := f.GetStyle(id)
	check(t, err)
	return style
}

var raw = excelize.Options{RawCellValue: true}

func TestValueTypes(t *testing.T) {
	for _, tc := range []struct {
		name string
		opts []hypaxel.Option
	}{
		{"compact", nil},
		{"cell references", []hypaxel.Option{hypaxel.WithCellReferences(true)}},
		{"shared strings", []hypaxel.Option{hypaxel.WithSharedStrings(true)}},
		{"stored", []hypaxel.Option{hypaxel.WithCompressionLevel(0)}},
		{"level 9", []hypaxel.Option{hypaxel.WithCompressionLevel(9)}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := build(t, func(w *hypaxel.Writer) {
				s := addSheet(t, w, "Values")
				s.WriteString("text")
				s.WriteInt(-42)
				s.WriteUint(math.MaxUint32)
				s.WriteFloat(1234.5678)
				s.WriteBool(true)
				s.WriteBool(false)
				s.WriteFormula("=SUM(B1:D1)")
				s.WriteFloat(math.NaN())
				s.WriteFloat(math.Inf(1))
				check(t, s.EndRow())
				check(t, s.WriteRow(int8(-1), uint16(2), float32(0.1), nil, "after gap", hypaxel.Formula("A1&\"<x>\"")))
				check(t, s.WriteRow(1e300, 1.5e-7, int64(1)<<53, 0.0025))
			}, tc.opts...)

			want := [][]string{
				{"text", "-42", "4294967295", "1234.5678", "1", "0", "", "#NUM!", "#NUM!"},
				{"-1", "2", "0.1", "", "after gap", ""},
				{"1e+300", "1.5e-07", "9007199254740992", "0.0025"},
			}
			rows, err := f.GetRows("Values", raw)
			check(t, err)
			for i, wantRow := range want {
				for j, wantCell := range wantRow {
					got := ""
					if i < len(rows) && j < len(rows[i]) {
						got = rows[i][j]
					}
					if got != wantCell {
						t.Errorf("row %d, column %d: got %q, want %q", i+1, j+1, got, wantCell)
					}
				}
			}
			if got := cell(t, f, "Values", "E2"); got != "after gap" {
				t.Errorf("E2 = %q", got)
			}
			if ct := cellType(t, f, "Values", "E1"); ct != excelize.CellTypeBool {
				t.Errorf("E1 type = %v, want bool", ct)
			}
			if ct := cellType(t, f, "Values", "H1"); ct != excelize.CellTypeError {
				t.Errorf("H1 type = %v, want error", ct)
			}
			formula, err := f.GetCellFormula("Values", "G1")
			check(t, err)
			if formula != "SUM(B1:D1)" {
				t.Errorf("G1 formula = %q", formula)
			}
			formula, err = f.GetCellFormula("Values", "F2")
			check(t, err)
			if formula != `A1&"<x>"` {
				t.Errorf("F2 formula = %q", formula)
			}
		})
	}
}

func TestTextEscaping(t *testing.T) {
	texts := []string{
		"plain ünïcødé 😀 日本語",
		`a&b<c>d"e'f`,
		"  leading and trailing  ",
		"line1\nline2\ttab",
		"bell\x07 and \x1f",
		"literal _x0041_ stays, snake_case too",
		"noncharacters ￾￿ end",
		"",
		strings.Repeat("é", hypaxel.MaxCellLength),
		strings.Repeat("long text ", 2000),
	}
	for _, shared := range []bool{false, true} {
		f := build(t, func(w *hypaxel.Writer) {
			s := addSheet(t, w, "Text")
			for _, text := range texts {
				s.WriteString(text)
				check(t, s.EndRow())
			}
			s.WriteString("bad \xff utf8 \xed\xa0\x80")
			check(t, s.EndRow())
		}, hypaxel.WithSharedStrings(shared))
		for i, want := range texts {
			if got := cell(t, f, "Text", hypaxel.CellRef(i+1, 1)); got != want {
				if len(want) > 60 {
					t.Errorf("shared=%v row %d: long text differs (got %d bytes, want %d)", shared, i+1, len(got), len(want))
				} else {
					t.Errorf("shared=%v row %d: got %q, want %q", shared, i+1, got, want)
				}
			}
		}
		if got, want := cell(t, f, "Text", hypaxel.CellRef(len(texts)+1, 1)), "bad � utf8 ���"; got != want {
			t.Errorf("shared=%v invalid UTF-8: got %q, want %q", shared, got, want)
		}
	}
}

func TestDateSystems(t *testing.T) {
	berlin, err := time.LoadLocation("Europe/Berlin")
	check(t, err)
	moments := []time.Time{
		time.Date(2024, 5, 17, 13, 45, 30, 0, time.UTC),
		time.Date(2024, 5, 17, 13, 45, 30, 0, berlin),
		time.Date(1900, 1, 1, 0, 0, 0, 0, time.UTC),
		time.Date(1900, 2, 28, 12, 0, 0, 0, time.UTC),
		time.Date(1900, 3, 1, 0, 0, 0, 0, time.UTC),
		time.Date(1904, 1, 1, 0, 0, 0, 0, time.UTC),
		time.Date(1969, 12, 31, 23, 59, 59, 0, time.UTC),
		time.Date(9999, 12, 31, 0, 0, 0, 0, time.UTC),
	}
	for _, date1904 := range []bool{false, true} {
		f := build(t, func(w *hypaxel.Writer) {
			custom := w.AddStyle(hypaxel.Style{NumberFormat: hypaxel.FormatDateTime})
			s := addSheet(t, w, "Dates")
			for _, m := range moments {
				s.WriteTime(m, custom)
				s.WriteDate(m)
				s.WriteTime(m)
				check(t, s.EndRow())
			}
			s.WriteTime(time.Time{})
			s.WriteTime(time.Date(1850, 6, 1, 8, 30, 0, 0, time.UTC))
			s.WriteDate(time.Date(1850, 6, 1, 8, 30, 0, 0, time.UTC))
			s.WriteDuration(36*time.Hour + 15*time.Minute + 5*time.Second)
			check(t, s.EndRow())
		}, hypaxel.WithDate1904(date1904))

		props, err := f.GetWorkbookProps()
		check(t, err)
		if got := props.Date1904 != nil && *props.Date1904; got != date1904 {
			t.Errorf("workbook date1904 = %v, want %v", got, date1904)
		}
		for i, m := range moments {
			if date1904 && m.Year() < 1904 {
				if got, want := cell(t, f, "Dates", hypaxel.CellRef(i+1, 1)), m.Format("2006-01-02T15:04:05"); got != want {
					t.Errorf("1904 system, %v: got %q, want ISO text %q", m, got, want)
				}
				continue
			}
			// excelize renders serial 0 of the 1904 system as "1900-01-00"; the raw value is checked below.
			epoch1904 := date1904 && m.Equal(moments[5])
			if got, want := cell(t, f, "Dates", hypaxel.CellRef(i+1, 1)), m.Format("2006-01-02 15:04:05"); got != want && !epoch1904 {
				t.Errorf("date1904=%v %v: got %q, want %q", date1904, m, got, want)
			}
			serial, err := f.GetCellValue("Dates", hypaxel.CellRef(i+1, 2), raw)
			check(t, err)
			if strings.Contains(serial, ".") {
				t.Errorf("WriteDate(%v) kept a time of day: %s", m, serial)
			}
			if epoch1904 && serial != "0" {
				t.Errorf("1904-01-01 in the 1904 system has serial %s, want 0", serial)
			}
		}
		if id := styleAt(t, f, "Dates", "B1").NumFmt; id != 14 {
			t.Errorf("unstyled date uses number format %d, want 14", id)
		}
		if id := styleAt(t, f, "Dates", "C1").NumFmt; id != 22 {
			t.Errorf("unstyled time uses number format %d, want 22", id)
		}
		last := len(moments) + 1
		if got := cell(t, f, "Dates", hypaxel.CellRef(last, 1)); got != "" {
			t.Errorf("zero time: got %q, want a blank cell", got)
		}
		if got := cell(t, f, "Dates", hypaxel.CellRef(last, 2)); got != "1850-06-01T08:30:00" {
			t.Errorf("date before the epoch: got %q", got)
		}
		if got := cell(t, f, "Dates", hypaxel.CellRef(last, 3)); got != "1850-06-01" {
			t.Errorf("date-only before the epoch: got %q", got)
		}
		// excelize shifts [h]:mm:ss values by the 1904 epoch, so the rendering is checked in the 1900 system only.
		if got := cell(t, f, "Dates", hypaxel.CellRef(last, 4)); got != "36:15:05" && !date1904 {
			t.Errorf("duration: got %q, want 36:15:05", got)
		}
		if got := cell(t, f, "Dates", hypaxel.CellRef(last, 4), raw); !strings.HasPrefix(got, "1.5104745370") {
			t.Errorf("duration serial: got %q", got)
		}
	}
}

func TestStyles(t *testing.T) {
	var header, money, boxed, same, rotated hypaxel.StyleID
	f, data := buildRaw(t, func(w *hypaxel.Writer) {
		blue, err := hypaxel.ParseColor("#305496")
		check(t, err)
		headerStyle := hypaxel.Style{
			Font:      hypaxel.Font{Name: "Arial", Size: 14, Bold: true, Italic: true, Underline: true, Strike: true, Color: hypaxel.White},
			Fill:      blue,
			Alignment: hypaxel.Alignment{Horizontal: hypaxel.AlignCenter, Vertical: hypaxel.VAlignTop, WrapText: true},
		}
		header = w.AddStyle(headerStyle)
		same = w.AddStyle(headerStyle)
		money = w.AddStyle(hypaxel.Style{NumberFormat: "#,##0.00 \"EUR\""})
		boxed = w.AddStyle(hypaxel.Style{Border: hypaxel.Border{
			Left:   hypaxel.BorderLine{Style: hypaxel.BorderThin, Color: hypaxel.Red},
			Right:  hypaxel.BorderLine{Style: hypaxel.BorderDouble},
			Top:    hypaxel.BorderLine{Style: hypaxel.BorderMediumDashDot, Color: hypaxel.Blue},
			Bottom: hypaxel.BorderLine{Style: hypaxel.BorderSlantDashDot},
		}})
		rotated = w.AddStyle(hypaxel.Style{Alignment: hypaxel.Alignment{Horizontal: hypaxel.AlignRight, Indent: 2, Rotation: 45, ShrinkToFit: true}})
		s := addSheet(t, w, "Styles")
		check(t, s.WriteRowStyled(header, "Name", hypaxel.Styled("Amount", money)))
		s.WriteFloat(1234.5, money)
		s.WriteBlank(boxed)
		s.WriteString("tilted", rotated)
		s.WriteTime(time.Date(2024, 1, 2, 0, 0, 0, 0, time.UTC), header)
		check(t, s.EndRow())
	})
	if header != same {
		t.Errorf("identical styles got different ids %d and %d", header, same)
	}

	h := styleAt(t, f, "Styles", "A1")
	if h.Font == nil || !h.Font.Bold || !h.Font.Italic || !h.Font.Strike || h.Font.Underline != "single" ||
		h.Font.Family != "Arial" || h.Font.Size != 14 || h.Font.Color != "FFFFFF" {
		t.Errorf("header font = %+v", h.Font)
	}
	if h.Fill.Type != "pattern" || h.Fill.Pattern != 1 {
		t.Errorf("header fill = %+v", h.Fill)
	}
	// excelize resolves fill and border colors only for workbooks with a theme part, which
	// Hypaxel does not write, so those colors are checked in the styles part itself.
	styles := part(t, data, "xl/styles.xml")
	for _, want := range []string{
		`<patternFill patternType="solid"><fgColor rgb="FF305496"/>`,
		`<left style="thin"><color rgb="FFFF0000"/></left>`,
		`<top style="mediumDashDot"><color rgb="FF0000FF"/></top>`,
	} {
		if !strings.Contains(styles, want) {
			t.Errorf("styles.xml lacks %s", want)
		}
	}
	if h.Alignment == nil || h.Alignment.Horizontal != "center" || h.Alignment.Vertical != "top" || !h.Alignment.WrapText {
		t.Errorf("header alignment = %+v", h.Alignment)
	}

	m := styleAt(t, f, "Styles", "B1")
	if m.CustomNumFmt == nil || *m.CustomNumFmt != "#,##0.00 \"EUR\"" {
		t.Errorf("money number format = %v", m.CustomNumFmt)
	}
	if got := cell(t, f, "Styles", "A2"); got != "1,234.50 EUR" {
		t.Errorf("formatted money = %q", got)
	}

	b := styleAt(t, f, "Styles", "B2")
	borders := map[string]excelize.Border{}
	for _, border := range b.Border {
		borders[border.Type] = border
	}
	wantBorders := map[string]excelize.Border{
		"left":   {Type: "left", Style: 1},
		"right":  {Type: "right", Style: 6},
		"top":    {Type: "top", Style: 10},
		"bottom": {Type: "bottom", Style: 13},
	}
	for edge, want := range wantBorders {
		got := borders[edge]
		if got.Style != want.Style {
			t.Errorf("%s border = %+v, want %+v", edge, got, want)
		}
	}

	r := styleAt(t, f, "Styles", "C2")
	if r.Alignment == nil || r.Alignment.Horizontal != "right" || r.Alignment.Indent != 2 || r.Alignment.TextRotation != 45 || !r.Alignment.ShrinkToFit {
		t.Errorf("rotated alignment = %+v", r.Alignment)
	}

	d := styleAt(t, f, "Styles", "D2")
	if d.NumFmt != 22 || d.Font == nil || !d.Font.Bold {
		t.Errorf("a styled time must keep its style and gain a date format: %+v", d)
	}
}

func TestAllBorderStyles(t *testing.T) {
	styles := []hypaxel.BorderStyle{
		hypaxel.BorderThin, hypaxel.BorderMedium, hypaxel.BorderDashed, hypaxel.BorderDotted, hypaxel.BorderThick,
		hypaxel.BorderDouble, hypaxel.BorderHair, hypaxel.BorderMediumDashed, hypaxel.BorderDashDot,
		hypaxel.BorderMediumDashDot, hypaxel.BorderDashDotDot, hypaxel.BorderMediumDashDotDot, hypaxel.BorderSlantDashDot,
	}
	// excelize numbers the same OOXML line styles in its own order.
	excelizeIndex := []int{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}
	f := build(t, func(w *hypaxel.Writer) {
		s := addSheet(t, w, "Borders")
		for _, style := range styles {
			s.WriteBlank(w.AddStyle(hypaxel.Style{Border: hypaxel.BorderAll(style, hypaxel.Black)}))
		}
		check(t, s.EndRow())
	})
	for i := range styles {
		style := styleAt(t, f, "Borders", hypaxel.CellRef(1, i+1))
		if len(style.Border) == 0 {
			t.Errorf("column %d: no border read back", i+1)
			continue
		}
		for _, border := range style.Border {
			if border.Type != "diagonalDown" && border.Type != "diagonalUp" && border.Style != excelizeIndex[i] {
				t.Errorf("column %d %s border: style %d, want %d", i+1, border.Type, border.Style, excelizeIndex[i])
			}
		}
	}
}

func TestWorksheetLayout(t *testing.T) {
	f := build(t, func(w *hypaxel.Writer) {
		wide := w.AddStyle(hypaxel.Style{Font: hypaxel.Font{Italic: true}})
		tall := w.AddStyle(hypaxel.Style{Fill: hypaxel.Yellow})
		orange, err := hypaxel.ParseColor("FFA500")
		check(t, err)

		s := addSheet(t, w, "Layout", hypaxel.SheetOptions{
			Columns:       []hypaxel.Column{{Width: 30}, {}, {Width: 12.5, Style: wide}, {Hidden: true}},
			FrozenRows:    1,
			FrozenColumns: 2,
			HideGridLines: true,
			TabColor:      orange,
		})
		check(t, s.WriteStrings([]string{"A", "B", "C", "D"}))
		check(t, s.BeginRow(hypaxel.RowOptions{Height: 42, Style: tall}))
		s.WriteString("tall")
		check(t, s.EndRow())
		s.SkipRows(2)
		s.SkipCells(2)
		s.WriteString("C5")
		check(t, s.EndRow())
		check(t, s.BeginRow(hypaxel.RowOptions{Index: 10}))
		s.WriteString("A10")
		check(t, s.MergeCells(1, 1, 1, 2))
		check(t, s.MergeCells(5, 3, 7, 5))
		check(t, s.AutoFilter(1, 1, 10, 4))

		rows := addSheet(t, w, "Rows only", hypaxel.SheetOptions{FrozenRows: 2})
		check(t, rows.WriteRow("x"))
		cols := addSheet(t, w, "Cols only", hypaxel.SheetOptions{FrozenColumns: 3})
		check(t, cols.WriteRow("x"))
		hidden := addSheet(t, w, "Hidden", hypaxel.SheetOptions{Hidden: true})
		check(t, hidden.WriteRow("secret"))
		check(t, hidden.AutoFilter(1, 1, 1, 1))
	})

	if got := f.GetSheetList(); !slices.Equal(got, []string{"Layout", "Rows only", "Cols only", "Hidden"}) {
		t.Errorf("sheets = %v", got)
	}
	for ref, want := range map[string]string{"A1": "A", "D1": "D", "A2": "tall", "C5": "C5", "A10": "A10", "A3": "", "A5": ""} {
		if got := cell(t, f, "Layout", ref); got != want {
			t.Errorf("%s = %q, want %q", ref, got, want)
		}
	}
	for col, want := range map[string]float64{"A": 30, "C": 12.5} {
		got, err := f.GetColWidth("Layout", col)
		check(t, err)
		if got != want {
			t.Errorf("width of %s = %v, want %v", col, got, want)
		}
	}
	visible, err := f.GetColVisible("Layout", "D")
	check(t, err)
	if visible {
		t.Error("column D should be hidden")
	}
	colStyle, err := f.GetColStyle("Layout", "C")
	check(t, err)
	if style, err := f.GetStyle(colStyle); err != nil || style.Font == nil || !style.Font.Italic {
		t.Errorf("column C style = %+v, %v", style, err)
	}
	height, err := f.GetRowHeight("Layout", 2)
	check(t, err)
	if height != 42 {
		t.Errorf("row 2 height = %v, want 42", height)
	}

	panes, err := f.GetPanes("Layout")
	check(t, err)
	if !panes.Freeze || panes.XSplit != 2 || panes.YSplit != 1 || panes.TopLeftCell != "C2" || panes.ActivePane != "bottomRight" {
		t.Errorf("panes = %+v", panes)
	}
	panes, err = f.GetPanes("Rows only")
	check(t, err)
	if !panes.Freeze || panes.XSplit != 0 || panes.YSplit != 2 || panes.TopLeftCell != "A3" || panes.ActivePane != "bottomLeft" {
		t.Errorf("row panes = %+v", panes)
	}
	panes, err = f.GetPanes("Cols only")
	check(t, err)
	if !panes.Freeze || panes.XSplit != 3 || panes.YSplit != 0 || panes.TopLeftCell != "D1" || panes.ActivePane != "topRight" {
		t.Errorf("column panes = %+v", panes)
	}

	merged, err := f.GetMergeCells("Layout")
	check(t, err)
	var ranges []string
	for _, m := range merged {
		ranges = append(ranges, m.GetStartAxis()+":"+m.GetEndAxis())
	}
	if !slices.Equal(ranges, []string{"A1:B1", "C5:E7"}) {
		t.Errorf("merged ranges = %v", ranges)
	}

	view, err := f.GetSheetView("Layout", 0)
	check(t, err)
	if view.ShowGridLines == nil || *view.ShowGridLines {
		t.Error("grid lines should be hidden")
	}
	props, err := f.GetSheetProps("Layout")
	check(t, err)
	if props.TabColorRGB == nil || !strings.EqualFold(*props.TabColorRGB, "FFFFA500") {
		t.Errorf("tab color = %v", props.TabColorRGB)
	}

	for sheet, want := range map[string]bool{"Layout": true, "Hidden": false} {
		visible, err := f.GetSheetVisible(sheet)
		check(t, err)
		if visible != want {
			t.Errorf("sheet %q visible = %v, want %v", sheet, visible, want)
		}
	}

	filters := map[string]string{}
	for _, name := range f.GetDefinedName() {
		if name.Name == "_xlnm._FilterDatabase" {
			filters[name.Scope] = name.RefersTo
		}
	}
	if filters["Layout"] != "'Layout'!$A$1:$D$10" || filters["Hidden"] != "'Hidden'!$A$1:$A$1" {
		t.Errorf("auto filter names = %v", filters)
	}
}

type account struct {
	ID      int           `xlsx:"Account #,width=12"`
	Owner   string        `xlsx:",width=24"`
	Balance float64       `xlsx:"Balance,format=#,##0.00"`
	Opened  time.Time     `xlsx:"Opened,date"`
	Seen    *time.Time    `xlsx:"Last seen"`
	Idle    time.Duration `xlsx:"Idle"`
	Active  bool
	Secret  string `xlsx:"-"`
	Note    *string
}

func TestStructRows(t *testing.T) {
	seen := time.Date(2024, 3, 4, 5, 6, 7, 0, time.UTC)
	note := "vip"
	accounts := []account{
		{ID: 1, Owner: "Ada", Balance: 1234.5, Opened: time.Date(2020, 1, 2, 15, 0, 0, 0, time.UTC), Seen: &seen, Idle: 90 * time.Minute, Active: true, Secret: "x", Note: &note},
		{ID: 2, Owner: "Linus", Balance: -0.5},
	}
	f := build(t, func(w *hypaxel.Writer) {
		check(t, hypaxel.WriteSheet(w, "Accounts", accounts))
		check(t, hypaxel.WriteSheetSeq(w, "Seq", slices.Values(accounts)))
		check(t, hypaxel.WriteSheet(w, "Plain", [][]any{{1, "a"}, {2, "b", true}}))
	})
	want := [][]string{
		{"Account #", "Owner", "Balance", "Opened", "Last seen", "Idle", "Active", "Note"},
		{"1", "Ada", "1,234.50", "01-02-20", "3/4/24 05:06", "1:30:00", "TRUE", "vip"},
		{"2", "Linus", "-0.50", "", "", "0:00:00", "FALSE"},
	}
	for _, sheet := range []string{"Accounts", "Seq"} {
		rows, err := f.GetRows(sheet)
		check(t, err)
		if len(rows) != len(want) {
			t.Fatalf("%s: %d rows, want %d: %v", sheet, len(rows), len(want), rows)
		}
		for i := range want {
			if !slices.Equal(rows[i], want[i]) {
				t.Errorf("%s row %d = %q, want %q", sheet, i+1, rows[i], want[i])
			}
		}
		panes, err := f.GetPanes(sheet)
		check(t, err)
		if !panes.Freeze || panes.YSplit != 1 {
			t.Errorf("%s: header row is not frozen: %+v", sheet, panes)
		}
		if width, _ := f.GetColWidth(sheet, "B"); width != 24 {
			t.Errorf("%s: width of B = %v, want 24", sheet, width)
		}
		if style := styleAt(t, f, sheet, "A1"); style.Font == nil || !style.Font.Bold {
			t.Errorf("%s: header is not bold", sheet)
		}
	}
	rows, err := f.GetRows("Plain")
	check(t, err)
	if len(rows) != 2 || !slices.Equal(rows[0], []string{"1", "a"}) || !slices.Equal(rows[1], []string{"2", "b", "TRUE"}) {
		t.Errorf("plain rows = %q", rows)
	}
}

func TestWorkloadMatchesAcrossWriters(t *testing.T) {
	data := Data(2500)
	rows := slices.Values(data)
	writers := map[string]func(*bytes.Buffer) error{
		"typed":           func(b *bytes.Buffer) error { return HypaxelTyped(b, rows) },
		"row":             func(b *bytes.Buffer) error { return HypaxelRow(b, rows) },
		"structs":         func(b *bytes.Buffer) error { return HypaxelStructs(b, rows) },
		"shared":          func(b *bytes.Buffer) error { return HypaxelTyped(b, rows, hypaxel.WithSharedStrings(true)) },
		"excelize stream": func(b *bytes.Buffer) error { return ExcelizeStream(b, rows) },
		"excelize memory": func(b *bytes.Buffer) error { return ExcelizeInMemory(b, rows) },
	}
	read := func(name string) [][]string {
		var buf bytes.Buffer
		check(t, writers[name](&buf))
		f, err := excelize.OpenReader(&buf)
		check(t, err)
		defer f.Close()
		got, err := f.GetRows(f.GetSheetList()[0], raw)
		check(t, err)
		if name == "structs" {
			got = got[1:]
		}
		return got
	}
	want := read("typed")
	if len(want) != len(data) || len(want[0]) != 10 {
		t.Fatalf("typed: %d rows of %d cells", len(want), len(want[0]))
	}
	if !slices.Equal(want[7], []string{"7", "Customer 7", "Software", "8.75", "0.07", "45292.0048611111", "0", "21", "Unique comment #7", "1"}) {
		t.Errorf("row 8 = %q", want[7])
	}
	for name := range writers {
		got := read(name)
		if len(got) != len(want) {
			t.Errorf("%s: %d rows, want %d", name, len(got), len(want))
			continue
		}
		for i := range want {
			if strings.HasPrefix(name, "excelize") {
				// excelize formats floats and date serials with its own precision; compare the rest.
				for _, j := range []int{0, 1, 2, 6, 7, 8} {
					if got[i][j] != want[i][j] {
						t.Fatalf("%s row %d column %d = %q, want %q", name, i+1, j+1, got[i][j], want[i][j])
					}
				}
			} else if !slices.Equal(got[i], want[i]) {
				t.Fatalf("%s row %d = %q, want %q", name, i+1, got[i], want[i])
			}
		}
	}
}
