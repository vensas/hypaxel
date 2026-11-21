package hypaxel

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

func writeBook(t testing.TB, write func(w *Writer), opts ...Option) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := NewWriter(&buf, opts...)
	write(w)
	if err := w.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	return buf.Bytes()
}

func openBytes(t testing.TB, data []byte, opts ...ReadOption) *Reader {
	t.Helper()
	r, err := NewReader(bytes.NewReader(data), int64(len(data)), opts...)
	if err != nil {
		t.Fatalf("NewReader: %v", err)
	}
	return r
}

type dumpedRow struct {
	index  int
	values []string
}

// dump returns every row of a sheet with its cells as text, dates in ISO 8601.
func dump(t *testing.T, data []byte, sheet string) []dumpedRow {
	t.Helper()
	rows, err := openBytes(t, data).Rows(sheet)
	noErr(t, err)
	var out []dumpedRow
	for row, err := range rows.All() {
		noErr(t, err)
		var values []string
		for _, v := range row.AppendValues(nil) {
			values = append(values, v.String())
		}
		out = append(out, dumpedRow{row.Index(), values})
	}
	return out
}

func wantRows(t *testing.T, got []dumpedRow, want ...dumpedRow) {
	t.Helper()
	if !reflect.DeepEqual(got, want) {
		t.Errorf("rows:\n got %v\nwant %v", got, want)
	}
}

func dr(index int, values ...string) dumpedRow { return dumpedRow{index, values} }

// pkg builds a ZIP package from name, content pairs.
func pkg(t testing.TB, parts ...string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for i := 0; i < len(parts); i += 2 {
		f, err := zw.Create(parts[i])
		if err != nil {
			t.Fatal(err)
		}
		f.Write([]byte(parts[i+1]))
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

const rootRels = `<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`

func singleSheet(t testing.TB, sheetXML, extraRels string, extraParts ...string) []byte {
	t.Helper()
	workbook := `<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>`
	rels := `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>` + extraRels + `</Relationships>`
	parts := []string{"_rels/.rels", rootRels, "xl/workbook.xml", workbook, "xl/_rels/workbook.xml.rels", rels, "xl/worksheets/sheet1.xml", sheetXML}
	return pkg(t, append(parts, extraParts...)...)
}

func rowsError(t *testing.T, data []byte, opts ...ReadOption) error {
	t.Helper()
	rows, err := openBytes(t, data, opts...).Rows("S")
	if err != nil {
		return err
	}
	for rows.Next() {
	}
	if rows.Err() == nil {
		t.Fatal("expected an error")
	}
	return rows.Err()
}

func mustNext(t *testing.T, rows *Rows) *Row {
	t.Helper()
	if !rows.Next() {
		t.Fatalf("no more rows: %v", rows.Err())
	}
	return rows.Row()
}

func wantValue(t *testing.T, got Value, kind ValueKind, text string) {
	t.Helper()
	if got.Kind() != kind || got.String() != text {
		t.Errorf("got %v %q, want %v %q", got.Kind(), got.String(), kind, text)
	}
}

func TestReadValueTypesInBothStringModes(t *testing.T) {
	const special = "<&>\"' _x0041_ \x01\ttab\nline 東京 😀"
	dt := time.Date(2024, 3, 15, 13, 45, 30, 0, time.UTC)
	for _, shared := range []bool{false, true} {
		t.Run(fmt.Sprint("shared=", shared), func(t *testing.T) {
			data := writeBook(t, func(w *Writer) {
				clock := w.AddStyle(Style{NumberFormat: FormatTime})
				border := w.AddStyle(Style{Border: BorderAll(BorderThin, 0)})
				s := mustSheet(t, w, "Data")
				noErr(t, s.WriteRow("text", 42, int64(-1_234_567_890_123), 3.25, true, false))
				s.WriteTime(dt)
				s.SkipCells(1)
				s.WriteFloat(0.25, clock)
				s.WriteDuration(36 * time.Hour)
				s.WriteDate(dt)
				noErr(t, s.EndRow())
				noErr(t, s.WriteRow(math.NaN(), nil, "after-gap", special))
				s.WriteFormula("SUM(B1:D1)")
				s.WriteBlank(border)
				s.WriteString("")
				noErr(t, s.EndRow())
			}, WithSharedStrings(shared))

			r := openBytes(t, data)
			rows, err := r.Rows("Data")
			noErr(t, err)
			defer rows.Close()

			row := mustNext(t, rows)
			if row.Index() != 1 || row.Len() != 6 || row.LastColumn() != 6 {
				t.Fatalf("row 1: index %d, len %d, last column %d", row.Index(), row.Len(), row.LastColumn())
			}
			wantValue(t, row.Get(1), ValueString, "text")
			wantValue(t, row.Get(2), ValueNumber, "42")
			wantValue(t, row.Get(3), ValueNumber, "-1234567890123")
			wantValue(t, row.Get(4), ValueNumber, "3.25")
			wantValue(t, row.Get(5), ValueBool, "TRUE")
			wantValue(t, row.Get(6), ValueBool, "FALSE")
			wantValue(t, row.Get(7), ValueEmpty, "")
			if b, ok := row.Get(5).Bool(); !b || !ok {
				t.Error("Bool() of TRUE")
			}
			if n, ok := row.Get(4).Float(); n != 3.25 || !ok {
				t.Error("Float() of 3.25")
			}

			row = mustNext(t, rows)
			if v := row.Get(1); v.DateKind() != DateKindDateTime {
				t.Errorf("date kind %v", v.DateKind())
			} else if got, ok := v.Time(); !ok || !got.Equal(dt) || got.Location() != time.UTC {
				t.Errorf("Time() = %v, %v", got, ok)
			}
			if v := row.Get(3); v.DateKind() != DateKindTime || v.String() != "06:00:00" {
				t.Errorf("time of day: %v %q", v.DateKind(), v)
			} else if got, _ := v.Time(); got.Hour() != 6 || got.Minute() != 0 {
				t.Errorf("time of day: %v", got)
			}
			if v := row.Get(4); v.DateKind() != DateKindDuration {
				t.Errorf("duration kind %v", v.DateKind())
			} else if d, ok := v.Duration(); !ok || d != 36*time.Hour {
				t.Errorf("Duration() = %v, %v", d, ok)
			}
			if v := row.Get(5); v.DateKind() != DateKindDate || v.String() != "2024-03-15" {
				t.Errorf("date: %v %q", v.DateKind(), v)
			}
			if serial, ok := row.Get(5).Float(); !ok || serial != 45366 {
				t.Errorf("serial %v", serial)
			}

			row = mustNext(t, rows)
			wantValue(t, row.Get(1), ValueError, "#NUM!")
			wantValue(t, row.Get(2), ValueEmpty, "")
			wantValue(t, row.Get(3), ValueString, "after-gap")
			wantValue(t, row.Get(4), ValueString, special)
			var columns []int
			for column := range row.Cells() {
				columns = append(columns, column)
			}
			if !reflect.DeepEqual(columns, []int{1, 3, 4}) {
				t.Errorf("columns %v", columns)
			}

			// A formula without a cached value and a styled blank have no value; "" is a string.
			row = mustNext(t, rows)
			if column, v := row.Cell(0); row.Index() != 4 || row.Len() != 1 || column != 3 || v.Kind() != ValueString || v.Text() != "" {
				t.Errorf("row %d: %d cells, first %d %v", row.Index(), row.Len(), column, v.Kind())
			}
			if rows.Next() || rows.Next() || rows.Err() != nil {
				t.Errorf("expected the end, err %v", rows.Err())
			}
		})
	}
}

func TestReadDatesInBothDateSystems(t *testing.T) {
	dates := []time.Time{
		time.Date(1900, 1, 1, 0, 0, 0, 0, time.UTC),
		time.Date(1900, 2, 28, 0, 0, 0, 0, time.UTC),
		time.Date(1900, 3, 1, 0, 0, 0, 0, time.UTC),
		time.Date(1904, 1, 1, 0, 0, 0, 0, time.UTC),
		time.Date(1999, 12, 31, 0, 0, 0, 0, time.UTC),
		time.Date(2024, 2, 29, 0, 0, 0, 0, time.UTC),
		time.Date(9999, 12, 31, 0, 0, 0, 0, time.UTC),
	}
	for _, date1904 := range []bool{false, true} {
		data := writeBook(t, func(w *Writer) {
			s := mustSheet(t, w, "D")
			for _, d := range dates {
				s.WriteDate(d)
				s.WriteTime(d.Add(23*time.Hour + 59*time.Minute + 59*time.Second + 123*time.Millisecond))
				noErr(t, s.EndRow())
			}
		}, WithDate1904(date1904))
		r := openBytes(t, data)
		if r.Date1904() != date1904 {
			t.Fatalf("Date1904() = %v", r.Date1904())
		}
		rows, err := r.RowsAt(0)
		noErr(t, err)
		for _, d := range dates {
			row := mustNext(t, rows)
			if date1904 && d.Year() < 1904 {
				wantValue(t, row.Get(1), ValueString, d.Format(time.DateOnly))
				continue
			}
			got, ok := row.Get(1).Time()
			if !ok || !got.Equal(d) {
				t.Errorf("1904=%v: date %v, got %v %v", date1904, d, got, ok)
			}
			want := d.Add(23*time.Hour + 59*time.Minute + 59*time.Second + 123*time.Millisecond)
			if got, ok := row.Get(2).Time(); !ok || !got.Equal(want) {
				t.Errorf("1904=%v: date-time %v, got %v %v", date1904, want, got, ok)
			}
		}
	}
}

func TestReadListsSheetsAndFindsThemByName(t *testing.T) {
	data := writeBook(t, func(w *Writer) {
		noErr(t, mustSheet(t, w, "First").WriteRow("a", 1))
		s, err := w.AddSheetOptions("Secret", SheetOptions{Hidden: true})
		noErr(t, err)
		noErr(t, s.WriteRow("b", 2))
		noErr(t, mustSheet(t, w, "Q&A <1>").WriteRow("c", 3))
	})
	r := openBytes(t, data)
	if got := r.SheetNames(); !reflect.DeepEqual(got, []string{"First", "Secret", "Q&A <1>"}) {
		t.Errorf("SheetNames() = %q", got)
	}
	sheets := r.Sheets()
	if sheets[1].Visibility != SheetHidden || sheets[0].Visibility != SheetVisible || sheets[0].Kind != SheetKindWorksheet {
		t.Errorf("Sheets() = %+v", sheets)
	}
	wantRows(t, dump(t, data, "q&a <1>"), dr(1, "c", "3"))
	wantRows(t, dump(t, data, "Secret"), dr(1, "b", "2"))
	_, err := r.Rows("Missing")
	wantErr(t, err, ErrSheetNotFound)
	_, err = r.RowsAt(3)
	wantErr(t, err, ErrSheetNotFound)
	_, err = r.RowsAt(-1)
	wantErr(t, err, ErrSheetNotFound)
}

func TestReadSkipsMissingAndValuelessRows(t *testing.T) {
	data := writeBook(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		noErr(t, s.WriteRow("first"))
		noErr(t, s.BeginRow(RowOptions{Index: 5, Height: 30}))
		noErr(t, s.EndRow())
		noErr(t, s.BeginRow(RowOptions{Index: 1000}))
		s.SkipCells(2)
		s.WriteString("C1000")
		noErr(t, s.EndRow())
		noErr(t, s.BeginRow(RowOptions{Index: MaxRows}))
		s.SkipCells(MaxColumns - 1)
		s.WriteString("last")
		noErr(t, s.EndRow())
	})
	rows, err := openBytes(t, data).Rows("S")
	noErr(t, err)
	var seen [][3]int
	var values []Value
	for rows.Next() {
		row := rows.Row()
		values = row.AppendValues(values[:0])
		seen = append(seen, [3]int{row.Index(), row.LastColumn(), len(values)})
	}
	noErr(t, rows.Err())
	want := [][3]int{{1, 1, 1}, {1000, 3, 3}, {MaxRows, MaxColumns, MaxColumns}}
	if !reflect.DeepEqual(seen, want) {
		t.Errorf("got %v, want %v", seen, want)
	}
	if values[MaxColumns-1].Text() != "last" || !values[0].IsEmpty() {
		t.Errorf("last row values %v ... %v", values[0], values[MaxColumns-1])
	}
}

func TestReadXMLVariantsFromOtherProducers(t *testing.T) {
	// Prefixed namespaces, no cell or row references, out-of-order and duplicate cells, inline
	// rich text with phonetic runs, ISO dates, CDATA, comments, entities and CRLF line endings.
	sheet := "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<x:worksheet xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
		`<x:dimension ref="A1:C3"/><x:sheetViews><x:sheetView workbookViewId="0"/></x:sheetViews>` +
		`<x:sheetData>` +
		`<x:row><x:c><x:v>1</x:v></x:c><x:c t="str"><x:f>"a"&amp;"b"</x:f><x:v>ab</x:v></x:c><x:c t="b"><x:v>0</x:v></x:c></x:row>` +
		`<x:row r="3" spans="1:3"><x:c r="C3"><x:v>3</x:v></x:c><x:c r="A3"><x:v>1</x:v></x:c><x:c r="C3"><x:v>4</x:v></x:c></x:row>` +
		`<!-- a comment --><x:row><x:c t="inlineStr"><x:is><x:r><x:t>Tok</x:t></x:r><x:r><x:rPr><x:b/></x:rPr><x:t xml:space="preserve">yo &amp; </x:t></x:r><x:rPh><x:t>トウキョウ</x:t></x:rPh></x:is></x:c>` +
		`<x:c t="d"><x:v>2024-05-01T08:30:00</x:v></x:c><x:c t="inlineStr"><x:is><x:t><![CDATA[<cdata> & _x0041_]]></x:t></x:is></x:c>` +
		"<x:c t=\"inlineStr\"><x:is><x:t>line1\r\nline2</x:t></x:is></x:c><x:c t=\"e\"><x:v>#N/A</x:v></x:c><x:c t=\"n\"><x:v> 1.5E3 </x:v></x:c>" +
		`<x:c t="d"><x:v>not a date</x:v></x:c><x:c s="1"><x:v>2&#46;5</x:v></x:c></x:row>` +
		`</x:sheetData><x:mergeCells count="0"/></x:worksheet>`
	wantRows(t, dump(t, singleSheet(t, sheet, ""), "S"),
		dr(1, "1", "ab", "FALSE"),
		dr(3, "1", "", "4"),
		dr(4, "Tokyo & ", "2024-05-01T08:30:00", "<cdata> & A", "line1\nline2", "#N/A", "1500", "not a date", "2.5"),
	)
}

func TestReadAppliesCustomAndBuiltinDateFormats(t *testing.T) {
	styles := `<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="200" formatCode="[$-409]d\-mmm\-yy;@"/></numFmts><cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="200"/><xf numFmtId="14"/><xf numFmtId="4"/></cellXfs></styleSheet>`
	sheet := `<worksheet><sheetData><row r="1"><c r="A1" s="1"><v>45413</v></c><c r="B1" s="2"><v>45413</v></c><c r="C1" s="3"><v>45413</v></c><c r="D1" s="99"><v>1</v></c></row></sheetData></worksheet>`
	rels := `<Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="/xl/styles.xml"/>`
	wantRows(t, dump(t, singleSheet(t, sheet, rels, "xl/styles.xml", styles), "S"), dr(1, "2024-05-01", "2024-05-01", "45413", "1"))
}

func TestReadStrictOpenXMLAndUnusualPartNames(t *testing.T) {
	root := `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument" Target="/Book/WB.xml"/></Relationships>`
	workbook := `<workbook xmlns="http://purl.oclc.org/ooxml/spreadsheetml/main" xmlns:r="http://purl.oclc.org/ooxml/officeDocument/relationships"><workbookPr date1904="true"/><sheets><sheet name="Strict" sheetId="1" state="veryHidden" r:id="rIdA"/><sheet name="Chart" sheetId="2" r:id="rIdC"/></sheets></workbook>`
	rels := `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdA" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/worksheet" Target="sheets/../sheets/One.xml"/><Relationship Id="rIdC" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/chartsheet" Target="charts/c.xml"/><Relationship Id="rIdS" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/sharedStrings" Target="strings.xml"/><Relationship Id="rIdX" Type="http://x/hyperlink" Target="https://example.com" TargetMode="External"/></Relationships>`
	sst := `<sst xmlns="http://purl.oclc.org/ooxml/spreadsheetml/main"><si><t>strict</t></si></sst>`
	sheet := `<worksheet xmlns="http://purl.oclc.org/ooxml/spreadsheetml/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="d"><v>1904-01-02</v></c></row></sheetData></worksheet>`
	data := pkg(t, "_rels/.rels", root, "Book/WB.xml", workbook, "Book/_rels/WB.xml.rels", rels, "Book/sheets/One.xml", sheet, "Book/strings.xml", sst, "Book/charts/c.xml", "<chartsheet/>")
	r := openBytes(t, data)
	sheets := r.Sheets()
	if !r.Date1904() || sheets[0].Visibility != SheetVeryHidden || sheets[1].Kind != SheetKindChartsheet {
		t.Fatalf("1904 %v, sheets %+v", r.Date1904(), sheets)
	}
	rows, err := r.Rows("Strict")
	noErr(t, err)
	row := mustNext(t, rows)
	wantValue(t, row.Get(1), ValueString, "strict")
	if got, _ := row.Get(2).Time(); !got.Equal(time.Date(1904, 1, 2, 0, 0, 0, 0, time.UTC)) {
		t.Errorf("1904 date %v", got)
	}
	_, err = r.Rows("Chart")
	wantErr(t, err, ErrUnsupported)
}

func TestReadReportsClearErrors(t *testing.T) {
	open := func(data []byte) error {
		_, err := NewReader(bytes.NewReader(data), int64(len(data)))
		return err
	}
	err := open([]byte("id,name\n1,Alice\n"))
	wantErr(t, err, ErrInvalidFile)

	ole := append([]byte{0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1}, make([]byte, 504)...)
	err = open(ole)
	wantErr(t, err, ErrInvalidFile)
	contains(t, err.Error(), "legacy .xls", "encrypted")

	docx := pkg(t, "_rels/.rels", strings.Replace(rootRels, "xl/workbook.xml", "word/document.xml", 1), "word/document.xml", "<w/>")
	err = open(docx)
	wantErr(t, err, ErrInvalidFile)
	contains(t, err.Error(), "Word or PowerPoint")

	err = open(pkg(t, "xl/workbook.xml", "<document/>"))
	wantErr(t, err, ErrInvalidFile)

	err = rowsError(t, singleSheet(t, `<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>7</v></c></row></sheetData></worksheet>`, ""))
	var re *ReadError
	if !errors.As(err, &re) || re.Part != "xl/worksheets/sheet1.xml" || !errors.Is(err, ErrMalformed) {
		t.Errorf("bad shared string index: %v", err)
	}
	contains(t, err.Error(), `row 1 has an invalid shared string index "7"`)

	for xml, message := range map[string]string{
		`<worksheet><sheetData><row r="1"><c r="A1"><v>1,5</v></c></row></sheetData></worksheet>`:               `invalid number "1,5"`,
		`<worksheet><sheetData><row r="2"><c r="A2"><v>NaN</v></c></row></sheetData></worksheet>`:               `row 2 has an invalid number "NaN"`,
		`<worksheet><sheetData><row r="1"><c r="A1" t="b"><v>yes</v></c></row></sheetData></worksheet>`:         `invalid boolean "yes"`,
		`<worksheet><sheetData><row r="1"><c r="1A"><v>1</v></c></row></sheetData></worksheet>`:                 `invalid reference`,
		`<worksheet><sheetData><row r="1"><c r="XFE1"><v>1</v></c></row></sheetData></worksheet>`:               `invalid reference`,
		`<worksheet><sheetData><row r="1"><c t="x"><v>1</v></c></row></sheetData></worksheet>`:                  `unknown type`,
		`<worksheet><sheetData><row r="0"><c><v>1</v></c></row></sheetData></worksheet>`:                        `outside 1..1048576`,
		`<worksheet><sheetData><row r="x"><c><v>1</v></c></row></sheetData></worksheet>`:                        `invalid row number`,
		`<!DOCTYPE lol [<!ENTITY a "aaaa">]><worksheet><sheetData/></worksheet>`:                                `document type`,
		`<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c>`:                                               `unexpected end`,
		`<worksheet><sheetData><row><c t="inlineStr"><is><t>&bogus;</t></is></c></row></sheetData></worksheet>`: `unknown entity`,
		"<worksheet><sheetData><row><c t=\"inlineStr\"><is><t>\xff</t></is></c></row></sheetData></worksheet>":  `not valid UTF-8`,
		"\xff\xfe<\x00w\x00/\x00>\x00": `UTF-16`,
	} {
		err := rowsError(t, singleSheet(t, xml, ""))
		if !strings.Contains(err.Error(), message) || !strings.HasPrefix(err.Error(), "hypaxel: xl/worksheets/sheet1.xml: ") {
			t.Errorf("%s:\n got %v\nwant %s", xml, err, message)
		}
	}

	missing := pkg(t, "_rels/.rels", rootRels, "xl/workbook.xml", `<workbook><sheets><sheet name="S" r:id="rId1"/></sheets></workbook>`,
		"xl/_rels/workbook.xml.rels", `<Relationships><Relationship Id="rId1" Type="x/worksheet" Target="worksheets/gone.xml"/></Relationships>`)
	_, err = openBytes(t, missing).Rows("S")
	wantErr(t, err, ErrMalformed)
	contains(t, err.Error(), "xl/worksheets/gone.xml", "missing")

	noRel := pkg(t, "xl/workbook.xml", `<workbook><sheets><sheet name="S"/></sheets></workbook>`)
	_, err = openBytes(t, noRel).Rows("S")
	wantErr(t, err, ErrMalformed)
	contains(t, err.Error(), "no relationship")
}

func TestReadEnforcesTheSharedStringLimit(t *testing.T) {
	data := writeBook(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		for i := range 1000 {
			noErr(t, s.WriteRow(fmt.Sprint("unique string number ", i)))
		}
	}, WithSharedStrings(true))
	_, err := openBytes(t, data, WithMaxSharedStringsBytes(1000)).Rows("S")
	wantErr(t, err, ErrLimitExceeded)
	var re *ReadError
	if !errors.As(err, &re) || re.Part != "xl/sharedStrings.xml" {
		t.Errorf("error %v names no part", err)
	}
	rows, err := openBytes(t, data).Rows("S")
	noErr(t, err)
	n := 0
	for rows.Next() {
		n++
	}
	if n != 1000 || rows.Err() != nil {
		t.Errorf("%d rows, %v", n, rows.Err())
	}
}

func TestReadSheetsConcurrently(t *testing.T) {
	data := writeBook(t, func(w *Writer) {
		for _, name := range []string{"A", "B", "C"} {
			s := mustSheet(t, w, name)
			for i := range 2000 {
				noErr(t, s.WriteRow("in "+name, i))
			}
		}
	}, WithSharedStrings(true))
	r := openBytes(t, data)
	var wg sync.WaitGroup
	errs := make([]error, 6)
	for i := range errs {
		wg.Go(func() {
			name := string(rune('A' + i%3))
			rows, err := r.Rows(name)
			if err != nil {
				errs[i] = err
				return
			}
			n := 0
			for row, err := range rows.All() {
				if err != nil {
					errs[i] = err
					return
				}
				if row.Get(1).Text() != "in "+name || row.Index() != n+1 {
					errs[i] = fmt.Errorf("sheet %s row %d: %v", name, row.Index(), row.Get(1))
					return
				}
				n++
			}
			if n != 2000 {
				errs[i] = fmt.Errorf("sheet %s: %d rows", name, n)
			}
		})
	}
	wg.Wait()
	noErr(t, errors.Join(errs...))
}

func TestReadStreamsLargeSheets(t *testing.T) {
	const count = 50_000
	data := writeBook(t, func(w *Writer) {
		s := mustSheet(t, w, "Big")
		for i := range count {
			s.WriteInt(int64(i))
			s.WriteString("constant text")
			s.WriteFloat(float64(i) * 0.5)
			s.WriteBool(i%2 == 0)
			noErr(t, s.EndRow())
		}
	})
	rows, err := openBytes(t, data).Rows("Big")
	noErr(t, err)
	n, sum, want := 0, 0.0, 0.0
	for rows.Next() {
		row := rows.Row()
		if row.Index() != n+1 {
			t.Fatalf("row %d at position %d", row.Index(), n)
		}
		v, _ := row.Get(3).Float()
		sum += v
		want += float64(n) * 0.5
		n++
	}
	noErr(t, rows.Err())
	if n != count || sum != want {
		t.Errorf("%d rows, sum %v, want %v", n, sum, want)
	}
}

func TestReadIteratorStopsEarlyAndCloses(t *testing.T) {
	data := writeBook(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		for i := range 10 {
			noErr(t, s.WriteRow(i))
		}
	})
	rows, err := openBytes(t, data).Rows("S")
	noErr(t, err)
	for row, err := range rows.All() {
		noErr(t, err)
		if row.Index() == 3 {
			break
		}
	}
	if rows.Next() {
		t.Error("Next after the loop ended")
	}
	noErr(t, rows.Close())
}

func TestOpenReaderFromFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "book.xlsx")
	noErr(t, os.WriteFile(path, writeBook(t, func(w *Writer) { noErr(t, mustSheet(t, w, "S").WriteRow("x")) }), 0o600))
	r, err := OpenReader(path)
	noErr(t, err)
	rows, err := r.Rows("S")
	noErr(t, err)
	if !rows.Next() || rows.Row().Get(1).Text() != "x" {
		t.Errorf("row: %v", rows.Err())
	}
	noErr(t, r.Close())
	noErr(t, r.Close())

	if _, err := OpenReader(filepath.Join(t.TempDir(), "missing.xlsx")); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("missing file: %v", err)
	}
	bad := filepath.Join(t.TempDir(), "bad.xlsx")
	noErr(t, os.WriteFile(bad, []byte("not a zip"), 0o600))
	if _, err := OpenReader(bad); !errors.Is(err, ErrInvalidFile) {
		t.Errorf("bad file: %v", err)
	}
}

// storedBook is a workbook without compression, so that damage reaches the XML parser and not
// only the ZIP CRC check.
func storedBook(t testing.TB) []byte {
	return writeBook(t, func(w *Writer) {
		date := w.AddStyle(Style{NumberFormat: FormatDate})
		s, _ := w.AddSheet("S")
		for i := range 50 {
			s.WriteString(fmt.Sprint("s", i))
			s.WriteInt(int64(i))
			s.WriteFloat(float64(45_000+i), date)
			s.WriteBool(i%3 == 0)
			s.EndRow()
		}
	}, WithSharedStrings(true), WithCompressionLevel(0))
}

// readEverything reads every sheet of data and formats every value; it must not panic or hang.
func readEverything(data []byte, opts ...ReadOption) error {
	r, err := NewReader(bytes.NewReader(data), int64(len(data)), append([]ReadOption{WithMaxSharedStringsBytes(1 << 20)}, opts...)...)
	if err != nil {
		return err
	}
	for i := range r.Sheets() {
		if tables, err := r.TablesAt(i); err == nil {
			for _, table := range tables {
				_ = table.Style.String()
			}
		}
		rows, err := r.RowsAt(i)
		if err != nil {
			continue
		}
		if layout, err := rows.Layout(); err == nil {
			layout.ColumnWidth(1)
			layout.ColumnHidden(MaxColumns)
		}
		for row, err := range rows.All() {
			if err != nil {
				return err
			}
			for _, v := range row.Cells() {
				_ = v.String()
				v.Time()
				v.Duration()
			}
		}
	}
	return nil
}

func TestReadDamagedFilesFailWithoutPanicking(t *testing.T) {
	original := storedBook(t)
	noErr(t, readEverything(original))
	state := uint64(0x2545_f491_4f6c_dd1d)
	next := func() uint64 {
		state ^= state << 13
		state ^= state >> 7
		state ^= state << 17
		return state
	}
	for i := range 2000 {
		damaged := bytes.Clone(original)
		for range 1 + next()%4 {
			damaged[next()%uint64(len(damaged))] = byte(next())
		}
		if next()%4 == 0 {
			damaged = damaged[:next()%uint64(len(damaged))]
		}
		_ = readEverything(damaged, WithConcurrentInflate(i%2 == 0))
	}
}

func TestReadDetectsZipDamage(t *testing.T) {
	data := writeBook(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		for i := range 2000 {
			noErr(t, s.WriteRow(i, "some text that compresses"))
		}
	})
	r := openBytes(t, data)
	f := r.find("xl/worksheets/sheet1.xml")
	offset, err := f.DataOffset()
	noErr(t, err)

	sheetEntry := centralEntry(t, data, "xl/worksheets/sheet1.xml")
	corrupt := bytes.Clone(data)
	corrupt[offset+int64(f.CompressedSize64)/2] ^= 0xFF
	// A central directory entry that understates the uncompressed size, as a zip bomb would.
	understated := bytes.Clone(data)
	binary.LittleEndian.PutUint32(understated[sheetEntry+24:], 100)
	overstated := bytes.Clone(data)
	binary.LittleEndian.PutUint32(overstated[sheetEntry+24:], uint32(f.UncompressedSize64)+1)
	badCRC := bytes.Clone(data)
	badCRC[sheetEntry+16] ^= 0xFF
	truncated := bytes.Clone(data)
	binary.LittleEndian.PutUint32(truncated[sheetEntry+20:], uint32(f.CompressedSize64)/2)

	for _, tc := range []struct {
		name  string
		data  []byte
		cause error
	}{
		{"corrupt deflate data", corrupt, nil},
		{"understated size", understated, zip.ErrFormat},
		{"overstated size", overstated, io.ErrUnexpectedEOF},
		{"CRC mismatch", badCRC, zip.ErrChecksum},
		{"truncated compressed data", truncated, nil},
	} {
		var messages []string
		for _, concurrent := range []bool{false, true} {
			err := rowsError(t, tc.data, WithConcurrentInflate(concurrent))
			wantErr(t, err, ErrMalformed)
			if tc.cause != nil && !errors.Is(err, tc.cause) {
				t.Errorf("%s: %v does not wrap %v", tc.name, err, tc.cause)
			}
			var re *ReadError
			if !errors.As(err, &re) || re.Part != "xl/worksheets/sheet1.xml" {
				t.Errorf("%s: %v names no part", tc.name, err)
			}
			messages = append(messages, err.Error())
		}
		if messages[0] != messages[1] {
			t.Errorf("%s: the error depends on concurrent inflate:\n%s\n%s", tc.name, messages[0], messages[1])
		}
	}

	for n := range len(data) {
		if n%97 == 0 || n > len(data)-200 {
			_ = readEverything(data[:n], WithConcurrentInflate(n%2 == 0))
		}
	}
}

// centralEntry returns the offset of the central directory header of a ZIP entry.
func centralEntry(t *testing.T, data []byte, name string) int {
	t.Helper()
	for at := 0; ; at++ {
		i := bytes.Index(data[at:], []byte("PK\x01\x02"))
		if i < 0 {
			t.Fatalf("no central directory entry for %s", name)
		}
		at += i
		nameLen := int(binary.LittleEndian.Uint16(data[at+28:]))
		if string(data[at+46:at+46+nameLen]) == name {
			return at
		}
	}
}

func TestReadRejectsUnsupportedZipEntries(t *testing.T) {
	data := singleSheet(t, `<worksheet><sheetData/></worksheet>`, "")
	cdOffset := centralEntry(t, data, "xl/worksheets/sheet1.xml")
	encrypted := bytes.Clone(data)
	encrypted[cdOffset+8] |= 1
	_, err := openBytes(t, encrypted).Rows("S")
	wantErr(t, err, ErrUnsupported)
	contains(t, err.Error(), "encrypted")

	method := bytes.Clone(data)
	binary.LittleEndian.PutUint16(method[cdOffset+10:], 12)
	_, err = openBytes(t, method).Rows("S")
	wantErr(t, err, ErrUnsupported)
	contains(t, err.Error(), "compression method 12")
}

func FuzzReader(f *testing.F) {
	f.Add(storedBook(f))
	f.Add(writeBook(f, func(w *Writer) {
		s, _ := w.AddSheet("S")
		s.WriteRow("a", 1, true, time.Date(2024, 5, 1, 8, 30, 0, 0, time.UTC), 36*time.Hour)
	}))
	f.Add(singleSheet(f, `<worksheet><sheetData><row><c t="inlineStr"><is><r><t>a</t></r></is></c><c t="d"><v>2024-05-01</v></c></row></sheetData></worksheet>`, ""))
	f.Fuzz(func(t *testing.T, data []byte) {
		_ = readEverything(data)
	})
}

func FuzzSheetXML(f *testing.F) {
	f.Add(`<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c t="inlineStr"><is><t>x_x0041_</t></is></c></row></sheetData></worksheet>`)
	f.Add(`<x:worksheet xmlns:x="a"><x:sheetData><x:row><x:c t="d"><x:v>2024-05-01T08:30:00</x:v></x:c><x:c t="e"><x:v>#N/A</x:v></x:c></x:row></x:sheetData></x:worksheet>`)
	f.Fuzz(func(t *testing.T, sheet string) {
		sst := `<sst><si><t>shared</t></si></sst>`
		rels := `<Relationship Id="rId2" Type="x/sharedStrings" Target="sharedStrings.xml"/>`
		_ = readEverything(singleSheet(t, sheet, rels, "xl/sharedStrings.xml", sst))
	})
}

func FuzzTableXML(f *testing.F) {
	f.Add(`<table displayName="T" ref="A1:B3" totalsRowCount="1"><autoFilter ref="A1:B2"/><tableColumns><tableColumn name="a_x000A_b"/><tableColumn name="c"><f>1</f></tableColumn></tableColumns><tableStyleInfo name="TableStyleDark3" showRowStripes="1"/></table>`)
	f.Add(`<x:table xmlns:x="a" name="T" ref="$B$2" headerRowCount="0"><x:tableStyleInfo name="Custom"/></x:table>`)
	f.Fuzz(func(t *testing.T, table string) {
		rels := `<Relationships><Relationship Id="rId1" Type="x/table" Target="../tables/t.xml"/></Relationships>`
		sheet := `<worksheet><cols><col min="1" max="2" width="9" hidden="1"/></cols><sheetData><row ht="20" outlineLevel="1"><c><v>1</v></c></row></sheetData></worksheet>`
		_ = readEverything(singleSheet(t, sheet, "", "xl/worksheets/_rels/sheet1.xml.rels", rels, "xl/tables/t.xml", table))
	})
}

func FuzzSharedStrings(f *testing.F) {
	f.Add(`<sst><si><t>a</t></si><si><t xml:space="preserve"> b </t></si><si><r><t>c</t></r><rPh><t>d</t></rPh></si></sst>`, uint8(9))
	f.Add(`<sst><si><t a='>'>x&amp;_x0041_</t></si><si><t/></si><si/></sst>`, uint8(0))
	f.Fuzz(func(t *testing.T, xml string, size uint8) {
		x := newXMLReader(strings.NewReader(xml), "sst.xml")
		x.buf = make([]byte, 8+int(size))
		sst, err := parseSharedStrings(x, 1<<20)
		if err == nil {
			for i := range sst.len() {
				_ = sst.get(uint32(i))
			}
		}
	})
}
