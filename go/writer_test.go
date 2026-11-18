package hypaxel

import (
	"bytes"
	"errors"
	"io"
	"math"
	"strings"
	"testing"
	"time"
)

func TestCompactAndExplicitReferences(t *testing.T) {
	write := func(w *Writer) {
		s := mustSheet(t, w, "S")
		s.WriteInt(1)
		s.WriteString("a")
		s.SkipCells(2)
		s.WriteBool(true)
		s.WriteFloat(2.5)
		noErr(t, s.EndRow())
		noErr(t, s.EndRow())
		s.WriteUint(7)
		noErr(t, s.EndRow())
	}
	compact := sheetData(t, book(t, write), 1)
	want := `<row r="1"><c><v>1</v></c><c t="inlineStr"><is><t>a</t></is></c><c r="E1" t="b"><v>1</v></c><c><v>2.5</v></c></row>` +
		`<row r="3"><c><v>7</v></c></row>`
	if compact != want {
		t.Errorf("compact:\n got %s\nwant %s", compact, want)
	}
	explicit := sheetData(t, book(t, write, WithCellReferences(true)), 1)
	want = `<row r="1"><c r="A1"><v>1</v></c><c r="B1" t="inlineStr"><is><t>a</t></is></c><c r="E1" t="b"><v>1</v></c><c r="F1"><v>2.5</v></c></row>` +
		`<row r="3"><c r="A3"><v>7</v></c></row>`
	if explicit != want {
		t.Errorf("explicit:\n got %s\nwant %s", explicit, want)
	}
}

func TestCellKinds(t *testing.T) {
	var bold StyleID
	parts := book(t, func(w *Writer) {
		bold = w.AddStyle(Style{Font: Font{Bold: true}})
		s := mustSheet(t, w, "S")
		s.WriteFloat(math.NaN())
		s.WriteFloat(math.Inf(-1), bold)
		s.WriteFormula("=A1&\"<\"")
		s.WriteFormula("SUM(A1:A2)", bold)
		s.WriteFormula("")
		s.WriteBlank()
		s.WriteBlank(bold)
		s.WriteBool(false, bold)
		s.WriteInt(-5, bold)
		s.WriteUint(math.MaxUint64)
		s.WriteString(" padded ", bold)
		noErr(t, s.EndRow())
	})
	got := sheetData(t, parts, 1)
	want := `<row r="1"><c t="e"><v>#NUM!</v></c><c s="1" t="e"><v>#NUM!</v></c><c><f>A1&amp;&quot;&lt;&quot;</f></c>` +
		`<c s="1"><f>SUM(A1:A2)</f></c><c><f></f></c><c r="G1" s="1"/><c s="1" t="b"><v>0</v></c><c s="1"><v>-5</v></c>` +
		`<c><v>18446744073709551615</v></c><c s="1" t="inlineStr"><is><t xml:space="preserve"> padded </t></is></c></row>`
	if got != want {
		t.Errorf("\n got %s\nwant %s", got, want)
	}
	contains(t, parts["xl/workbook.xml"], `<calcPr calcId="191029" fullCalcOnLoad="1"/>`)
}

func TestDateSerials(t *testing.T) {
	plus2 := time.FixedZone("plus2", 2*3600)
	row := func(date1904 bool) string {
		return sheetData(t, book(t, func(w *Writer) {
			s := mustSheet(t, w, "S")
			s.WriteTime(time.Date(2024, 1, 1, 12, 0, 0, 0, time.UTC))
			s.WriteTime(time.Date(2024, 1, 1, 12, 0, 0, 0, plus2))
			s.WriteDate(time.Date(2024, 1, 1, 23, 59, 59, 0, plus2))
			s.WriteTime(time.Date(1900, 1, 1, 0, 0, 0, 0, time.UTC))
			s.WriteTime(time.Date(1900, 2, 28, 0, 0, 0, 0, time.UTC))
			s.WriteTime(time.Date(1900, 3, 1, 0, 0, 0, 0, time.UTC))
			s.WriteTime(time.Date(1904, 1, 1, 0, 0, 0, 0, time.UTC))
			s.WriteTime(time.Date(9999, 12, 31, 0, 0, 0, 0, time.UTC))
			s.WriteTime(time.Date(1899, 12, 31, 0, 0, 0, 0, time.UTC))
			s.WriteDate(time.Date(10000, 1, 1, 0, 0, 0, 0, time.UTC))
			s.WriteTime(time.Date(1650, 1, 2, 3, 4, 5, 600_000_000, time.UTC))
			s.WriteTime(time.Time{})
			s.WriteDuration(36 * time.Hour)
			s.WriteDuration(-90 * time.Second)
			noErr(t, s.EndRow())
		}, WithDate1904(date1904)), 1)
	}
	text := func(s string) string { return `<c t="inlineStr"><is><t>` + s + `</t></is></c>` }
	want1900 := `<row r="1"><c s="1"><v>45292.5</v></c><c s="1"><v>45292.5</v></c><c s="2"><v>45292</v></c>` +
		`<c s="1"><v>1</v></c><c s="1"><v>59</v></c><c s="1"><v>61</v></c><c s="1"><v>1462</v></c><c s="1"><v>2958465</v></c>` +
		text("1899-12-31T00:00:00") + text("10000-01-01") + text("1650-01-02T03:04:05.6") +
		`<c r="M1" s="3"><v>1.5</v></c><c s="3"><v>-0.0010416666666666667</v></c></row>`
	if got := row(false); got != want1900 {
		t.Errorf("1900 system:\n got %s\nwant %s", got, want1900)
	}
	want1904 := `<row r="1"><c s="1"><v>43830.5</v></c><c s="1"><v>43830.5</v></c><c s="2"><v>43830</v></c>` +
		text("1900-01-01T00:00:00") + text("1900-02-28T00:00:00") + text("1900-03-01T00:00:00") +
		`<c s="1"><v>0</v></c><c s="1"><v>2957003</v></c>` +
		text("1899-12-31T00:00:00") + text("10000-01-01") + text("1650-01-02T03:04:05.6") +
		`<c r="M1" s="3"><v>1.5</v></c><c s="3"><v>-0.0010416666666666667</v></c></row>`
	if got := row(true); got != want1904 {
		t.Errorf("1904 system:\n got %s\nwant %s", got, want1904)
	}
}

func TestSheetHeaderAndFooter(t *testing.T) {
	parts := book(t, func(w *Writer) {
		italic := w.AddStyle(Style{Font: Font{Italic: true}})
		s, err := w.AddSheetOptions("Über <1>", SheetOptions{
			Columns:       []Column{{Width: 20}, {}, {Width: 300, Style: italic}, {Hidden: true}},
			FrozenRows:    2,
			FrozenColumns: 1,
			HideGridLines: true,
			TabColor:      RGB(255, 165, 0),
		})
		noErr(t, err)
		noErr(t, s.BeginRow(RowOptions{Height: 30.5, Style: italic}))
		s.WriteString("x")
		noErr(t, s.BeginRow(RowOptions{Index: 5}))
		noErr(t, s.MergeCells(1, 1, 2, 3))
		noErr(t, s.AutoFilter(1, 1, 9, 4))
		noErr(t, s.AutoFilter(2, 1, 9, 4))

		rows, err := w.AddSheetOptions("it's", SheetOptions{FrozenRows: 1, Hidden: true})
		noErr(t, err)
		noErr(t, rows.AutoFilter(1, 1, 1, 2))
		_, err = w.AddSheetOptions("cols", SheetOptions{FrozenColumns: 2})
		noErr(t, err)
	})
	contains(t, parts["xl/worksheets/sheet1.xml"],
		`<sheetPr><tabColor rgb="FFFFA500"/></sheetPr>`,
		`<sheetView workbookViewId="0" tabSelected="1" showGridLines="0"><pane xSplit="1" ySplit="2" topLeftCell="B3" activePane="bottomRight" state="frozen"/><selection pane="bottomRight"/></sheetView>`,
		`<cols><col min="1" max="1" width="20" customWidth="1"/><col min="3" max="3" width="255" customWidth="1" style="1"/><col min="4" max="4" width="9.140625" hidden="1"/></cols>`,
		`<sheetData><row r="1" ht="30.5" customHeight="1" s="1" customFormat="1"><c t="inlineStr"><is><t>x</t></is></c></row><row r="5"></row></sheetData>`,
		`</sheetData><autoFilter ref="A2:D9"/><mergeCells count="1"><mergeCell ref="A1:C2"/></mergeCells><pageMargins`,
	)
	contains(t, parts["xl/worksheets/sheet2.xml"], `<sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft"/>`)
	contains(t, parts["xl/worksheets/sheet3.xml"], `<pane xSplit="2" topLeftCell="C1" activePane="topRight" state="frozen"/>`, `<sheetData></sheetData>`)
	contains(t, parts["xl/workbook.xml"],
		`<bookViews><workbookView activeTab="0"/></bookViews>`,
		`<sheet name="Über &lt;1&gt;" sheetId="1" r:id="rId1"/><sheet name="it's" sheetId="2" state="hidden" r:id="rId2"/><sheet name="cols" sheetId="3" r:id="rId3"/>`,
		`<definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'Über &lt;1&gt;'!$A$2:$D$9</definedName>`,
		`<definedName name="_xlnm._FilterDatabase" localSheetId="1" hidden="1">'it''s'!$A$1:$B$1</definedName></definedNames>`,
		`<calcPr calcId="191029"/>`,
	)
	contains(t, parts["xl/_rels/workbook.xml.rels"], `Target="worksheets/sheet3.xml"`, `<Relationship Id="rId4"`, `Target="styles.xml"`)
	contains(t, parts["[Content_Types].xml"], `/xl/worksheets/sheet3.xml`, `/xl/styles.xml`)
	contains(t, parts["_rels/.rels"], `Target="xl/workbook.xml"`)
	if _, ok := parts["xl/sharedStrings.xml"]; ok {
		t.Error("inline strings must not produce a shared string part")
	}
}

func TestSharedStrings(t *testing.T) {
	parts := book(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		for _, text := range []string{"a", "b", "a", " c<", "a"} {
			s.WriteString(text)
		}
		s.WriteInt(1)
		noErr(t, s.EndRow())
		for i := range 5000 {
			s.WriteString(strings.Repeat("row ", 5) + string(rune('a'+i%26)) + ColumnName(i+1))
			noErr(t, s.EndRow())
		}
	}, WithSharedStrings(true))
	contains(t, sheetData(t, parts, 1), `<row r="1"><c t="s"><v>0</v></c><c t="s"><v>1</v></c><c t="s"><v>0</v></c><c t="s"><v>2</v></c><c t="s"><v>0</v></c><c><v>1</v></c></row>`)
	contains(t, parts["xl/sharedStrings.xml"],
		`count="5005" uniqueCount="5003"><si><t>a</t></si><si><t>b</t></si><si><t xml:space="preserve"> c&lt;</t></si>`)
	contains(t, parts["xl/_rels/workbook.xml.rels"], `<Relationship Id="rId3"`, `Target="sharedStrings.xml"`)
	contains(t, parts["[Content_Types].xml"], `/xl/sharedStrings.xml`)

	numbersOnly := book(t, func(w *Writer) {
		noErr(t, mustSheet(t, w, "S").WriteRow(1))
	}, WithSharedStrings(true))
	if _, ok := numbersOnly["xl/sharedStrings.xml"]; ok {
		t.Error("a workbook without text must not have a shared string part")
	}
}

func TestEmptyWorkbookGetsASheet(t *testing.T) {
	parts := book(t, func(*Writer) {})
	contains(t, parts["xl/workbook.xml"], `<sheet name="Sheet1" sheetId="1" r:id="rId1"/>`)
	contains(t, parts["xl/worksheets/sheet1.xml"], `<sheetData></sheetData>`)
}

func TestFirstVisibleSheetIsActive(t *testing.T) {
	parts := book(t, func(w *Writer) {
		_, err := w.AddSheetOptions("hidden", SheetOptions{Hidden: true})
		noErr(t, err)
		mustSheet(t, w, "shown")
	}, WithDate1904(true))
	contains(t, parts["xl/workbook.xml"], `<workbookPr date1904="1"/><bookViews><workbookView activeTab="1"/>`)
}

func TestSheetNameRules(t *testing.T) {
	w := NewWriter(io.Discard)
	for _, name := range []string{"", strings.Repeat("x", 32), strings.Repeat("😀", 16), "a:b", `a\b`, "a/b", "a?b", "a*b", "a[b", "a]b", "'a", "a'", "History", "hIsToRy"} {
		_, err := w.AddSheet(name)
		wantErr(t, err, ErrInvalidSheetName)
	}
	for _, name := range []string{strings.Repeat("x", 31), strings.Repeat("ä", 31), "it's ok", "Sheet (1)"} {
		_, err := w.AddSheet(name)
		noErr(t, err)
	}
	_, err := w.AddSheet("IT'S OK")
	wantErr(t, err, ErrDuplicateSheetName)
	noErr(t, w.Close())
}

func TestSheetOptionValidation(t *testing.T) {
	w := NewWriter(io.Discard)
	for _, opts := range []SheetOptions{
		{FrozenRows: -1},
		{FrozenRows: MaxRows},
		{FrozenColumns: -1},
		{FrozenColumns: MaxColumns},
		{Columns: make([]Column, MaxColumns+1)},
	} {
		_, err := w.AddSheetOptions("S", opts)
		wantErr(t, err, ErrInvalidArgument)
	}
	_, err := w.AddSheetOptions("S", SheetOptions{Columns: []Column{{Style: 9}}})
	wantErr(t, err, ErrInvalidStyle)
	_, err = w.AddSheetOptions("S", SheetOptions{Columns: make([]Column, MaxColumns), FrozenRows: MaxRows - 1, FrozenColumns: MaxColumns - 1})
	noErr(t, err)
	noErr(t, w.Close())
}

func TestRowValidation(t *testing.T) {
	w := NewWriter(io.Discard)
	s := mustSheet(t, w, "S")
	noErr(t, s.BeginRow(RowOptions{Index: 3}))
	if s.Row() != 3 || s.Column() != 0 {
		t.Errorf("position = %d,%d", s.Row(), s.Column())
	}
	s.WriteInt(1)
	if s.Column() != 1 {
		t.Errorf("column = %d", s.Column())
	}
	wantErr(t, s.BeginRow(RowOptions{Index: 3}), ErrRowOrder)
	wantErr(t, s.BeginRow(RowOptions{Index: 2}), ErrRowOrder)
	wantErr(t, s.BeginRow(RowOptions{Index: -1}), ErrRowOutOfRange)
	wantErr(t, s.BeginRow(RowOptions{Index: MaxRows + 1}), ErrRowOutOfRange)
	wantErr(t, s.BeginRow(RowOptions{Style: 5}), ErrInvalidStyle)
	wantErr(t, s.BeginRow(RowOptions{Height: 410}), ErrInvalidArgument)
	wantErr(t, s.BeginRow(RowOptions{Height: -1}), ErrInvalidArgument)
	wantErr(t, s.BeginRow(RowOptions{Height: math.NaN()}), ErrInvalidArgument)
	noErr(t, s.BeginRow(RowOptions{}))
	if s.Row() != 4 {
		t.Errorf("row = %d, want 4", s.Row())
	}
	for _, r := range [][4]int{{0, 1, 1, 1}, {1, 0, 1, 1}, {2, 1, 1, 1}, {1, 2, 1, 1}, {1, 1, MaxRows + 1, 1}, {1, 1, 1, MaxColumns + 1}} {
		wantErr(t, s.MergeCells(r[0], r[1], r[2], r[3]), ErrInvalidArgument)
		wantErr(t, s.AutoFilter(r[0], r[1], r[2], r[3]), ErrInvalidArgument)
	}
	wantErr(t, s.MergeCells(2, 2, 2, 2), ErrInvalidArgument)
	noErr(t, s.AutoFilter(2, 2, 2, 2))
	noErr(t, w.Err())
	noErr(t, w.Close())
}

func TestRowLimit(t *testing.T) {
	w := NewWriter(io.Discard)
	s := mustSheet(t, w, "S")
	s.SkipRows(MaxRows - 1)
	noErr(t, s.WriteRow("last row"))
	if s.Row() != MaxRows {
		t.Fatalf("row = %d", s.Row())
	}
	noErr(t, s.EndRow())
	s.SkipRows(10)
	noErr(t, w.Err())
	s.WriteString("one too many")
	wantErr(t, s.EndRow(), ErrRowOutOfRange)
	wantErr(t, s.WriteRow(1), ErrRowOutOfRange)
	wantErr(t, w.Close(), ErrRowOutOfRange)
}

func TestColumnLimit(t *testing.T) {
	w := NewWriter(io.Discard)
	s := mustSheet(t, w, "S")
	s.SkipCells(MaxColumns - 1)
	s.WriteInt(1)
	noErr(t, s.EndRow())
	s.SkipCells(MaxColumns)
	s.WriteInt(1)
	wantErr(t, s.EndRow(), ErrTooManyColumns)

	w = NewWriter(io.Discard)
	s = mustSheet(t, w, "S")
	s.SkipCells(MaxColumns + 1)
	wantErr(t, w.Err(), ErrTooManyColumns)
}

func TestTextLimit(t *testing.T) {
	for _, shared := range []bool{false, true} {
		w := NewWriter(io.Discard, WithSharedStrings(shared))
		s := mustSheet(t, w, "S")
		s.WriteString(strings.Repeat("x", MaxCellLength))
		s.WriteString(strings.Repeat("é", MaxCellLength))
		s.WriteString(strings.Repeat("😀", MaxCellLength/2))
		noErr(t, s.EndRow())
		s.WriteString(strings.Repeat("😀", MaxCellLength/2+1))
		err := s.EndRow()
		wantErr(t, err, ErrTextTooLong)
		if !strings.Contains(err.Error(), "32768 characters at row 2, column 1") {
			t.Errorf("error lacks the position: %v", err)
		}
		wantErr(t, w.Close(), ErrTextTooLong)
	}
}

func TestNegativeSkips(t *testing.T) {
	w := NewWriter(io.Discard)
	mustSheet(t, w, "S").SkipCells(-1)
	wantErr(t, w.Err(), ErrInvalidArgument)
	w = NewWriter(io.Discard)
	mustSheet(t, w, "S").SkipRows(-1)
	wantErr(t, w.Err(), ErrInvalidArgument)
}

func TestInvalidStyleIsSticky(t *testing.T) {
	other := NewWriter(io.Discard)
	foreign := other.AddStyle(Style{Font: Font{Bold: true}})
	w := NewWriter(io.Discard)
	s := mustSheet(t, w, "S")
	s.WriteInt(1, foreign)
	s.WriteInt(2)
	s.WriteTime(time.Now(), foreign)
	wantErr(t, s.EndRow(), ErrInvalidStyle)
	_, err := w.AddSheet("next")
	wantErr(t, err, ErrInvalidStyle)
	wantErr(t, w.Flush(), ErrInvalidStyle)
	wantErr(t, w.Close(), ErrInvalidStyle)
	wantErr(t, w.Close(), ErrInvalidStyle)
}

func TestStaleSheetAndClosedWriter(t *testing.T) {
	w := NewWriter(io.Discard)
	first := mustSheet(t, w, "first")
	mustSheet(t, w, "second")
	first.WriteInt(1)
	wantErr(t, w.Err(), ErrSheetClosed)

	for name, use := range map[string]func(s *Sheet) error{
		"EndRow":            func(s *Sheet) error { return s.EndRow() },
		"BeginRow":          func(s *Sheet) error { return s.BeginRow(RowOptions{}) },
		"MergeCells":        func(s *Sheet) error { return s.MergeCells(1, 1, 1, 2) },
		"AutoFilter":        func(s *Sheet) error { return s.AutoFilter(1, 1, 1, 2) },
		"WriteRow":          func(s *Sheet) error { return s.WriteRow(1) },
		"WriteStrings":      func(s *Sheet) error { return s.WriteStrings([]string{"a"}) },
		"WriteStruct":       func(s *Sheet) error { return s.WriteStruct(struct{ A int }{1}) },
		"WriteStructHeader": func(s *Sheet) error { return s.WriteStructHeader(struct{ A int }{1}) },
		"SkipRows":          func(s *Sheet) error { s.SkipRows(1); return s.w.err },
		"SkipCells":         func(s *Sheet) error { s.SkipCells(1); return s.w.err },
		"Write":             func(s *Sheet) error { s.Write(struct{}{}); return s.w.err },
	} {
		w := NewWriter(io.Discard)
		s := mustSheet(t, w, "S")
		noErr(t, w.Close())
		if err := use(s); !errors.Is(err, ErrSheetClosed) {
			t.Errorf("%s after Close: %v, want ErrSheetClosed", name, err)
		}
	}

	w = NewWriter(io.Discard)
	noErr(t, w.Close())
	noErr(t, w.Close())
	_, err := w.AddSheet("late")
	wantErr(t, err, ErrClosed)
	wantErr(t, w.Flush(), ErrClosed)
	wantErr(t, WriteSheet(w, "late", []struct{ A int }{{1}}), ErrClosed)
}

func TestNoVisibleSheet(t *testing.T) {
	w := NewWriter(io.Discard)
	_, err := w.AddSheetOptions("hidden", SheetOptions{Hidden: true})
	noErr(t, err)
	wantErr(t, w.Close(), ErrNoVisibleSheet)
}

type failingWriter struct {
	limit, written, calls int
}

var errDisk = errors.New("disk full")

func (f *failingWriter) Write(p []byte) (int, error) {
	f.calls++
	if f.written+len(p) > f.limit {
		return 0, errDisk
	}
	f.written += len(p)
	return len(p), nil
}

func TestDestinationErrorIsSticky(t *testing.T) {
	dst := &failingWriter{limit: 4000}
	w := NewWriter(dst, WithFlushThreshold(1024), WithCompressionLevel(0))
	s := mustSheet(t, w, "S")
	var err error
	rows := 0
	for err == nil && rows < 100_000 {
		err = s.WriteRow(rows, "some text that fills the buffer")
		rows++
	}
	wantErr(t, err, errDisk)
	if rows == 100_000 {
		t.Fatal("the destination error never surfaced")
	}
	calls := dst.calls
	wantErr(t, s.WriteRow(1), errDisk)
	wantErr(t, w.Close(), errDisk)
	if dst.calls != calls {
		t.Errorf("the destination was written to %d more times after it failed", dst.calls-calls)
	}
}

type chunkRecorder struct {
	bytes.Buffer
	chunks []int
}

func (c *chunkRecorder) Write(p []byte) (int, error) {
	c.chunks = append(c.chunks, len(p))
	return c.Buffer.Write(p)
}

func TestFlushThresholdBoundsBuffering(t *testing.T) {
	for _, threshold := range []int{0, 8 * 1024, 256 * 1024} {
		var dst chunkRecorder
		w := NewWriter(&dst, WithFlushThreshold(threshold), WithCompressionLevel(0))
		s := mustSheet(t, w, "S")
		for i := range 20_000 {
			s.WriteInt(int64(i))
			s.WriteString("streamed while it is written")
			noErr(t, s.EndRow())
		}
		beforeClose := len(dst.chunks)
		noErr(t, w.Close())
		unzip(t, dst.Bytes())
		effective := max(threshold, minFlushThreshold)
		if beforeClose < 2 {
			t.Errorf("threshold %d: only %d chunks reached the destination before Close", threshold, beforeClose)
		}
		for _, size := range dst.chunks[:beforeClose] {
			if size < effective || size > effective+2*xmlBufferSize {
				t.Errorf("threshold %d: chunk of %d bytes", threshold, size)
			}
		}
	}
}

func TestFlushSendsEverythingWritten(t *testing.T) {
	var dst bytes.Buffer
	w := NewWriter(&dst)
	noErr(t, w.Flush())
	if dst.Len() != 0 {
		t.Errorf("flushing an empty writer wrote %d bytes", dst.Len())
	}
	s := mustSheet(t, w, "S")
	noErr(t, s.WriteRow("visible to the reader early"))
	noErr(t, w.Flush())
	flushed := dst.Len()
	if flushed < 100 {
		t.Errorf("only %d bytes after Flush", flushed)
	}
	s.WriteString("open row")
	noErr(t, w.Flush())
	noErr(t, s.WriteRow("more"))
	noErr(t, w.Close())
	contains(t, sheetData(t, unzip(t, dst.Bytes()), 1), "visible to the reader early", "open row", "more")
}

func TestOptionsClamp(t *testing.T) {
	var c config
	WithCompressionLevel(42)(&c)
	if c.compressionLevel != 9 {
		t.Errorf("level = %d", c.compressionLevel)
	}
	WithCompressionLevel(-3)(&c)
	if c.compressionLevel != 0 {
		t.Errorf("level = %d", c.compressionLevel)
	}
	WithFlushThreshold(1)(&c)
	if c.flushThreshold != minFlushThreshold {
		t.Errorf("threshold = %d", c.flushThreshold)
	}
	columns := Widths(10, 0, 20.5)
	if len(columns) != 3 || columns[0].Width != 10 || columns[1] != (Column{}) || columns[2].Width != 20.5 {
		t.Errorf("Widths = %+v", columns)
	}
}

func TestLongInlineStringsAreCompressedEarly(t *testing.T) {
	var dst chunkRecorder
	w := NewWriter(&dst, WithCompressionLevel(0))
	s := mustSheet(t, w, "S")
	long := strings.Repeat("y", 30_000)
	for range 40 {
		s.WriteString(long)
	}
	if len(dst.chunks) == 0 {
		t.Error("a row of long strings must reach the destination before the row ends")
	}
	noErr(t, s.EndRow())
	noErr(t, w.Close())
	if got := strings.Count(sheetData(t, unzip(t, dst.Bytes()), 1), long); got != 40 {
		t.Errorf("%d long strings read back, want 40", got)
	}
}

func TestTypedWritesDoNotAllocate(t *testing.T) {
	for _, shared := range []bool{false, true} {
		w := NewWriter(io.Discard, WithSharedStrings(shared))
		money := w.AddStyle(Style{NumberFormat: FormatThousands2})
		s := mustSheet(t, w, "S")
		when := time.Date(2024, 5, 17, 13, 45, 30, 0, time.UTC)
		row := func() {
			s.WriteInt(42)
			s.WriteString("Customer 17")
			s.WriteString("needs <escaping> & more ü")
			s.WriteFloat(1234.5678, money)
			s.WriteFloat(0.1)
			s.WriteTime(when)
			s.WriteDate(when, money)
			s.WriteDuration(90 * time.Minute)
			s.WriteBool(true)
			s.WriteUint(7)
			s.WriteBlank(money)
			s.WriteFormula("A1+1")
			if err := s.EndRow(); err != nil {
				t.Fatal(err)
			}
		}
		for range 2000 {
			row()
		}
		if allocs := testing.AllocsPerRun(5000, row); allocs != 0 {
			t.Errorf("shared=%v: %v allocations per row, want 0", shared, allocs)
		}
		noErr(t, w.Close())
	}
}
