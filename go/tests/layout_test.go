package tests

import (
	"testing"

	hypaxel "github.com/vensas/hypaxel/go"
)

func TestOutlinesTablesAndAutoFitReadBack(t *testing.T) {
	f := build(t, func(w *hypaxel.Writer) {
		s := addSheet(t, w, "Tree", hypaxel.SheetOptions{
			RowOutlineLevels: 2,
			AutoFit:          &hypaxel.AutoFit{},
			Columns:          []hypaxel.Column{{}, {Width: 20}},
		})
		check(t, s.WriteRow("Region", "Revenue"))
		check(t, s.WriteRow("North", 300))
		check(t, s.BeginRow(hypaxel.RowOptions{OutlineLevel: 1}))
		check(t, s.WriteRow("A rather long store name", 120))
		check(t, s.BeginRow(hypaxel.RowOptions{OutlineLevel: 2, Hidden: true}))
		check(t, s.WriteRow("Till", 60))
		check(t, s.AddTable(hypaxel.Table{
			FirstRow: 1, FirstColumn: 1, LastRow: s.Row(), LastColumn: 2,
			Name: "Regions", Columns: []string{"Region", "Revenue"}, Style: hypaxel.TableStyleMedium(9),
		}))
		check(t, s.AddTable(hypaxel.Table{FirstRow: 10, FirstColumn: 1, LastRow: 11, LastColumn: 1, NoHeaderRow: true}))
	})

	for row, want := range map[int]uint8{2: 0, 3: 1, 4: 2} {
		level, err := f.GetRowOutlineLevel("Tree", row)
		check(t, err)
		if level != want {
			t.Errorf("row %d: outline level %d, want %d", row, level, want)
		}
	}
	visible, err := f.GetRowVisible("Tree", 4)
	check(t, err)
	if visible {
		t.Error("row 4 should be hidden")
	}
	if got := cell(t, f, "Tree", "A3"); got != "A rather long store name" {
		t.Errorf("A3 = %q", got)
	}

	tables, err := f.GetTables("Tree")
	check(t, err)
	if len(tables) != 2 || tables[0].Name != "Regions" || tables[0].Range != "A1:B4" || tables[0].StyleName != "TableStyleMedium9" ||
		tables[1].Name != "Table2" || tables[1].Range != "A10:A11" {
		t.Errorf("tables: %+v", tables)
	}

	fitted, err := f.GetColWidth("Tree", "A")
	check(t, err)
	if fitted < 20 || fitted > 30 {
		t.Errorf("column A width %v", fitted)
	}
	explicit, err := f.GetColWidth("Tree", "B")
	check(t, err)
	if explicit != 20 {
		t.Errorf("column B width %v, want the explicit 20", explicit)
	}
}

func TestCustomTableStyleReadBack(t *testing.T) {
	f := build(t, func(w *hypaxel.Writer) {
		s := addSheet(t, w, "S")
		check(t, s.WriteRow("Name"))
		check(t, s.WriteRow("x"))
		check(t, s.AddTable(hypaxel.Table{FirstRow: 1, FirstColumn: 1, LastRow: 2, LastColumn: 1, Name: "Branded",
			Columns: []string{"Name"}, Style: hypaxel.TableStyleCustom("Brand & Co")}))
	})
	tables, err := f.GetTables("S")
	check(t, err)
	if len(tables) != 1 || tables[0].StyleName != "Brand & Co" {
		t.Errorf("tables: %+v", tables)
	}
}
