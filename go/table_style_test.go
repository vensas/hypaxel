package hypaxel

import (
	"bytes"
	"reflect"
	"strings"
	"testing"
)

func TestCustomTableStyleIsWrittenVerbatim(t *testing.T) {
	parts := book(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		noErr(t, s.WriteRow("a"))
		noErr(t, s.WriteRow(1))
		noErr(t, s.AddTable(Table{FirstRow: 1, FirstColumn: 1, LastRow: 2, LastColumn: 1, Columns: []string{"a"},
			Style: TableStyleCustom(`Brand & "Co" <_x0041_> ü`)}))
	})
	contains(t, parts["xl/tables/table1.xml"], `<tableStyleInfo name="Brand &amp; &quot;Co&quot; &lt;_x0041_&gt; ü" showFirstColumn`)
}

func TestCustomTableStyleValidation(t *testing.T) {
	w := NewWriter(&bytes.Buffer{})
	s := mustSheet(t, w, "S")
	styled := func(style TableStyle) Table {
		return Table{FirstRow: 1, FirstColumn: 1, LastRow: 3, LastColumn: 1, Columns: []string{"a"}, Style: style}
	}
	for _, name := range []string{"", strings.Repeat("x", 256), strings.Repeat("😀", 128), "a\tb", "a\nb", "a\rb", "a\x00b", "a\x1fb", "a￾b", "a\xffb"} {
		err := s.AddTable(styled(TableStyleCustom(name)))
		wantErr(t, err, ErrInvalidArgument)
		if err != nil && !strings.Contains(err.Error(), "invalid table style name") {
			t.Errorf("%q: %v", name, err)
		}
	}
	noErr(t, s.AddTable(styled(TableStyleCustom(strings.Repeat("x", 255)))))
}

func TestCustomTableStyleEqualityAndString(t *testing.T) {
	if TableStyleCustom("Mine") != TableStyleCustom("Mine") || TableStyleCustom("Mine") == TableStyleCustom("mine") {
		t.Error("custom styles compare by name")
	}
	if TableStyleCustom("TableStyleMedium9") == TableStyleMedium(9) || TableStyleCustom("None") == TableStyleNone {
		t.Error("a custom style never equals a built-in style")
	}
	if name, ok := TableStyleCustom("Mine").Custom(); !ok || name != "Mine" || TableStyleCustom("Mine").String() != "Mine" {
		t.Errorf("Custom() = %q, %v", name, ok)
	}
	if _, ok := TableStyleMedium(9).Custom(); ok {
		t.Error("a built-in style is not custom")
	}
	if _, ok := TableStyleNone.Custom(); ok {
		t.Error("TableStyleNone is not custom")
	}
}

func TestCustomTableStyleRoundTrip(t *testing.T) {
	tables := []Table{
		{FirstRow: 1, FirstColumn: 1, LastRow: 2, LastColumn: 1, Name: "Custom", Columns: []string{"a"}, Style: TableStyleCustom(" Brand & Co_x0041_ ")},
		{FirstRow: 1, FirstColumn: 3, LastRow: 2, LastColumn: 3, Name: "Lookalike", Columns: []string{"c"}, Style: TableStyleCustom("TableStyleMedium9")},
		{FirstRow: 1, FirstColumn: 5, LastRow: 2, LastColumn: 5, Name: "BuiltIn", Columns: []string{"e"}, Style: TableStyleLight(4)},
	}
	data := writeBook(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		noErr(t, s.WriteRow("a", nil, "c", nil, "e"))
		noErr(t, s.WriteRow(1, nil, 2, nil, 3))
		for _, table := range tables {
			noErr(t, s.AddTable(table))
		}
	})
	got := mustTables(t, openBytes(t, data), "S")
	tables[1].Style = TableStyleMedium(9)
	if !reflect.DeepEqual(got, tables) {
		t.Errorf("got %+v\nwant %+v", got, tables)
	}
}
