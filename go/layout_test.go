package hypaxel

import (
	"bytes"
	"math"
	"strconv"
	"strings"
	"testing"
	"time"
)

type colWidth struct {
	index int
	width float64
}

func colWidths(t *testing.T, sheetXML string) []colWidth {
	t.Helper()
	attr := func(col, name string) string {
		_, rest, ok := strings.Cut(col, name+`="`)
		if !ok {
			t.Fatalf("no %s in <col %s", name, col)
		}
		value, _, _ := strings.Cut(rest, `"`)
		return value
	}
	var cols []colWidth
	for _, col := range strings.Split(sheetXML, "<col ")[1:] {
		index, err := strconv.Atoi(attr(col, "min"))
		noErr(t, err)
		width, err := strconv.ParseFloat(attr(col, "width"), 64)
		noErr(t, err)
		cols = append(cols, colWidth{index, width})
	}
	return cols
}

func widthOf(cols []colWidth, index int) (float64, bool) {
	for _, c := range cols {
		if c.index == index {
			return c.width, true
		}
	}
	return 0, false
}

func mustSheetOptions(t *testing.T, w *Writer, name string, opts SheetOptions) *Sheet {
	t.Helper()
	s, err := w.AddSheetOptions(name, opts)
	if err != nil {
		t.Fatalf("AddSheetOptions(%q): %v", name, err)
	}
	return s
}

func TestRowOutlineLevels(t *testing.T) {
	parts := book(t, func(w *Writer) {
		s := mustSheetOptions(t, w, "Tree", SheetOptions{RowOutlineLevels: 2, OutlineSummaryAbove: true})
		noErr(t, s.WriteRow("North", 300))
		noErr(t, s.BeginRow(RowOptions{OutlineLevel: 1}))
		s.WriteString("Store A")
		s.WriteInt(120)
		noErr(t, s.EndRow())
		noErr(t, s.BeginRow(RowOptions{OutlineLevel: 2, Hidden: true}))
		s.WriteString("Till 1")
		s.WriteInt(60)
		noErr(t, s.EndRow())
		noErr(t, s.BeginRow(RowOptions{OutlineLevel: 1, Collapsed: true, Height: 20}))
		s.WriteString("Store B")
		noErr(t, s.EndRow())
		noErr(t, s.WriteRow("South", 1))
	})
	contains(t, parts["xl/worksheets/sheet1.xml"],
		`<sheetPr><outlinePr summaryBelow="0"/></sheetPr>`,
		`<sheetFormatPr defaultRowHeight="15" outlineLevelRow="2"/>`,
		`<row r="1">`,
		`<row r="2" outlineLevel="1">`,
		`<row r="3" hidden="1" outlineLevel="2"><c t="inlineStr"><is><t>Till 1</t>`,
		`<row r="4" ht="20" customHeight="1" outlineLevel="1" collapsed="1">`,
		`<row r="5"><c t="inlineStr"><is><t>South</t></is></c><c><v>1</v></c></row>`,
	)
}

func TestTabColorAndSummaryShareSheetPr(t *testing.T) {
	parts := book(t, func(w *Writer) {
		mustSheetOptions(t, w, "S", SheetOptions{TabColor: Blue, OutlineSummaryAbove: true})
	})
	contains(t, parts["xl/worksheets/sheet1.xml"], `<sheetPr><tabColor rgb="FF0000FF"/><outlinePr summaryBelow="0"/></sheetPr>`)
}

func TestOutlineLevelsMustBeDeclared(t *testing.T) {
	w := NewWriter(&bytes.Buffer{})
	s := mustSheetOptions(t, w, "S", SheetOptions{RowOutlineLevels: 1})
	noErr(t, s.BeginRow(RowOptions{OutlineLevel: 1}))
	err := s.BeginRow(RowOptions{OutlineLevel: 2})
	wantErr(t, err, ErrInvalidArgument)
	if !strings.Contains(err.Error(), "SheetOptions.RowOutlineLevels") {
		t.Errorf("error should name the option: %v", err)
	}
	wantErr(t, s.BeginRow(RowOptions{OutlineLevel: 8}), ErrInvalidArgument)
	wantErr(t, s.BeginRow(RowOptions{OutlineLevel: -1}), ErrInvalidArgument)
	_, err = w.AddSheetOptions("T", SheetOptions{RowOutlineLevels: 8})
	wantErr(t, err, ErrInvalidArgument)
	noErr(t, w.Close())
}

func TestTables(t *testing.T) {
	parts := book(t, func(w *Writer) {
		s := mustSheet(t, w, "Orders")
		noErr(t, s.WriteRow("Product", "Units", "Line\nbreak"))
		for i := range 5 {
			noErr(t, s.WriteRow("Widget", i, 2.5))
		}
		noErr(t, s.AddTable(Table{FirstRow: 1, FirstColumn: 1, LastRow: s.Row(), LastColumn: 3,
			Name: "Orders", Columns: []string{"Product", "Units", "Line\nbreak"}}))
		noErr(t, s.AddTable(Table{FirstRow: 10, FirstColumn: 1, LastRow: 12, LastColumn: 2, NoHeaderRow: true, Style: TableStyleNone}))

		s = mustSheet(t, w, "Second")
		noErr(t, s.WriteRow("A & B"))
		noErr(t, s.WriteRow(1))
		noErr(t, s.AddTable(Table{FirstRow: 1, FirstColumn: 1, LastRow: 2, LastColumn: 1,
			Columns: []string{"A & B"}, Style: TableStyleLight(1), ColumnStripes: true}))
	})

	for _, name := range []string{"xl/tables/table1.xml", "xl/tables/table2.xml", "xl/tables/table3.xml",
		"xl/worksheets/_rels/sheet1.xml.rels", "xl/worksheets/_rels/sheet2.xml.rels"} {
		if _, ok := parts[name]; !ok {
			t.Errorf("missing part %s", name)
		}
	}
	contains(t, parts["xl/tables/table1.xml"],
		`id="1" name="Orders" displayName="Orders" ref="A1:C6" totalsRowShown="0"`,
		`<autoFilter ref="A1:C6"/>`,
		`<tableColumn id="3" name="Line_x000A_break"/>`,
		`name="TableStyleMedium2" showFirstColumn="0" showLastColumn="0" showRowStripes="1" showColumnStripes="0"`,
	)
	t2 := parts["xl/tables/table2.xml"]
	contains(t, t2, `name="Table2"`, `headerRowCount="0"`, `<tableColumn id="1" name="Column1"/>`)
	if strings.Contains(t2, "autoFilter") || strings.Contains(t2, "TableStyle") {
		t.Errorf("headerless unstyled table: %s", t2)
	}
	contains(t, parts["xl/tables/table3.xml"], `name="Table3"`, `<tableColumn id="1" name="A &amp; B"/>`,
		`name="TableStyleLight1"`, `showColumnStripes="1"`)

	if sheet := parts["xl/worksheets/sheet1.xml"]; !strings.HasSuffix(sheet,
		`<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/><tableParts count="2"><tablePart r:id="rId1"/><tablePart r:id="rId2"/></tableParts></worksheet>`) {
		t.Errorf("tableParts must follow pageMargins: %s", sheet)
	}
	contains(t, parts["xl/worksheets/_rels/sheet2.xml.rels"],
		`Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table3.xml"`)
	if n := strings.Count(parts["[Content_Types].xml"], "spreadsheetml.table+xml"); n != 3 {
		t.Errorf("%d table content types, want 3", n)
	}
	contains(t, parts["[Content_Types].xml"], `<Override PartName="/xl/tables/table3.xml"`)
}

func TestTableValidation(t *testing.T) {
	w := NewWriter(&bytes.Buffer{})
	s := mustSheet(t, w, "S")
	tbl := func(lastRow, lastColumn int, columns ...string) Table {
		return Table{FirstRow: 1, FirstColumn: 1, LastRow: lastRow, LastColumn: lastColumn, Columns: columns}
	}
	named := func(t Table, name string) Table { t.Name = name; return t }
	styled := func(t Table, style TableStyle) Table { t.Style = style; return t }

	wantErr(t, s.AddTable(tbl(3, 2)), ErrInvalidArgument)                           // header names are required
	wantErr(t, s.AddTable(tbl(1, 1, "a")), ErrInvalidArgument)                      // needs a data row
	wantErr(t, s.AddTable(tbl(3, 2, "a")), ErrInvalidArgument)                      // column count
	wantErr(t, s.AddTable(tbl(3, 2, "a", "A")), ErrInvalidArgument)                 // duplicate column
	wantErr(t, s.AddTable(tbl(3, 2, "a", "")), ErrInvalidArgument)                  // empty column
	wantErr(t, s.AddTable(tbl(3, 1, strings.Repeat("x", 256))), ErrInvalidArgument) // long column
	wantErr(t, s.AddTable(tbl(0, 1, "a")), ErrInvalidArgument)                      // range
	wantErr(t, s.AddTable(named(tbl(3, 1, "a"), "A1")), ErrInvalidTableName)
	wantErr(t, s.AddTable(named(tbl(3, 1, "a"), "my table")), ErrInvalidTableName)
	wantErr(t, s.AddTable(styled(tbl(3, 1, "a"), TableStyleMedium(29))), ErrInvalidArgument)
	wantErr(t, s.AddTable(styled(tbl(3, 1, "a"), TableStyle(0x0900))), ErrInvalidArgument)

	noErr(t, s.AddTable(named(tbl(3, 1, "a"), "Data")))
	wantErr(t, s.AddTable(Table{FirstRow: 3, FirstColumn: 1, LastRow: 5, LastColumn: 1, Columns: []string{"b"}}), ErrOverlappingRange)
	wantErr(t, s.AddTable(Table{FirstRow: 10, FirstColumn: 1, LastRow: 12, LastColumn: 1, Columns: []string{"b"}, Name: "DATA"}), ErrDuplicateTableName)
	wantErr(t, s.MergeCells(2, 1, 2, 3), ErrOverlappingRange)
	wantErr(t, s.AutoFilter(1, 1, 9, 1), ErrOverlappingRange)

	noErr(t, s.MergeCells(20, 1, 20, 2))
	wantErr(t, s.AddTable(Table{FirstRow: 19, FirstColumn: 1, LastRow: 21, LastColumn: 1, Columns: []string{"c"}}), ErrOverlappingRange)
	noErr(t, s.AutoFilter(30, 1, 40, 2))
	wantErr(t, s.AddTable(Table{FirstRow: 35, FirstColumn: 2, LastRow: 36, LastColumn: 2, Columns: []string{"d"}}), ErrOverlappingRange)

	// Names are unique across worksheets.
	s = mustSheet(t, w, "T")
	wantErr(t, s.AddTable(named(tbl(3, 1, "a"), "data")), ErrDuplicateTableName)
	noErr(t, s.AddTable(tbl(3, 1, "a")))
	noErr(t, w.Close())
}

func TestTableNames(t *testing.T) {
	for _, ok := range []string{"Orders", "_t", "Sales.2024", "Résumé", "R1C1x", "XFE1", "ABCD1", `\name`} {
		if !validTableName(ok) {
			t.Errorf("%q should be valid", ok)
		}
	}
	for _, bad := range []string{"", "1abc", "A1", "xfd1048576", "R", "C", "r1c1", "RC", "R12", "C3", "has space", "a-b", "a:b", ".a", strings.Repeat("a", 256)} {
		if validTableName(bad) {
			t.Errorf("%q should be invalid", bad)
		}
	}
}

func TestOverlaps(t *testing.T) {
	r := func(r1, c1, r2, c2 uint32) cellRange { return cellRange{r1, c1, r2, c2} }
	if !overlaps(r(1, 1, 5, 5), r(5, 5, 6, 6)) {
		t.Error("corner cell is shared")
	}
	if overlaps(r(1, 1, 5, 5), r(6, 1, 9, 5)) || overlaps(r(1, 1, 5, 5), r(1, 6, 5, 9)) {
		t.Error("adjacent ranges do not overlap")
	}
}

func TestTableStyleString(t *testing.T) {
	for style, want := range map[TableStyle]string{
		0: "TableStyleMedium2", TableStyleNone: "None", TableStyleLight(21): "TableStyleLight21",
		TableStyleMedium(28): "TableStyleMedium28", TableStyleDark(1): "TableStyleDark1",
	} {
		if got := style.String(); got != want {
			t.Errorf("got %s, want %s", got, want)
		}
	}
	if TableStyleDark(12).valid() || TableStyleLight(0).valid() {
		t.Error("out-of-range styles must be invalid")
	}
}

func TestAutoFitMeasuresTheSample(t *testing.T) {
	parts := book(t, func(w *Writer) {
		header := w.AddStyle(Style{Font: Font{Bold: true}})
		big := w.AddStyle(Style{Font: Font{Size: 22}})
		money := w.AddStyle(Style{NumberFormat: FormatThousands2})
		s := mustSheetOptions(t, w, "Fit", SheetOptions{
			AutoFit: &AutoFit{},
			Columns: []Column{{}, {Width: 30}, {}, {}, {}, {Hidden: true}},
		})
		noErr(t, s.WriteRow(Styled("Name", header), "fixed", Styled(1_234_567.0, money), true, Styled("x", big)))
		noErr(t, s.WriteRow("A much longer product name", "x", Styled(1.0, money), false, Styled("x", big)))
		noErr(t, s.WriteRow("short\nWith a very long second line in a wrapped cell"))
		s.SkipCells(7)
		s.WriteInt(42)
		s.WriteFormula("REPT(\"x\",500)")
		noErr(t, s.EndRow())
	})
	sheet := parts["xl/worksheets/sheet1.xml"]
	cols := colWidths(t, sheet)
	width := func(c int) float64 {
		w, ok := widthOf(cols, c)
		if !ok {
			t.Fatalf("no <col> for column %d in %s", c, sheet)
		}
		return w
	}
	if w := width(1); w < 40 || w >= 50 {
		t.Errorf("longest line of column A: got %v", w)
	}
	if w := width(2); w != 30 {
		t.Errorf("explicit widths win: got %v", w)
	}
	if w := width(3); w < 9 || w >= 13 {
		t.Errorf("1,234,567.00 fits: got %v", w)
	}
	if w := width(4); w < 5 || w >= 7 {
		t.Errorf("FALSE: got %v", w)
	}
	if w := width(5); w <= 2 {
		t.Errorf("larger font is wider: got %v", w)
	}
	if w := width(6); w != 9.140625 {
		t.Errorf("hidden column without content keeps the default width: got %v", w)
	}
	contains(t, sheet, `<col min="6" max="6" width="9.140625" hidden="1"/>`)
	if _, ok := widthOf(cols, 7); ok {
		t.Error("skipped empty columns get no entry")
	}
	if w := width(8); w >= 3 {
		t.Errorf("42: got %v", w)
	}
	if _, ok := widthOf(cols, 9); ok {
		t.Error("formulas are not measured")
	}
	if strings.Index(sheet, "<cols>") > strings.Index(sheet, "<sheetData>") {
		t.Error("<cols> must precede <sheetData>")
	}
	contains(t, sheetData(t, parts, 1), `<row r="4"><c r="H4"><v>42</v></c>`)
}

func TestAutoFitMeasuresDatesWithTheirFormat(t *testing.T) {
	when := time.Date(2026, 9, 30, 12, 0, 0, 0, time.UTC)
	parts := book(t, func(w *Writer) {
		long := w.AddStyle(Style{NumberFormat: "dddd, d mmmm yyyy"})
		s := mustSheetOptions(t, w, "S", SheetOptions{AutoFit: &AutoFit{}})
		s.WriteDate(when)
		s.WriteTime(when, long)
		s.WriteDuration(90 * time.Minute)
		noErr(t, s.EndRow())
	})
	cols := colWidths(t, parts["xl/worksheets/sheet1.xml"])
	want := []float64{
		math.Ceil((float64(textPx("12/31/2026"))+2)/7*100) / 100,
		math.Ceil((float64(textPx("Wednesday, 31 September 2026"))+2)/7*100) / 100,
		math.Ceil((float64(textPx("100:00:00"))+2)/7*100) / 100,
	}
	if len(cols) != len(want) {
		t.Fatalf("got %v", cols)
	}
	for i, w := range want {
		if cols[i].width != w {
			t.Errorf("column %d: got %v, want %v", i+1, cols[i].width, w)
		}
	}
}

type countingWriter struct {
	bytes.Buffer
	writes int
}

func (c *countingWriter) Write(p []byte) (int, error) {
	c.writes++
	return c.Buffer.Write(p)
}

func TestAutoFitStopsAfterTheSampleAndKeepsStreaming(t *testing.T) {
	var out countingWriter
	w := NewWriter(&out, WithFlushThreshold(4096))
	s := mustSheetOptions(t, w, "S", SheetOptions{AutoFit: &AutoFit{SampleRows: 10, MaxWidth: 20}})
	for i := range 9 {
		noErr(t, s.WriteRow("row "+strconv.Itoa(i), i))
	}
	noErr(t, w.Flush())
	if out.Len() >= 100 {
		t.Errorf("%d bytes written; only the ZIP entry header is written while the sample is held back", out.Len())
	}
	noErr(t, s.WriteRow("row 9", 9))
	noErr(t, s.WriteRow("a value far wider than anything in the sample, which is not measured", 1))
	for i := range 20_000 {
		noErr(t, s.WriteRow(i, float64(i)*1.5))
	}
	noErr(t, w.Close())
	if out.writes <= 3 {
		t.Errorf("%d writes; output must still be flushed incrementally after the sample", out.writes)
	}
	cols := colWidths(t, unzip(t, out.Bytes())["xl/worksheets/sheet1.xml"])
	if len(cols) != 2 || cols[0].width >= 10 {
		t.Errorf("rows after the sample are not measured: %v", cols)
	}
}

func TestAutoFitStopsAtTheSampleSizeLimit(t *testing.T) {
	var out countingWriter
	w := NewWriter(&out)
	s := mustSheetOptions(t, w, "S", SheetOptions{AutoFit: &AutoFit{SampleRows: 1 << 30}})
	text := strings.Repeat("x", 1000)
	for range 9000 {
		noErr(t, s.WriteRow(text))
	}
	if w.fit != nil {
		t.Fatal("the sample must end at 8 MiB of XML")
	}
	if cap(w.xml) > 2*(xmlBufferSize+4096) {
		t.Errorf("the XML buffer keeps %d bytes after the sample", cap(w.xml))
	}
	noErr(t, w.Close())
	if cols := colWidths(t, unzip(t, out.Bytes())["xl/worksheets/sheet1.xml"]); len(cols) != 1 || cols[0].width != 255 {
		t.Errorf("got %v", cols)
	}
}

func TestAutoFitRespectsMaxWidthAndShortSheets(t *testing.T) {
	parts := book(t, func(w *Writer) {
		s := mustSheetOptions(t, w, "S", SheetOptions{AutoFit: &AutoFit{MaxWidth: 15}})
		noErr(t, s.WriteRow(strings.Repeat("x", 500)))
		mustSheetOptions(t, w, "Empty", SheetOptions{AutoFit: &AutoFit{}})
	})
	if cols := colWidths(t, parts["xl/worksheets/sheet1.xml"]); len(cols) != 1 || cols[0] != (colWidth{1, 15}) {
		t.Errorf("got %v", cols)
	}
	if strings.Contains(parts["xl/worksheets/sheet2.xml"], "<cols>") {
		t.Error("an empty sheet gets no <cols>")
	}
}

func TestAutoFitWithSharedStrings(t *testing.T) {
	parts := book(t, func(w *Writer) {
		s := mustSheetOptions(t, w, "S", SheetOptions{AutoFit: &AutoFit{}})
		noErr(t, s.WriteRow("0123456789"))
	}, WithSharedStrings(true))
	if cols := colWidths(t, parts["xl/worksheets/sheet1.xml"]); len(cols) != 1 || cols[0].width != 10.29 {
		t.Errorf("got %v", cols)
	}
}

func TestAutoFitValidation(t *testing.T) {
	w := NewWriter(&bytes.Buffer{})
	_, err := w.AddSheetOptions("A", SheetOptions{AutoFit: &AutoFit{SampleRows: -1}})
	wantErr(t, err, ErrInvalidArgument)
	_, err = w.AddSheetOptions("B", SheetOptions{AutoFit: &AutoFit{MaxWidth: math.NaN()}})
	wantErr(t, err, ErrInvalidArgument)
	_, err = w.AddSheetOptions("C", SheetOptions{AutoFit: &AutoFit{MaxWidth: -1}})
	wantErr(t, err, ErrInvalidArgument)
	noErr(t, w.Close())
}

func TestEverythingTogether(t *testing.T) {
	parts := book(t, func(w *Writer) {
		s := mustSheetOptions(t, w, "All", SheetOptions{AutoFit: &AutoFit{}, RowOutlineLevels: 1, FrozenRows: 1, TabColor: Blue})
		noErr(t, s.WriteRow("Region", "Revenue"))
		noErr(t, s.WriteRow("North", 10))
		noErr(t, s.BeginRow(RowOptions{OutlineLevel: 1}))
		s.WriteString("  Store")
		s.WriteInt(4)
		noErr(t, s.EndRow())
		noErr(t, s.AddTable(Table{FirstRow: 1, FirstColumn: 1, LastRow: 3, LastColumn: 2, Columns: []string{"Region", "Revenue"}}))
	})
	sheet := parts["xl/worksheets/sheet1.xml"]
	last := -1
	for _, tag := range []string{"<sheetPr>", "<sheetViews>", "<sheetFormatPr", "<cols>", "<sheetData>", "<pageMargins", "<tableParts"} {
		i := strings.Index(sheet, tag)
		if i <= last {
			t.Fatalf("%s missing or out of schema order: %s", tag, sheet)
		}
		last = i
	}
	contains(t, sheet, `<row r="3" outlineLevel="1"><c t="inlineStr"><is><t xml:space="preserve">  Store</t>`)
}

func TestWholeRowWriteFillsBegunRow(t *testing.T) {
	parts := book(t, func(w *Writer) {
		s := mustSheetOptions(t, w, "S", SheetOptions{RowOutlineLevels: 1})
		noErr(t, s.BeginRow(RowOptions{OutlineLevel: 1}))
		noErr(t, s.WriteRow("grouped", 1))
		noErr(t, s.WriteRow("plain", 2))
		noErr(t, s.BeginRow(RowOptions{Height: 30}))
		s.WriteString("partial")
		noErr(t, s.WriteRow("next row", 3))
		noErr(t, s.BeginRow(RowOptions{Hidden: true}))
		s.SkipCells(1)
		noErr(t, s.WriteStrings([]string{"after skip"}))
		noErr(t, s.BeginRow(RowOptions{OutlineLevel: 1}))
		noErr(t, s.EndRow())
		noErr(t, s.WriteRow("after empty row"))
	})
	got := sheetData(t, parts, 1)
	want := `<row r="1" outlineLevel="1"><c t="inlineStr"><is><t>grouped</t></is></c><c><v>1</v></c></row>` +
		`<row r="2"><c t="inlineStr"><is><t>plain</t></is></c><c><v>2</v></c></row>` +
		`<row r="3" ht="30" customHeight="1"><c t="inlineStr"><is><t>partial</t></is></c></row>` +
		`<row r="4"><c t="inlineStr"><is><t>next row</t></is></c><c><v>3</v></c></row>` +
		`<row r="5" hidden="1"></row>` +
		`<row r="6"><c t="inlineStr"><is><t>after skip</t></is></c></row>` +
		`<row r="7" outlineLevel="1"></row>` +
		`<row r="8"><c t="inlineStr"><is><t>after empty row</t></is></c></row>`
	if got != want {
		t.Errorf("\n got %s\nwant %s", got, want)
	}
}

func TestStructsFillBegunRow(t *testing.T) {
	type record struct{ Name string }
	parts := book(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		noErr(t, s.WriteRow("title"))
		noErr(t, s.BeginRow(RowOptions{Height: 25}))
		noErr(t, s.WriteStructHeader(record{}))
		noErr(t, s.BeginRow(RowOptions{Height: 12}))
		noErr(t, s.WriteStruct(record{"a"}))
		noErr(t, s.WriteStruct(record{"b"}))
	})
	got := sheetData(t, parts, 1)
	contains(t, got,
		`<row r="2" ht="25" customHeight="1"><c t="inlineStr"><is><t>Name</t>`,
		`<row r="3" ht="12" customHeight="1"><c t="inlineStr"><is><t>a</t>`,
		`<row r="4"><c t="inlineStr"><is><t>b</t>`,
	)
}

func TestTextWidths(t *testing.T) {
	if textPx("") != 0 || textPx("0123456789") != 70 || textPx("日本") != 30 {
		t.Error("basic widths")
	}
	if textPx("ab\nlonger line") != textPx("longer line") {
		t.Error("the longest line counts")
	}
	if textPx("WWW") <= textPx("iii") {
		t.Error("W is wider than i")
	}
}

func TestNumberShapes(t *testing.T) {
	px := func(code string, v float64) float32 {
		f := newFitState(AutoFit{}, nil, 0)
		f.styles = []styleFit{{known: true, scale: 1, shape: customShape(code)}}
		f.number(nil, 1, 0, v)
		return f.widths[0]
	}
	for _, c := range []struct {
		code  string
		value float64
		want  string
	}{
		{"#,##0.00", 1234567.891, "1,234,567.89"},
		{"0.0%", 0.256, "25.6%"},
		{"0", -42, "-42"},
		{"000", 7, "007"},
		{`"$"#,##0`, 1500, "$1,500"},
		{"[$€-407] #,##0.00", 10, "€ 10.00"},
		{"yyyy-mm-dd", 45000, "2026-12-31"},
		{"dd mmmm yyyy", 45000, "31 September 2026"},
		{"h:mm AM/PM", 0.5, "12:00 PM"},
		{"[h]:mm:ss", 2.5, "100:00:00"},
		{"0.00E+00", 12345, "1.23E+04"},
		{"General", 3.25, "3.25"},
		{"@", 1.0 / 3, "0.333333333"},
	} {
		if got, want := px(c.code, c.value), textPx(c.want); got != want {
			t.Errorf("%s with %v: got %v px, want %v (%s)", c.code, c.value, got, want, c.want)
		}
	}
}

func TestGeneralNumbers(t *testing.T) {
	for v, want := range map[float64]string{42: "42", 1e15: "1.23457E+11", -1e15: "-1.23457E+11", -2.5: "-2.5", 0: "0"} {
		if got := generalPx(v); got != textPx(want) {
			t.Errorf("%v: got %v, want %v", v, got, textPx(want))
		}
	}
	if generalPx(math.NaN()) != textPx("#NUM!") {
		t.Error("NaN")
	}
}

func TestBuiltinShapesMatchTheirCodes(t *testing.T) {
	for id, code := range map[uint32]string{1: "0", 2: "0.00", 3: "#,##0", 4: "#,##0.00", 9: "0%", 10: "0.00%", 11: "0.00E+00", 49: "@"} {
		for _, v := range []float64{0, -1.5, 1234567.891, 0.256} {
			a, b := newFitState(AutoFit{}, nil, 0), newFitState(AutoFit{}, nil, 0)
			a.styles = []styleFit{{known: true, scale: 1, shape: builtinShape(id)}}
			b.styles = []styleFit{{known: true, scale: 1, shape: customShape(code)}}
			a.number(nil, 1, 0, v)
			b.number(nil, 1, 0, v)
			if a.widths[0] != b.widths[0] {
				t.Errorf("format %d (%s) with %v: %v px, want %v", id, code, v, a.widths[0], b.widths[0])
			}
		}
	}
	for id, sample := range map[uint32]string{14: "12/31/2026", 21: "23:59:59", 22: "12/31/2026 23:59", 46: "100:00:00"} {
		if s := builtinShape(id); s.kind != shapeConstant || s.px != textPx(sample) {
			t.Errorf("format %d: %+v", id, s)
		}
	}
}

func TestTableColumnNameEscapes(t *testing.T) {
	got := string(appendColumnNameAttr(nil, "a\r\nb\tc<d"))
	if want := "a_x000D__x000A_b_x0009_c&lt;d"; got != want {
		t.Errorf("got %s, want %s", got, want)
	}
}
