package hypaxel

import (
	"errors"
	"strings"
	"testing"
)

func wantLimit(t *testing.T, err error, option string) {
	t.Helper()
	wantErr(t, err, ErrLimitExceeded)
	var re *ReadError
	if !errors.As(err, &re) || !strings.Contains(re.Message, option) {
		t.Errorf("error %v does not name %s", err, option)
	}
}

func TestReadDefaultLimits(t *testing.T) {
	r := openBytes(t, singleSheet(t, `<worksheet><sheetData/></worksheet>`, ""))
	want := readConfig{maxSharedStringsBytes: 1 << 30, maxTables: 4096, maxTableBytes: 64 << 20, maxTokenBytes: 16 << 20, maxRowTextBytes: 256 << 20, pipeline: true}
	if r.cfg != want {
		t.Errorf("got %+v, want %+v", r.cfg, want)
	}
}

func TestReadLimitOptionsClampTheirValues(t *testing.T) {
	r := openBytes(t, singleSheet(t, `<worksheet><sheetData/></worksheet>`, ""),
		WithMaxTables(0), WithMaxTableBytes(-1), WithMaxTokenBytes(1), WithMaxRowTextBytes(0))
	if r.cfg.maxTables != 1 || r.cfg.maxTableBytes != 1 || r.cfg.maxTokenBytes != 64<<10 || r.cfg.maxRowTextBytes != 1 {
		t.Errorf("got %+v", r.cfg)
	}
	r = openBytes(t, singleSheet(t, `<worksheet><sheetData/></worksheet>`, ""), WithMaxTokenBytes(1<<40), WithMaxRowTextBytes(1<<40))
	if r.cfg.maxTokenBytes != 1<<31-1 || r.cfg.maxRowTextBytes != 1<<31-1 {
		t.Errorf("got %+v", r.cfg)
	}
}

func TestReadEnforcesTheTableLimits(t *testing.T) {
	const sheet = `<worksheet><sheetData/></worksheet>`
	rel := func(id, target string) string {
		return `<Relationship Id="` + id + `" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/` + target + `"/>`
	}
	rels := `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` + rel("rId1", "a.xml") + rel("rId2", "b.xml") + rel("rId3", "c.xml") + `</Relationships>`
	data := singleSheet(t, sheet, "", "xl/worksheets/_rels/sheet1.xml.rels", rels,
		"xl/tables/a.xml", `<table displayName="A" ref="A1:A2"/>`,
		"xl/tables/b.xml", `<table displayName="B" ref="B1:B2"/>`,
		"xl/tables/c.xml", `<table displayName="C" ref="C1:C2"/>`)
	if tables := mustTables(t, openBytes(t, data, WithMaxTables(3)), "S"); len(tables) != 3 {
		t.Errorf("%d tables, want 3", len(tables))
	}
	_, err := openBytes(t, data, WithMaxTables(2)).Tables("S")
	wantLimit(t, err, "WithMaxTables")

	table := `<table displayName="Tbl" ref="A1:B2"><tableColumns><tableColumn name="ab"/><tableColumn name="c"/></tableColumns>` +
		`<tableStyleInfo name="Brand"/></table>`
	cost := int64(len("Tbl") + len("ab") + len("c") + 2*tableColumnCost + len("Brand"))
	one := sheetWithTable(t, sheet, table)
	if tables := mustTables(t, openBytes(t, one, WithMaxTableBytes(cost)), "S"); len(tables) != 1 || tables[0].Style != TableStyleCustom("Brand") {
		t.Errorf("got %+v", tables)
	}
	_, err = openBytes(t, one, WithMaxTableBytes(cost-1)).Tables("S")
	wantLimit(t, err, "WithMaxTableBytes")
}

func TestReadEnforcesTheTokenLimit(t *testing.T) {
	text := strings.Repeat("x", 100<<10)
	data := singleSheet(t, `<worksheet><sheetData><row r="1"><c t="inlineStr"><is><t>`+text+`</t></is></c></row></sheetData></worksheet>`, "")
	wantLimit(t, rowsError(t, data, WithMaxTokenBytes(64<<10)), "WithMaxTokenBytes")
	got := dump(t, data, "S")
	if len(got) != 1 || got[0].values[0] != text {
		t.Error("the default token limit accepts 100 KiB of text")
	}
}

func TestReadEnforcesTheRowTextLimit(t *testing.T) {
	data := singleSheet(t, `<worksheet><sheetData><row r="1"><c t="inlineStr"><is><t>abcdef</t></is></c><c t="str"><f>1</f><v>ghij</v></c></row></sheetData></worksheet>`, "")
	wantLimit(t, rowsError(t, data, WithMaxRowTextBytes(5)), "WithMaxRowTextBytes")
	wantLimit(t, rowsError(t, data, WithMaxRowTextBytes(9)), "WithMaxRowTextBytes")
	rows, err := openBytes(t, data, WithMaxRowTextBytes(10)).Rows("S")
	noErr(t, err)
	mustNext(t, rows)
}

func TestReadHiddenColumnsWithoutWidth(t *testing.T) {
	data := singleSheet(t, `<worksheet><cols><col min="1" max="1" width="12"/><col min="2" max="3" hidden="1"/><col min="4" max="4" style="2"/>`+
		`<col min="5" max="5" hidden="true" width="bad"/></cols><sheetData/></worksheet>`, "")
	rows, err := openBytes(t, data).Rows("S")
	noErr(t, err)
	layout := mustLayout(t, rows)
	want := []ColumnWidth{{1, 1, 12, true, false}, {2, 3, 0, false, true}, {5, 5, 0, false, true}}
	if len(layout.ColumnWidths) != len(want) {
		t.Fatalf("got %+v", layout.ColumnWidths)
	}
	for i := range want {
		if layout.ColumnWidths[i] != want[i] {
			t.Errorf("%d: got %+v, want %+v", i, layout.ColumnWidths[i], want[i])
		}
	}
	for column := 2; column <= 3; column++ {
		if _, ok := layout.ColumnWidth(column); ok || !layout.ColumnHidden(column) {
			t.Errorf("column %d: width present or not hidden", column)
		}
	}
	if w, ok := layout.ColumnWidth(1); !ok || w != 12 || layout.ColumnHidden(1) {
		t.Errorf("column 1: %v %v", w, ok)
	}
	if _, ok := layout.ColumnWidth(4); ok || layout.ColumnHidden(4) {
		t.Error("a style-only column is skipped")
	}
}
