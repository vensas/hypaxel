package hypaxel

import (
	"archive/zip"
	"bytes"
	"errors"
	"reflect"
	"slices"
	"strconv"
	"strings"
	"testing"
)

func mustLayout(t *testing.T, rows *Rows) *SheetLayout {
	t.Helper()
	layout, err := rows.Layout()
	noErr(t, err)
	return layout
}

func mustTables(t *testing.T, r *Reader, sheet string) []Table {
	t.Helper()
	tables, err := r.Tables(sheet)
	noErr(t, err)
	return tables
}

func TestReadBackTablesRowPropertiesAndLayout(t *testing.T) {
	full := Table{FirstRow: 1, FirstColumn: 1, LastRow: 4, LastColumn: 3, Name: "Orders",
		Columns: []string{"Product", "Units & more", "Line\nbreak"}, Style: TableStyleDark(11),
		NoFilterButtons: true, NoRowStripes: true, ColumnStripes: true, HighlightFirstColumn: true, HighlightLastColumn: true}
	defaults := Table{FirstRow: 1, FirstColumn: 5, LastRow: 3, LastColumn: 5, Columns: []string{"Only"}}
	headerless := Table{FirstRow: 10, FirstColumn: 1, LastRow: 12, LastColumn: 2, NoHeaderRow: true, Style: TableStyleNone}

	data := writeBook(t, func(w *Writer) {
		s, err := w.AddSheetOptions("Orders", SheetOptions{
			Columns:          []Column{{Width: 12.5}, {}, {Width: 30}, {Width: 5, Hidden: true}},
			RowOutlineLevels: 2, OutlineSummaryAbove: true,
		})
		noErr(t, err)
		noErr(t, s.BeginRow(RowOptions{Height: 30, Collapsed: true}))
		noErr(t, s.WriteRow("Product", "Units & more", "Line\nbreak", nil, "Only"))
		noErr(t, s.BeginRow(RowOptions{OutlineLevel: 1, Hidden: true}))
		noErr(t, s.WriteRow("Widget", 1, 2.5, nil, "x"))
		noErr(t, s.BeginRow(RowOptions{OutlineLevel: 2, Hidden: true, Height: 12.75}))
		noErr(t, s.WriteRow("Gadget", 2, 3.5, nil, "y"))
		noErr(t, s.WriteRow("Gizmo", 3, 4.5))
		noErr(t, s.AddTable(full))
		noErr(t, s.AddTable(defaults))
		noErr(t, s.AddTable(headerless))

		s, err = w.AddSheetOptions("Fitted", SheetOptions{Columns: []Column{{}, {Width: 40}}, AutoFit: &AutoFit{}})
		noErr(t, err)
		noErr(t, s.WriteRow("a rather long piece of text", "x", "short"))

		noErr(t, mustSheet(t, w, "Plain").WriteRow(1))
	})
	r := openBytes(t, data)

	headerless.Name = "Table3"
	headerless.Columns = []string{"Column1", "Column2"}
	headerless.NoFilterButtons = true
	headerless.NoRowStripes = false
	defaults.Name = "Table2"
	if got, want := mustTables(t, r, "orders"), []Table{full, defaults, headerless}; !reflect.DeepEqual(got, want) {
		t.Errorf("tables:\n got %+v\nwant %+v", got, want)
	}
	for _, sheet := range []string{"Fitted", "Plain"} {
		if got := mustTables(t, r, sheet); len(got) != 0 {
			t.Errorf("%s: unexpected tables %+v", sheet, got)
		}
	}
	_, err := r.Tables("Missing")
	wantErr(t, err, ErrSheetNotFound)
	_, err = r.TablesAt(3)
	wantErr(t, err, ErrSheetNotFound)

	rows, err := r.Rows("Orders")
	noErr(t, err)
	layout := mustLayout(t, rows)
	want := SheetLayout{ColumnWidths: []ColumnWidth{{1, 1, 12.5, true, false}, {3, 3, 30, true, false}, {4, 4, 5, true, true}}, OutlineSummaryAbove: true, RowOutlineLevels: 2}
	if !reflect.DeepEqual(*layout, want) {
		t.Errorf("layout: got %+v, want %+v", *layout, want)
	}
	if layout.ColumnHidden(3) || !layout.ColumnHidden(4) || layout.ColumnHidden(5) {
		t.Errorf("ColumnHidden: got %v %v %v", layout.ColumnHidden(3), layout.ColumnHidden(4), layout.ColumnHidden(5))
	}
	if w, ok := layout.ColumnWidth(3); !ok || w != 30 {
		t.Errorf("ColumnWidth(3) = %v, %v", w, ok)
	}
	if _, ok := layout.ColumnWidth(2); ok {
		t.Error("column 2 has no width")
	}
	type props struct {
		index, level      int
		hidden, collapsed bool
		height            float64
		hasHeight         bool
	}
	var got []props
	for rows.Next() {
		row := rows.Row()
		h, ok := row.Height()
		got = append(got, props{row.Index(), row.OutlineLevel(), row.Hidden(), row.Collapsed(), h, ok})
	}
	noErr(t, rows.Err())
	wantProps := []props{{1, 0, false, true, 30, true}, {2, 1, true, false, 0, false}, {3, 2, true, false, 12.75, true}, {4, 0, false, false, 0, false}}
	if !reflect.DeepEqual(got, wantProps) {
		t.Errorf("row properties:\n got %+v\nwant %+v", got, wantProps)
	}
	if after := mustLayout(t, rows); !reflect.DeepEqual(*after, want) {
		t.Errorf("layout after reading: %+v", *after)
	}

	rows, err = r.Rows("Fitted")
	noErr(t, err)
	defer rows.Close()
	layout = mustLayout(t, rows)
	if layout.OutlineSummaryAbove || layout.RowOutlineLevels != 0 {
		t.Errorf("unexpected outline settings: %+v", *layout)
	}
	first, ok1 := layout.ColumnWidth(1)
	second, ok2 := layout.ColumnWidth(2)
	third, ok3 := layout.ColumnWidth(3)
	if !ok1 || !ok2 || !ok3 || second != 40 || first <= third || third <= 0 {
		t.Errorf("fitted widths: %v %v %v", first, second, third)
	}
	if row := mustNext(t, rows); row.Get(1).Text() != "a rather long piece of text" {
		t.Errorf("first row after Layout: %v", row.Get(1))
	}
}

const tableRel = `<Relationship Id="rId9" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/table" Target="../tables/t.xml"/>`

func sheetWithTable(t testing.TB, sheetXML, tableXML string) []byte {
	t.Helper()
	rels := `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` + tableRel +
		`<Relationship Id="rId2" Type="http://x/hyperlink" Target="http://example.com" TargetMode="External"/></Relationships>`
	return singleSheet(t, sheetXML, "", "xl/worksheets/_rels/sheet1.xml.rels", rels, "xl/tables/t.xml", tableXML)
}

func TestReadLayoutFromOtherProducers(t *testing.T) {
	sheet := `<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
		`<x:sheetPr><x:tabColor rgb="FF00FF00"/><x:outlinePr summaryBelow="false" summaryRight="0"/><x:pageSetUpPr fitToPage="1"/></x:sheetPr>` +
		`<x:sheetViews><x:sheetView workbookViewId="0"><x:selection activeCell="A1"/></x:sheetView></x:sheetViews>` +
		`<x:sheetFormatPr defaultRowHeight="15" outlineLevelRow="9"/>` +
		`<x:cols><x:col min="1" max="16384" width="8.5" style="1"/><x:col min="2" max="3" width="20" customWidth="1"/>` +
		`<x:col min="4" max="4" hidden="1"/><x:col min="0" max="2" width="1"/><x:col min="5" max="4" width="1"/>` +
		`<x:col min="6" max="99999" width=" 7.25 " hidden="true"/><x:col min="7" max="7" width="-1"/><x:col min="8" max="8" width="NaN"/></x:cols>` +
		`<x:sheetData>` +
		`<x:row r="2" spans="1:1" ht="18.75" outlineLevel="3" hidden="true" collapsed="1"><x:c r="A2"><x:v>1</x:v></x:c></x:row>` +
		`<x:row r="3" ht="x" outlineLevel="12" hidden="0" collapsed="false" customHeight="1"><x:c><x:v>2</x:v></x:c></x:row>` +
		`<x:row r="4" outlineLevel="-1"><x:c><x:v>3</x:v></x:c></x:row>` +
		`</x:sheetData></x:worksheet>`
	table := `<?xml version="1.0"?><table xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="4" name="Internal" displayName="Shown" ref="$B$2:$D$9" totalsRowCount="1">` +
		`<autoFilter ref="B2:D8"><filterColumn colId="0"><filters><filter val="x"/></filters></filterColumn></autoFilter>` +
		`<tableColumns count="3"><tableColumn id="1" name="Line_x000A_break"/><tableColumn id="2" name="A &amp; B"><calculatedColumnFormula>1+1</calculatedColumnFormula></tableColumn><tableColumn id="3"/></tableColumns>` +
		`<tableStyleInfo name="My Custom Style" showFirstColumn="true" showLastColumn="0" showRowStripes="1" showColumnStripes="1"/>` +
		`<extLst><ext><autoFilter/><tableColumn name="ignored"/></ext></extLst></table>`
	r := openBytes(t, sheetWithTable(t, sheet, table))

	want := []Table{{FirstRow: 2, FirstColumn: 2, LastRow: 9, LastColumn: 4, Name: "Shown",
		Columns: []string{"Line\nbreak", "A & B", ""}, Style: TableStyleCustom("My Custom Style"), ColumnStripes: true, HighlightFirstColumn: true, TotalsRow: true}}
	if got := mustTables(t, r, "S"); !reflect.DeepEqual(got, want) {
		t.Errorf("tables:\n got %+v\nwant %+v", got, want)
	}

	rows, err := r.Rows("S")
	noErr(t, err)
	layout := mustLayout(t, rows)
	wantLayout := SheetLayout{ColumnWidths: []ColumnWidth{{1, 16384, 8.5, true, false}, {2, 3, 20, true, false}, {4, 4, 0, false, true}, {6, 16384, 7.25, true, true}}, OutlineSummaryAbove: true, RowOutlineLevels: 7}
	if !reflect.DeepEqual(*layout, wantLayout) {
		t.Errorf("layout: got %+v, want %+v", *layout, wantLayout)
	}
	if w, _ := layout.ColumnWidth(2); w != 8.5 {
		t.Errorf("ColumnWidth returns the first match, got %v", w)
	}
	row := mustNext(t, rows)
	if h, ok := row.Height(); row.Index() != 2 || row.OutlineLevel() != 3 || !row.Hidden() || !row.Collapsed() || !ok || h != 18.75 {
		t.Errorf("row 2: %d %v %v %v %v", row.OutlineLevel(), row.Hidden(), row.Collapsed(), h, ok)
	}
	row = mustNext(t, rows)
	if _, ok := row.Height(); row.OutlineLevel() != 7 || row.Hidden() || row.Collapsed() || ok {
		t.Errorf("row 3: %d %v %v %v", row.OutlineLevel(), row.Hidden(), row.Collapsed(), ok)
	}
	if row = mustNext(t, rows); row.OutlineLevel() != 0 {
		t.Errorf("row 4: level %d", row.OutlineLevel())
	}
}

func TestReadTableVariants(t *testing.T) {
	const sheet = `<worksheet><sheetData/></worksheet>`
	const ns = ` xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"`
	for _, c := range []struct {
		xml  string
		want Table
	}{
		{`<table` + ns + ` name="OnlyName" ref="c3" headerRowCount="0"/>`,
			Table{FirstRow: 3, FirstColumn: 3, LastRow: 3, LastColumn: 3, Name: "OnlyName", Style: TableStyleNone, NoHeaderRow: true, NoFilterButtons: true, NoRowStripes: true}},
		{`<table` + ns + ` displayName="T" ref="A1:B2" headerRowCount="1"><autoFilter ref="A1:B2"/><tableStyleInfo name="TableStyleMedium2" showRowStripes="1"/></table>`,
			Table{FirstRow: 1, FirstColumn: 1, LastRow: 2, LastColumn: 2, Name: "T"}},
		{`<table` + ns + ` displayName="T" ref="A1:B2"><tableStyleInfo name="TableStyleLight21"/></table>`,
			Table{FirstRow: 1, FirstColumn: 1, LastRow: 2, LastColumn: 2, Name: "T", Style: TableStyleLight(21), NoFilterButtons: true, NoRowStripes: true}},
		{`<table` + ns + ` displayName="T" ref="A1:B2"><tableStyleInfo name="TableStyleDark12" showRowStripes="1"/></table>`,
			Table{FirstRow: 1, FirstColumn: 1, LastRow: 2, LastColumn: 2, Name: "T", Style: TableStyleCustom("TableStyleDark12"), NoFilterButtons: true}},
	} {
		got := mustTables(t, openBytes(t, sheetWithTable(t, sheet, c.xml)), "S")
		if len(got) != 1 || !reflect.DeepEqual(got[0], c.want) {
			t.Errorf("%s:\n got %+v\nwant %+v", c.xml, got, c.want)
		}
	}
	for name, want := range map[string]TableStyle{"TableStyleMedium28": TableStyleMedium(28), " TableStyleLight3 ": TableStyleLight(3),
		"TableStyleMedium29": TableStyleCustom("TableStyleMedium29"), "TableStyleLight0": TableStyleCustom("TableStyleLight0"),
		"TableStyleMedium": TableStyleCustom("TableStyleMedium"), "PivotStyleLight16": TableStyleCustom("PivotStyleLight16"), "": TableStyleNone,
		" Mine ": TableStyleCustom(" Mine "), "TableStyleDark1x": TableStyleCustom("TableStyleDark1x"), "TableStyleMedium2": {}} {
		if got := parseTableStyle(name); got != want {
			t.Errorf("parseTableStyle(%q) = %v, want %v", name, got, want)
		}
	}
	for _, bad := range []string{"", "A", "1", "A0", "XFE1", "A1048577", "C3:A1", "A1:B", "A1:B2:C3", "1A", "A1 B2"} {
		if _, ok := parseRange([]byte(bad)); ok {
			t.Errorf("parseRange(%q) succeeded", bad)
		}
	}
	if rng, ok := parseRange([]byte(" $B$2:$XFD$1048576 ")); !ok || rng != [4]int{2, 2, 1048576, 16384} {
		t.Errorf("parseRange: %v %v", rng, ok)
	}
}

func TestReadMalformedTablesAndLayout(t *testing.T) {
	const sheet = `<worksheet><sheetData/></worksheet>`
	for _, c := range []struct{ xml, message string }{
		{`<worksheet/>`, "not a table"},
		{``, "not a table"},
		{`<table displayName="T"/>`, "no valid cell range"},
		{`<table displayName="T" ref="A0:B2"/>`, "no valid cell range"},
		{`<table ref="A1:B2"><tableColumns>`, "unexpected end"},
		{`<!DOCTYPE table><table ref="A1"/>`, "document type"},
		{`<table ref="A1:B2"><tableColumns>` + strings.Repeat(`<tableColumn name="c"/>`, MaxColumns+1) + `</tableColumns></table>`, "more than 16384 columns"},
	} {
		_, err := openBytes(t, sheetWithTable(t, sheet, c.xml)).Tables("S")
		wantErr(t, err, ErrMalformed)
		var re *ReadError
		if !errors.As(err, &re) || re.Part != "xl/tables/t.xml" || !strings.Contains(re.Message, c.message) {
			t.Errorf("%.40s: got %v, want %q", c.xml, err, c.message)
		}
	}

	missing := singleSheet(t, sheet, "", "xl/worksheets/_rels/sheet1.xml.rels",
		`<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`+tableRel+`</Relationships>`)
	_, err := openBytes(t, missing).Tables("S")
	wantErr(t, err, ErrMalformed)

	many := `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
		strings.Repeat(tableRel, defaultMaxTables+1) + `</Relationships>`
	data := singleSheet(t, sheet, "", "xl/worksheets/_rels/sheet1.xml.rels", many, "xl/tables/t.xml", `<table ref="A1"/>`)
	_, err = openBytes(t, data).Tables("S")
	wantErr(t, err, ErrLimitExceeded)

	cols := `<worksheet><cols>` + strings.Repeat(`<col min="1" max="1" width="1"/>`, MaxColumns+1) + `</cols><sheetData/></worksheet>`
	rows, err := openBytes(t, singleSheet(t, cols, "")).Rows("S")
	noErr(t, err)
	_, err = rows.Layout()
	wantErr(t, err, ErrMalformed)
	if rows.Next() {
		t.Error("Next after a failed Layout")
	}
	wantErr(t, rows.Err(), ErrMalformed)

	rows, err = openBytes(t, singleSheet(t, `<worksheet><cols><col min="1" max="2" width="3"/></cols>`, "")).Rows("S")
	noErr(t, err)
	_, err = rows.Layout()
	wantErr(t, err, ErrMalformed)

	chart := pkg(t, "_rels/.rels", rootRels,
		"xl/workbook.xml", `<workbook xmlns:r="r"><sheets><sheet name="C" r:id="rId1"/><sheet name="N" r:id="rId7"/></sheets></workbook>`,
		"xl/_rels/workbook.xml.rels", `<Relationships><Relationship Id="rId1" Type="http://x/chartsheet" Target="chartsheets/sheet1.xml"/></Relationships>`,
		"xl/chartsheets/sheet1.xml", `<chartsheet/>`)
	r := openBytes(t, chart)
	for _, name := range []string{"C", "N"} {
		if tables := mustTables(t, r, name); len(tables) != 0 {
			t.Errorf("%s: %+v", name, tables)
		}
	}
}

type emptyRowProps struct {
	index, cells, lastColumn, level int
	hidden                          bool
	height                          float64
}

func readRowProps(t *testing.T, data []byte, sheet string, opts ...ReadOption) []emptyRowProps {
	t.Helper()
	rows, err := openBytes(t, data, opts...).Rows(sheet)
	noErr(t, err)
	var seen []emptyRowProps
	for rows.Next() {
		row := rows.Row()
		height, _ := row.Height()
		seen = append(seen, emptyRowProps{row.Index(), row.Len(), row.LastColumn(), row.OutlineLevel(), row.Hidden(), height})
		if got := len(row.AppendValues(nil)); got != row.LastColumn() {
			t.Fatalf("row %d: %d values, last column %d", row.Index(), got, row.LastColumn())
		}
	}
	noErr(t, rows.Err())
	return seen
}

func TestReadReturnsRowsWithoutValuesOnRequest(t *testing.T) {
	data := writeBook(t, func(w *Writer) {
		s, err := w.AddSheetOptions("Data", SheetOptions{RowOutlineLevels: 1})
		noErr(t, err)
		noErr(t, s.WriteRow("first"))
		noErr(t, s.BeginRow(RowOptions{Height: 40}))
		noErr(t, s.EndRow())
		noErr(t, s.BeginRow(RowOptions{Index: 5}))
		noErr(t, s.WriteRow(5))
		noErr(t, s.BeginRow(RowOptions{OutlineLevel: 1, Hidden: true}))
		noErr(t, s.EndRow())
	})
	valued := []emptyRowProps{{index: 1, cells: 1, lastColumn: 1}, {index: 5, cells: 1, lastColumn: 1}}
	for _, opts := range [][]ReadOption{nil, {WithEmptyRows(false)}} {
		if got := readRowProps(t, data, "Data", opts...); !reflect.DeepEqual(got, valued) {
			t.Fatalf("default: %+v", got)
		}
	}
	want := []emptyRowProps{
		{index: 1, cells: 1, lastColumn: 1},
		{index: 2, height: 40},
		{index: 5, cells: 1, lastColumn: 1},
		{index: 6, level: 1, hidden: true},
	}
	if got := readRowProps(t, data, "Data", WithEmptyRows(true)); !reflect.DeepEqual(got, want) {
		t.Fatalf("with empty rows: %+v", got)
	}

	other := singleSheet(t, `<worksheet><sheetData><row r="2" ht="30"/><row r="3" hidden="1"><c r="A3" s="1"/></row><row><c><v>1</v></c></row><row r="9"></row></sheetData></worksheet>`, "")
	want = []emptyRowProps{
		{index: 2, height: 30},
		{index: 3, hidden: true},
		{index: 4, cells: 1, lastColumn: 1},
		{index: 9},
	}
	if got := readRowProps(t, other, "S", WithEmptyRows(true)); !reflect.DeepEqual(got, want) {
		t.Fatalf("other producer: %+v", got)
	}
	if got := readRowProps(t, other, "S"); !reflect.DeepEqual(got, want[2:3]) {
		t.Fatalf("other producer, default: %+v", got)
	}
}

func TestTableStyleMedium2IsTheZeroValue(t *testing.T) {
	if TableStyleMedium(2) != (TableStyle{}) {
		t.Fatalf("TableStyleMedium(2) = %v, want the zero value", TableStyleMedium(2))
	}
	if got := TableStyleMedium(2).String(); got != "TableStyleMedium2" {
		t.Errorf("String() = %q", got)
	}
	written := Table{FirstRow: 1, FirstColumn: 1, LastRow: 2, LastColumn: 1, Name: "T", Columns: []string{"a"}, Style: TableStyleMedium(2)}
	data := writeBook(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		noErr(t, s.WriteRow("a"))
		noErr(t, s.WriteRow(1))
		noErr(t, s.AddTable(written))
	})
	if got := mustTables(t, openBytes(t, data), "S"); !reflect.DeepEqual(got, []Table{written}) {
		t.Errorf("got %+v, want %+v", got, written)
	}
}

func TestReadTablesInPositionOrder(t *testing.T) {
	const ns = ` xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"`
	rel := func(id, target string) string {
		return `<Relationship Id="` + id + `" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/` + target + `"/>`
	}
	data := singleSheet(t, `<worksheet`+ns+`><sheetData/></worksheet>`, "",
		"xl/worksheets/_rels/sheet1.xml.rels", `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`+
			rel("rId3", "c.xml")+rel("rId2", "b.xml")+rel("rId1", "a.xml")+`</Relationships>`,
		"xl/tables/a.xml", `<table`+ns+` name="A" ref="A1:B2"/>`,
		"xl/tables/b.xml", `<table`+ns+` name="B" ref="D1:E2"/>`,
		"xl/tables/c.xml", `<table`+ns+` name="C" ref="A5:B6"/>`)
	var names []string
	for _, table := range mustTables(t, openBytes(t, data), "S") {
		names = append(names, table.Name)
	}
	if got := strings.Join(names, ""); got != "ABC" {
		t.Errorf("order = %s, want ABC", got)
	}
}

type savedRow struct {
	index, cells, level int
	hidden, collapsed   bool
	height              float64
	hasHeight           bool
}

// readSavedWorkbook reads a workbook from testdata/reader at the repository root: the tables
// and layout of its Orders sheet and the properties of every stored row.
func readSavedWorkbook(t *testing.T, file string) ([]Table, SheetLayout, []savedRow) {
	t.Helper()
	r, err := OpenReader("../testdata/reader/"+file, WithEmptyRows(true))
	noErr(t, err)
	defer r.Close()
	if got := r.SheetNames(); !reflect.DeepEqual(got, []string{"Orders", "Plain"}) {
		t.Fatalf("sheets = %v", got)
	}
	if got := mustTables(t, r, "Plain"); len(got) != 0 {
		t.Errorf("Plain: unexpected tables %+v", got)
	}
	tables := mustTables(t, r, "Orders")
	rows, err := r.Rows("Orders")
	noErr(t, err)
	defer rows.Close()
	layout := *mustLayout(t, rows)
	var seen []savedRow
	for rows.Next() {
		row := rows.Row()
		height, hasHeight := row.Height()
		seen = append(seen, savedRow{row.Index(), row.Len(), row.OutlineLevel(), row.Hidden(), row.Collapsed(), height, hasHeight})
	}
	noErr(t, rows.Err())
	return tables, layout, seen
}

var savedWorkbookLayout = SheetLayout{
	ColumnWidths:        []ColumnWidth{{1, 1, 14, true, false}, {2, 2, 22.5, true, false}, {4, 4, 6, true, true}},
	OutlineSummaryAbove: true,
	RowOutlineLevels:    2,
}

func TestReadWorkbookSavedByExcel(t *testing.T) {
	tables, layout, rows := readSavedWorkbook(t, "excel.xlsx")
	wantTables := []Table{
		{FirstRow: 1, FirstColumn: 1, LastRow: 4, LastColumn: 3, Name: "Orders", Columns: []string{"Product", "Units & more", "Price"},
			Style: TableStyleDark(3), NoRowStripes: true, ColumnStripes: true, HighlightFirstColumn: true, HighlightLastColumn: true},
		{FirstRow: 7, FirstColumn: 1, LastRow: 8, LastColumn: 2, Name: "Table2", Columns: []string{"Column1", "Column2"},
			Style: TableStyleNone, NoHeaderRow: true, NoFilterButtons: true},
	}
	if !reflect.DeepEqual(tables, wantTables) {
		t.Errorf("tables:\n got %+v\nwant %+v", tables, wantTables)
	}
	if !reflect.DeepEqual(layout, savedWorkbookLayout) {
		t.Errorf("layout: got %+v, want %+v", layout, savedWorkbookLayout)
	}
	wantRows := []savedRow{
		{1, 3, 0, false, false, 0, false},
		{2, 3, 1, false, true, 0, false},
		{3, 3, 2, true, false, 0, false},
		{4, 3, 1, false, true, 30, true},
		{6, 0, 0, false, false, 45, true},
		{7, 2, 0, false, false, 0, false},
		{8, 2, 0, false, false, 0, false},
	}
	if !reflect.DeepEqual(rows, wantRows) {
		t.Errorf("rows:\n got %+v\nwant %+v", rows, wantRows)
	}
}

// LibreOffice drops table styles and stores a height on every row.
func TestReadWorkbookSavedByLibreOffice(t *testing.T) {
	tables, layout, rows := readSavedWorkbook(t, "libreoffice.xlsx")
	wantTables := []Table{
		{FirstRow: 1, FirstColumn: 1, LastRow: 4, LastColumn: 3, Name: "Orders", Columns: []string{"Product", "Units & more", "Price"},
			Style: TableStyleNone, NoRowStripes: true},
		{FirstRow: 7, FirstColumn: 1, LastRow: 8, LastColumn: 2, Name: "Table2", Columns: []string{"Column1", "Column2"},
			Style: TableStyleNone, NoHeaderRow: true, NoFilterButtons: true, NoRowStripes: true},
	}
	if !reflect.DeepEqual(tables, wantTables) {
		t.Errorf("tables:\n got %+v\nwant %+v", tables, wantTables)
	}
	if !reflect.DeepEqual(layout, savedWorkbookLayout) {
		t.Errorf("layout: got %+v, want %+v", layout, savedWorkbookLayout)
	}
	wantRows := []savedRow{
		{1, 3, 0, false, false, 15, true},
		{2, 3, 1, false, false, 15, true},
		{3, 3, 2, true, false, 15, true},
		{4, 3, 1, false, true, 30, true},
		{6, 0, 0, false, false, 45, true},
		{7, 2, 0, false, false, 15, true},
		{8, 2, 0, false, false, 15, true},
	}
	if !reflect.DeepEqual(rows, wantRows) {
		t.Errorf("rows:\n got %+v\nwant %+v", rows, wantRows)
	}
}

// excel-zero-width.xlsx was saved by Excel for Mac from a file that gave column B a width of 0
// without the hidden flag; Excel added the flag and kept the width of 0.
func TestReadZeroWidthColumnSavedByExcel(t *testing.T) {
	r, err := OpenReader("../testdata/reader/excel-zero-width.xlsx")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	rows, err := r.RowsAt(0)
	if err != nil {
		t.Fatal(err)
	}
	layout := mustLayout(t, rows)
	want := []ColumnWidth{{1, 1, 10.5, true, false}, {2, 2, 0, true, true}}
	if !reflect.DeepEqual(layout.ColumnWidths, want) {
		t.Errorf("got %+v, want %+v", layout.ColumnWidths, want)
	}
	if !layout.ColumnVisible(1) || layout.ColumnVisible(2) || !layout.ColumnVisible(3) || layout.ColumnWidths[1].Visible() {
		t.Error("column B should be the only column that is not visible")
	}
}

// excel-totals.xlsx is an inventory list saved by Excel for Mac: one table over 1,000 rows with
// a totals row.
func TestReadTableWithTotalsRowSavedByExcel(t *testing.T) {
	r, err := OpenReader("../testdata/reader/excel-totals.xlsx")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	if names := r.SheetNames(); !reflect.DeepEqual(names, []string{"Inventory"}) {
		t.Errorf("sheets: %v", names)
	}

	tables, err := r.TablesAt(0)
	if err != nil {
		t.Fatal(err)
	}
	wantTables := []Table{{
		FirstRow: 1, FirstColumn: 1, LastRow: 1002, LastColumn: 11, Name: "Table1",
		Columns: []string{"SKU", "Item", "Group", "Vendor", "Cost", "Price",
			"Opening", "Sold", "Closing", "Reorder", "Status"},
		Style: TableStyleMedium(2), TotalsRow: true,
	}}
	if !reflect.DeepEqual(tables, wantTables) {
		t.Errorf("tables:\n got %+v\nwant %+v", tables, wantTables)
	}

	rows, err := r.RowsAt(0)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	layout, err := rows.Layout()
	if err != nil {
		t.Fatal(err)
	}
	if layout.OutlineSummaryAbove || len(layout.ColumnWidths) != 11 {
		t.Fatalf("layout: %+v", layout)
	}
	first, last := layout.ColumnWidths[0], layout.ColumnWidths[10]
	if first != (ColumnWidth{FirstColumn: 1, LastColumn: 1, Width: 11.6640625, HasWidth: true}) || last != (ColumnWidth{FirstColumn: 11, LastColumn: 11, Width: 13.1640625, HasWidth: true}) {
		t.Errorf("column widths: first %+v, last %+v", first, last)
	}

	count := 0
	var totals []string
	for rows.Next() {
		row := rows.Row()
		count++
		if _, hasHeight := row.Height(); row.OutlineLevel() != 0 || row.Hidden() || hasHeight {
			t.Errorf("row %d has unexpected properties", row.Index())
		}
		if row.Index() == 1002 {
			for column, v := range row.Cells() {
				totals = append(totals, strconv.Itoa(column)+"="+v.String())
			}
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if count != 1002 {
		t.Errorf("rows: got %d, want 1002", count)
	}
	want := []string{"1=Total", "5=21.085", "6=33.736", "8=99696", "10=34500", "11=1000"}
	if !reflect.DeepEqual(totals, want) {
		t.Errorf("totals row:\n got %v\nwant %v", totals, want)
	}
}

// google-sheets.xlsx is a table downloaded from Google Sheets: a custom table style, no filter
// buttons, a totals row and a height on every row but the first.
func TestReadTableDownloadedFromGoogleSheets(t *testing.T) {
	r, err := OpenReader("../testdata/reader/google-sheets.xlsx")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	if names := r.SheetNames(); !reflect.DeepEqual(names, []string{"Inventory"}) {
		t.Errorf("sheets: %v", names)
	}

	tables, err := r.TablesAt(0)
	if err != nil {
		t.Fatal(err)
	}
	wantTables := []Table{{
		FirstRow: 1, FirstColumn: 1, LastRow: 903, LastColumn: 11, Name: "Table1",
		Columns: []string{"SKU", "Item", "Group", "Vendor", "Cost", "Price",
			"Opening", "Sold", "Closing", "Reorder", "Status"},
		Style: TableStyleCustom("Inventory-style"), NoFilterButtons: true,
		HighlightFirstColumn: true, HighlightLastColumn: true, TotalsRow: true,
	}}
	if !reflect.DeepEqual(tables, wantTables) {
		t.Errorf("tables:\n got %+v\nwant %+v", tables, wantTables)
	}

	rows, err := r.RowsAt(0)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	layout, err := rows.Layout()
	if err != nil {
		t.Fatal(err)
	}
	if layout.OutlineSummaryAbove || len(layout.ColumnWidths) != 12 {
		t.Fatalf("layout: %+v", layout)
	}
	first, last := layout.ColumnWidths[0], layout.ColumnWidths[11]
	if first != (ColumnWidth{FirstColumn: 1, LastColumn: 1, Width: 11.63, HasWidth: true}) || last != (ColumnWidth{FirstColumn: 12, LastColumn: 26, Width: 8.88, HasWidth: true}) {
		t.Errorf("column widths: first %+v, last %+v", first, last)
	}
	if width, ok := layout.ColumnWidth(20); !ok || width != 8.88 {
		t.Errorf("width of column 20: %v, %v", width, ok)
	}

	count := 0
	var totals []string
	for rows.Next() {
		row := rows.Row()
		count++
		if row.OutlineLevel() != 0 || row.Hidden() {
			t.Errorf("row %d is grouped or hidden", row.Index())
		}
		height, hasHeight := row.Height()
		if row.Index() == 1 && hasHeight || row.Index() != 1 && (!hasHeight || height != 15.75) {
			t.Errorf("row %d: height %v, %v", row.Index(), height, hasHeight)
		}
		if row.Index() == 2 {
			if got, ok := row.Get(7).Float(); !ok || got != 250 {
				t.Errorf("G2: got %v, %v, want the number 250", got, ok)
			}
		}
		if row.Index() == 903 {
			for column, v := range row.Cells() {
				totals = append(totals, strconv.Itoa(column)+"="+v.String())
			}
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if count != 903 {
		t.Errorf("rows: got %d, want 903", count)
	}
	want := []string{"1=Total", "5=21.20421754", "6=33.92674806", "8=90058", "10=31060", "11=901"}
	if !reflect.DeepEqual(totals, want) {
		t.Errorf("totals row:\n got %v\nwant %v", totals, want)
	}
}

func TestReadTablesBoundsMemory(t *testing.T) {
	const sheet = `<worksheet><sheetData/></worksheet>`
	const table = `<table displayName="T" ref="A1:B2"><tableColumns><tableColumn name="a"/><tableColumn name="b"/></tableColumns></table>`
	rels := `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` + strings.Repeat(tableRel, 3) + `</Relationships>`
	repeated := singleSheet(t, sheet, "", "xl/worksheets/_rels/sheet1.xml.rels", rels, "xl/tables/t.xml", table)
	tables, err := openBytes(t, repeated).Tables("S")
	noErr(t, err)
	if len(tables) != 1 {
		t.Errorf("a part referenced three times gave %d tables, want 1", len(tables))
	}

	column := `<tableColumn name="` + strings.Repeat("n", 4200) + `"/>`
	heavy := `<table displayName="T" ref="A1:B2"><tableColumns>` + strings.Repeat(column, MaxColumns) + `</tableColumns></table>`
	_, err = openBytes(t, sheetWithTable(t, sheet, heavy)).Tables("S")
	wantErr(t, err, ErrLimitExceeded)
	var re *ReadError
	if !errors.As(err, &re) || re.Part != "xl/tables/t.xml" || !strings.Contains(re.Message, "WithMaxTableBytes") {
		t.Errorf("got %v", err)
	}
}

func TestReadBoundsRelationships(t *testing.T) {
	const sheet = `<worksheet><sheetData/></worksheet>`
	const table = `<table displayName="T" ref="A1:B2"><tableColumns><tableColumn name="a"/><tableColumn name="b"/></tableColumns></table>`
	const open = `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
	external := `<Relationship Id="x" Type="t/hyperlink" Target="https://example.com" TargetMode="External"/>`
	rels := open + strings.Repeat(external, 70_000) + tableRel + `</Relationships>`
	links := singleSheet(t, sheet, "", "xl/worksheets/_rels/sheet1.xml.rels", rels, "xl/tables/t.xml", table)
	tables, err := openBytes(t, links).Tables("S")
	noErr(t, err)
	if len(tables) != 1 {
		t.Errorf("got %d tables behind 70,000 external links, want 1", len(tables))
	}

	internal := `<Relationship Id="x" Type="t/other" Target="other.xml"/>`
	rels = open + strings.Repeat(internal, 65_537) + `</Relationships>`
	flood := singleSheet(t, sheet, "", "xl/worksheets/_rels/sheet1.xml.rels", rels)
	_, err = openBytes(t, flood).Tables("S")
	wantErr(t, err, ErrLimitExceeded)
	var re *ReadError
	if !errors.As(err, &re) || re.Part != "xl/worksheets/_rels/sheet1.xml.rels" || !strings.Contains(re.Message, "more than 65536 relationships") {
		t.Errorf("got %v", err)
	}
}

func TestLayoutOnSheetWithoutRowsVerifiesTheEntry(t *testing.T) {
	sheet := `<worksheet><cols><col min="1" max="1" width="9"/></cols><sheetData/>` + strings.Repeat(`<mergeCell ref="A1:B2"/>`, 20_000) + `</worksheet>`
	data := singleSheet(t, sheet, "")
	damaged := bytes.Clone(data)
	damaged[centralEntry(t, data, "xl/worksheets/sheet1.xml")+16] ^= 0xFF

	checksum := func(err error) bool { return errors.Is(err, ErrMalformed) && errors.Is(err, zip.ErrChecksum) }
	for _, concurrent := range []bool{false, true} {
		for _, layoutFirst := range []bool{false, true} {
			rows, err := openBytes(t, damaged, WithConcurrentInflate(concurrent)).Rows("S")
			noErr(t, err)
			if layoutFirst {
				for range 2 {
					if _, err := rows.Layout(); !checksum(err) {
						t.Errorf("concurrent %v: Layout() = %v, want a checksum error", concurrent, err)
					}
				}
			}
			if rows.Next() {
				t.Fatal("unexpected row")
			}
			if err := rows.Err(); !checksum(err) {
				t.Errorf("concurrent %v, layout first %v: Err() = %v, want a checksum error", concurrent, layoutFirst, err)
			}
			for range 2 {
				if _, err := rows.Layout(); !checksum(err) {
					t.Errorf("concurrent %v, layout first %v: Layout() after Next = %v, want a checksum error", concurrent, layoutFirst, err)
				}
			}
		}
	}

	rows, err := openBytes(t, data).Rows("S")
	noErr(t, err)
	layout, err := rows.Layout()
	noErr(t, err)
	if len(layout.ColumnWidths) != 1 {
		t.Errorf("layout: %+v", layout)
	}
	if rows.rc != nil {
		t.Error("Layout at the end of the rows left the entry open")
	}
}

func TestLayoutErrorsAreItsOwn(t *testing.T) {
	sheet := `<worksheet><cols><col min="2" max="3" width="7.5"/></cols><sheetData><row r="1"><c r="A1"><v>1</v></c></row><row r="2"><c r="A2"><v>x</v></c></row></sheetData></worksheet>`
	rows, err := openBytes(t, singleSheet(t, sheet, "")).Rows("S")
	noErr(t, err)
	if _, err := rows.Layout(); err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
	}
	wantErr(t, rows.Err(), ErrMalformed)
	layout, err := rows.Layout()
	noErr(t, err)
	if width, ok := layout.ColumnWidth(3); !ok || width != 7.5 {
		t.Errorf("layout after a row error: %+v", layout)
	}

	rows, err = openBytes(t, singleSheet(t, sheet, "")).Rows("S")
	noErr(t, err)
	for rows.Next() {
	}
	if layout, err := rows.Layout(); err != nil || len(layout.ColumnWidths) != 1 {
		t.Errorf("layout read by Next: %+v, %v", layout, err)
	}

	rows, err = openBytes(t, singleSheet(t, sheet, "")).Rows("S")
	noErr(t, err)
	noErr(t, rows.Close())
	if _, err := rows.Layout(); !errors.Is(err, ErrRowsClosed) {
		t.Errorf("Layout on closed rows: %v", err)
	}
	if rows.Next() || rows.Err() != nil {
		t.Errorf("closed rows: Err() = %v", rows.Err())
	}

	bad := `<worksheet><cols>` + strings.Repeat(`<col min="1" max="1" width="1"/>`, MaxColumns+1) + `</cols><sheetData/></worksheet>`
	rows, err = openBytes(t, singleSheet(t, bad, "")).Rows("S")
	noErr(t, err)
	_, first := rows.Layout()
	_, second := rows.Layout()
	wantErr(t, first, ErrMalformed)
	if second != first {
		t.Errorf("second Layout: %v, want %v", second, first)
	}
}

func TestReadMeasuresArePlainDecimals(t *testing.T) {
	sheet := `<worksheet><cols><col min="1" max="1" width="0x1p4"/><col min="2" max="2" width="1_0"/><col min="3" max="3" width="Inf"/><col min="4" max="4" width="1.5e1"/></cols>` +
		`<sheetData><row ht="0x1p4"><c><v>1</v></c></row><row ht="NaN"><c><v>2</v></c></row><row ht=" 12.5 "><c><v>3</v></c></row></sheetData></worksheet>`
	rows, err := openBytes(t, singleSheet(t, sheet, "")).Rows("S")
	noErr(t, err)
	layout, err := rows.Layout()
	noErr(t, err)
	if len(layout.ColumnWidths) != 1 || layout.ColumnWidths[0] != (ColumnWidth{FirstColumn: 4, LastColumn: 4, Width: 15, HasWidth: true}) {
		t.Errorf("column widths: %+v", layout.ColumnWidths)
	}
	var heights []float64
	for rows.Next() {
		height, ok := rows.Row().Height()
		if !ok {
			height = -1
		}
		heights = append(heights, height)
	}
	noErr(t, rows.Err())
	if !slices.Equal(heights, []float64{-1, -1, 12.5}) {
		t.Errorf("heights: %v", heights)
	}
}

// numbers.xlsx is exported by Apple Numbers: its table is plain cells, not an Excel table, and an
// empty first row holds the chart.
func TestReadSheetExportedByNumbers(t *testing.T) {
	const path = "../testdata/reader/numbers.xlsx"
	r, err := OpenReader(path, WithEmptyRows(true))
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	if names := r.SheetNames(); !reflect.DeepEqual(names, []string{"Table and Chart"}) {
		t.Errorf("sheets: %v", names)
	}
	tables, err := r.TablesAt(0)
	if err != nil || len(tables) != 0 {
		t.Fatalf("tables: %+v, %v", tables, err)
	}

	rows, err := r.RowsAt(0)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	layout, err := rows.Layout()
	if err != nil {
		t.Fatal(err)
	}
	wantWidths := []ColumnWidth{
		{FirstColumn: 1, LastColumn: 1, Width: 24.6719, HasWidth: true},
		{FirstColumn: 2, LastColumn: 2, Width: 18.3672, HasWidth: true},
		{FirstColumn: 3, LastColumn: 5, Width: 18.7188, HasWidth: true},
		{FirstColumn: 6, LastColumn: 16384, Width: 27.75, HasWidth: true},
	}
	if !reflect.DeepEqual(layout.ColumnWidths, wantWidths) || layout.OutlineSummaryAbove || layout.RowOutlineLevels != 0 {
		t.Errorf("layout: %+v", layout)
	}

	type row struct {
		index  int
		height float64
		cells  string
	}
	want := []row{
		{1, 311.6, ""},
		{2, 27.65, "1:s=Table 1"},
		{3, 20.25, "2:s=Category A 3:s=Category B"},
		{4, 20.25, "1:s=Item 1 2:n=5 3:n=7"},
		{5, 20.05, "1:s=Item 2 2:n=10 3:n=8"},
		{6, 20.05, "1:s=Item 3 2:n=9 3:n=15"},
		{7, 20.05, "1:s=Item 4 2:n=7 3:n=12"},
		{8, 20.05, "1:s=Item 5 2:n=16 3:n=21"},
	}
	var got []row
	for rows.Next() {
		r := rows.Row()
		height, ok := r.Height()
		if !ok {
			height = -1
		}
		var cells []string
		for column, v := range r.Cells() {
			kind := "?"
			switch v.Kind() {
			case ValueString:
				kind = "s"
			case ValueNumber:
				kind = "n"
			}
			cells = append(cells, strconv.Itoa(column)+":"+kind+"="+v.String())
		}
		got = append(got, row{r.Index(), height, strings.Join(cells, " ")})
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("rows:\n got %+v\nwant %+v", got, want)
	}

	plain, err := OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer plain.Close()
	first, err := plain.RowsAt(0)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	if !first.Next() || first.Row().Index() != 2 {
		t.Errorf("without WithEmptyRows the first row read should be row 2 (err %v)", first.Err())
	}
}

func TestTableNamesThatLookLikeExcelEscapesRoundTrip(t *testing.T) {
	table := Table{FirstRow: 1, FirstColumn: 1, LastRow: 2, LastColumn: 1, Name: "T_x0041_", Columns: []string{"a_x0042_"}}
	data := writeBook(t, func(w *Writer) {
		s, err := w.AddSheet("S")
		noErr(t, err)
		noErr(t, s.WriteRow("a_x0042_"))
		noErr(t, s.WriteRow(1))
		noErr(t, s.AddTable(table))
	})
	tables, err := openBytes(t, data).TablesAt(0)
	noErr(t, err)
	if len(tables) != 1 || !reflect.DeepEqual(tables[0], table) {
		t.Errorf("tables = %+v, want %+v", tables, table)
	}

	// As Excel saves a table it was told to name T_x0041_: lower-case hex digits.
	excel := sheetWithTable(t, `<worksheet><sheetData/></worksheet>`,
		`<table name="T_x005f_x0041_" displayName="T_x005f_x0041_" ref="A1:A2"><tableColumns><tableColumn name="a"/></tableColumns></table>`)
	tables, err = openBytes(t, excel).TablesAt(0)
	noErr(t, err)
	if len(tables) != 1 || tables[0].Name != "T_x0041_" {
		t.Errorf("Excel-escaped name = %+v, want T_x0041_", tables)
	}
}
