package tests

import (
	"bytes"
	"math"
	"slices"
	"strconv"
	"testing"
	"time"

	"github.com/xuri/excelize/v2"

	hypaxel "github.com/vensas/hypaxel/go"
)

// writeWorkload writes count rows of the benchmark workload to a file in dir.
func writeWorkload(t testing.TB, dir string, count int, opts ...hypaxel.Option) string {
	t.Helper()
	path, err := WriteReadInput(dir, count, opts...)
	if err != nil {
		t.Fatal(err)
	}
	return path
}

func TestReadersAgreeOnTheWorkload(t *testing.T) {
	const count = 20_000
	for name, opts := range map[string][]hypaxel.Option{
		"inline": nil,
		"shared": {hypaxel.WithSharedStrings(true)},
	} {
		t.Run(name, func(t *testing.T) {
			path := writeWorkload(t, t.TempDir(), count, opts...)
			want, err := HypaxelRead(path)
			check(t, err)
			if want.Rows != count || want.Cells != 10*count || want.Trues != count/2 {
				t.Fatalf("hypaxel: %v", want)
			}
			for _, c := range ReadCases {
				got, err := c.Run(path)
				if err != nil {
					t.Errorf("%s: %v", c.Name, err)
					continue
				}
				if !c.Matches(got, want) {
					t.Errorf("%s:\n got %v\nwant %v", c.Name, got, want)
				}
			}
		})
	}
}

func readAll(t *testing.T, data []byte, sheet string) [][]hypaxel.Value {
	t.Helper()
	r, err := hypaxel.NewReader(bytes.NewReader(data), int64(len(data)))
	check(t, err)
	rows, err := r.Rows(sheet)
	check(t, err)
	var out [][]hypaxel.Value
	for row, err := range rows.All() {
		check(t, err)
		for len(out) < row.Index()-1 {
			out = append(out, nil)
		}
		out = append(out, row.AppendValues(nil))
	}
	return out
}

func excelizeBytes(t *testing.T, f *excelize.File) []byte {
	t.Helper()
	var buf bytes.Buffer
	check(t, f.Write(&buf))
	return buf.Bytes()
}

func TestReadsWorkbooksWrittenByExcelize(t *testing.T) {
	f := excelize.NewFile()
	defer f.Close()
	when := time.Date(2024, 5, 1, 8, 30, 0, 0, time.UTC)
	check(t, f.SetSheetRow("Sheet1", "A1", &[]any{"Name", "Amount", "When", "Flag"}))
	check(t, f.SetSheetRow("Sheet1", "A2", &[]any{"Widget", 12.5, when, true}))
	check(t, f.SetCellValue("Sheet1", "C5", "far away"))
	check(t, f.SetCellFormula("Sheet1", "B6", "SUM(B2:B2)"))
	check(t, f.SetCellValue("Sheet1", "A7", "Tab\tand\nnewline & <xml>"))
	check(t, f.SetCellRichText("Sheet1", "A8", []excelize.RichTextRun{{Text: "rich "}, {Text: "text", Font: &excelize.Font{Bold: true}}}))
	check(t, f.SetCellValue("Sheet1", "A9", int64(-1234567890123)))
	check(t, f.SetCellValue("Sheet1", "A10", 36*time.Hour))
	_, err := f.NewSheet("Hidden")
	check(t, err)
	check(t, f.SetCellValue("Hidden", "B2", "secret"))
	check(t, f.SetSheetVisible("Hidden", false))

	data := excelizeBytes(t, f)
	r, err := hypaxel.NewReader(bytes.NewReader(data), int64(len(data)))
	check(t, err)
	sheets := r.Sheets()
	if len(sheets) != 2 || sheets[1].Name != "Hidden" || sheets[1].Visibility != hypaxel.SheetHidden {
		t.Fatalf("sheets %+v", sheets)
	}

	rows := readAll(t, data, "Sheet1")
	text := func(row, column int) string {
		if row > len(rows) || column > len(rows[row-1]) {
			return ""
		}
		return rows[row-1][column-1].String()
	}
	for _, tc := range []struct {
		row, column int
		want        string
	}{
		{1, 1, "Name"}, {1, 4, "Flag"},
		{2, 1, "Widget"}, {2, 2, "12.5"}, {2, 3, "2024-05-01T08:30:00"}, {2, 4, "TRUE"},
		{5, 3, "far away"},
		{6, 2, ""},
		{7, 1, "Tab\tand\nnewline & <xml>"},
		{8, 1, "rich text"},
		{9, 1, "-1234567890123"},
	} {
		if got := text(tc.row, tc.column); got != tc.want {
			t.Errorf("row %d, column %d: got %q, want %q", tc.row, tc.column, got, tc.want)
		}
	}
	if got, ok := rows[1][2].Time(); !ok || !got.Equal(when) {
		t.Errorf("date %v", got)
	}
	if rows[9][0].Kind() == hypaxel.ValueDateTime {
		if d, _ := rows[9][0].Duration(); d != 36*time.Hour {
			t.Errorf("duration %v", d)
		}
	} else if n, _ := rows[9][0].Float(); n != 1.5 {
		t.Errorf("duration %v", rows[9][0])
	}
	if got := readAll(t, data, "hidden"); len(got) != 2 || got[1][1].Text() != "secret" {
		t.Errorf("hidden sheet %v", got)
	}
}

func TestReadsExcelizeStreamWriterOutput(t *testing.T) {
	var buf bytes.Buffer
	check(t, ExcelizeStream(&buf, Generate(500)))
	data := buf.Bytes()
	rows := readAll(t, data, "Sheet1")
	if len(rows) != 500 {
		t.Fatalf("%d rows", len(rows))
	}
	for i, row := range rows {
		o := order(i)
		got := []string{row[1].Text(), row[2].Text(), row[8].Text()}
		if !slices.Equal(got, []string{o.Name, o.Category, o.Comment}) {
			t.Fatalf("row %d: %q", i+1, got)
		}
		if when, ok := row[5].Time(); !ok || !when.Equal(o.Created) {
			t.Fatalf("row %d: date %v", i+1, row[5])
		}
		if b, _ := row[6].Bool(); b != o.Active {
			t.Fatalf("row %d: bool %v", i+1, row[6])
		}
		// excelize writes floats with 15 significant digits.
		if n, _ := row[9].Float(); math.Abs(n-o.Ratio) > 1e-14*o.Ratio {
			t.Fatalf("row %d: ratio %v", i+1, row[9])
		}
	}
}

func TestHypaxelReaderMatchesExcelizeRawValues(t *testing.T) {
	var buf bytes.Buffer
	check(t, HypaxelTyped(&buf, Generate(1000), hypaxel.WithSharedStrings(true)))
	data := buf.Bytes()
	f, err := excelize.OpenReader(bytes.NewReader(data))
	check(t, err)
	defer f.Close()
	want, err := f.GetRows("Data", excelize.Options{RawCellValue: true})
	check(t, err)
	got := readAll(t, data, "Data")
	if len(got) != len(want) {
		t.Fatalf("%d rows, excelize %d", len(got), len(want))
	}
	for i := range want {
		for j, w := range want[i] {
			v := got[i][j]
			ok := false
			switch v.Kind() {
			case hypaxel.ValueDateTime, hypaxel.ValueNumber:
				n, _ := v.Float()
				parsed, err := strconv.ParseFloat(w, 64)
				ok = err == nil && parsed == n
			case hypaxel.ValueBool:
				b, _ := v.Bool()
				ok = w == map[bool]string{false: "0", true: "1"}[b]
			default:
				ok = v.Text() == w
			}
			if !ok {
				t.Fatalf("row %d, column %d: %v, excelize %q", i+1, j+1, v, w)
			}
		}
	}
}
