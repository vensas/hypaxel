package hypaxel

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"iter"
	"slices"
	"strings"
	"testing"
	"time"
)

type audit struct {
	CreatedBy string `xlsx:"Created by"`
	internal  int
}

type timestamps struct {
	Updated time.Time `xlsx:",date"`
}

type order struct {
	ID       int     `xlsx:"Order #,width=10"`
	Customer string  `xlsx:",width=24.5"`
	Amount   float64 `xlsx:"Amount,width=14,format=#,##0.00"`
	Share    float32 `xlsx:"format=0.0%"`
	Units    uint8
	Paid     bool `xlsx:"Paid,hidden"`
	Created  time.Time
	Due      *time.Time    `xlsx:"Due,date"`
	Took     time.Duration `xlsx:"Took"`
	State    status
	Comment  sql.NullString
	Tag      *string
	Any      any
	Secret   string `xlsx:"-"`
	audit
	*timestamps
	hidden string
}

func sampleOrders() []order {
	due := time.Date(2024, 2, 1, 18, 0, 0, 0, time.UTC)
	tag := "vip"
	return []order{
		{
			ID: 1, Customer: "Ada", Amount: 1234.5, Share: 0.1, Units: 3, Paid: true,
			Created: time.Date(2024, 1, 1, 12, 0, 0, 0, time.UTC), Due: &due, Took: 36 * time.Hour, State: 1,
			Comment: sql.NullString{String: "note", Valid: true}, Tag: &tag, Any: 7, Secret: "s",
			audit: audit{CreatedBy: "root"}, timestamps: &timestamps{Updated: time.Date(2024, 1, 3, 9, 0, 0, 0, time.UTC)},
		},
		{ID: 2, Customer: "Linus"},
	}
}

const orderHeader = `<row r="1"><c s="3" t="inlineStr"><is><t>Order #</t></is></c><c s="3" t="inlineStr"><is><t>Customer</t></is></c>` +
	`<c s="3" t="inlineStr"><is><t>Amount</t></is></c><c s="3" t="inlineStr"><is><t>Share</t></is></c><c s="3" t="inlineStr"><is><t>Units</t></is></c>` +
	`<c s="3" t="inlineStr"><is><t>Paid</t></is></c><c s="3" t="inlineStr"><is><t>Created</t></is></c><c s="3" t="inlineStr"><is><t>Due</t></is></c>` +
	`<c s="3" t="inlineStr"><is><t>Took</t></is></c><c s="3" t="inlineStr"><is><t>State</t></is></c><c s="3" t="inlineStr"><is><t>Comment</t></is></c>` +
	`<c s="3" t="inlineStr"><is><t>Tag</t></is></c><c s="3" t="inlineStr"><is><t>Any</t></is></c><c s="3" t="inlineStr"><is><t>Created by</t></is></c>` +
	`<c s="3" t="inlineStr"><is><t>Updated</t></is></c></row>`

const orderRows = `<row r="2"><c><v>1</v></c><c t="inlineStr"><is><t>Ada</t></is></c><c s="1"><v>1234.5</v></c><c s="2"><v>0.1</v></c><c><v>3</v></c>` +
	`<c t="b"><v>1</v></c><c s="4"><v>45292.5</v></c><c s="5"><v>45323</v></c><c s="6"><v>1.5</v></c><c t="inlineStr"><is><t>done</t></is></c>` +
	`<c t="inlineStr"><is><t>note</t></is></c><c t="inlineStr"><is><t>vip</t></is></c><c><v>7</v></c><c t="inlineStr"><is><t>root</t></is></c>` +
	`<c s="5"><v>45294</v></c></row>` +
	`<row r="3"><c><v>2</v></c><c t="inlineStr"><is><t>Linus</t></is></c><c s="1"><v>0</v></c><c s="2"><v>0</v></c><c><v>0</v></c>` +
	`<c t="b"><v>0</v></c><c r="I3" s="6"><v>0</v></c><c t="inlineStr"><is><t>new</t></is></c><c r="N3" t="inlineStr"><is><t></t></is></c></row>`

func TestWriteSheetFromStructs(t *testing.T) {
	orders := sampleOrders()
	pointers := []*order{&orders[0], &orders[1]}
	channel := make(chan order, len(orders))
	for _, o := range orders {
		channel <- o
	}
	close(channel)
	failing := func(yield func(order, error) bool) {
		for _, o := range orders {
			if !yield(o, nil) {
				return
			}
		}
	}
	for name, write := range map[string]func(w *Writer) error{
		"slice":    func(w *Writer) error { return WriteSheet(w, "Orders", orders) },
		"pointers": func(w *Writer) error { return WriteSheet(w, "Orders", pointers) },
		"seq":      func(w *Writer) error { return WriteSheetSeq(w, "Orders", slices.Values(orders)) },
		"seq2":     func(w *Writer) error { return WriteSheetSeq2(w, "Orders", iter.Seq2[order, error](failing)) },
		"chan":     func(w *Writer) error { return WriteSheetChan(context.Background(), w, "Orders", channel) },
	} {
		parts := book(t, func(w *Writer) { noErr(t, write(w)) })
		if got := sheetData(t, parts, 1); got != orderHeader+orderRows {
			t.Errorf("%s:\n got %s\nwant %s", name, got, orderHeader+orderRows)
		}
		contains(t, parts["xl/worksheets/sheet1.xml"],
			`<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>`,
			`<cols><col min="1" max="1" width="10" customWidth="1"/><col min="2" max="2" width="24.5" customWidth="1"/><col min="3" max="3" width="14" customWidth="1"/><col min="6" max="6" width="9.140625" hidden="1"/></cols>`,
			`<autoFilter ref="A1:O3"/>`,
		)
		contains(t, parts["xl/styles.xml"], `formatCode="0.0%"`)
	}
}

func TestWriteSheetWithoutRows(t *testing.T) {
	parts := book(t, func(w *Writer) { noErr(t, WriteSheet(w, "Empty", []audit(nil))) })
	contains(t, parts["xl/worksheets/sheet1.xml"], `<autoFilter ref="A1:A1"/>`, `<t>Created by</t>`)
}

func TestWriteSheetFromSlices(t *testing.T) {
	var nilRow []any
	parts := book(t, func(w *Writer) {
		noErr(t, WriteSheet(w, "any", [][]any{{1, "a", nil, true}, nilRow, {2.5}}))
		noErr(t, WriteSheet(w, "strings", [][]string{{"x", "y"}}))
		noErr(t, WriteSheet(w, "arrays", [][2]int{{1, 2}, {3, 4}}))
		noErr(t, WriteSheetSeq(w, "mixed", slices.Values([]any{[]any{"row"}, audit{CreatedBy: "me"}, &audit{CreatedBy: "you"}, nil})))
	})
	if got, want := sheetData(t, parts, 1), `<row r="1"><c><v>1</v></c><c t="inlineStr"><is><t>a</t></is></c><c r="D1" t="b"><v>1</v></c></row><row r="2"></row><row r="3"><c><v>2.5</v></c></row>`; got != want {
		t.Errorf("any rows:\n got %s\nwant %s", got, want)
	}
	contains(t, sheetData(t, parts, 2), `<row r="1"><c t="inlineStr"><is><t>x</t></is></c><c t="inlineStr"><is><t>y</t></is></c></row>`)
	contains(t, sheetData(t, parts, 3), `<row r="2"><c><v>3</v></c><c><v>4</v></c></row>`)
	contains(t, sheetData(t, parts, 4), `<t>row</t>`, `<row r="2"><c t="inlineStr"><is><t>me</t></is></c></row>`, `<t>you</t>`, `<row r="4"></row>`)
	if strings.Contains(parts["xl/worksheets/sheet1.xml"], "autoFilter") {
		t.Error("plain rows must not get an auto filter")
	}
}

func TestWriteSheetErrors(t *testing.T) {
	w := NewWriter(io.Discard)
	wantErr(t, WriteSheet(w, "ints", []int{1}), ErrUnsupported)
	wantErr(t, WriteSheet(w, "times", []time.Time{{}}), ErrUnsupported)
	wantErr(t, WriteSheet(w, "empty", []struct{ a int }{{1}}), ErrUnsupported)
	wantErr(t, WriteSheet(w, "bad/name", []audit{{}}), ErrInvalidSheetName)
	wantErr(t, WriteSheet(w, "bad/name", [][]any{{1}}), ErrInvalidSheetName)
	wantErr(t, WriteSheetSeq(w, "bad/name", slices.Values([]audit{{}})), ErrInvalidSheetName)
	wantErr(t, WriteSheetSeq2(w, "bad/name", func(func(audit, error) bool) {}), ErrInvalidSheetName)
	wantErr(t, WriteSheetChan(context.Background(), w, "bad/name", make(chan audit)), ErrInvalidSheetName)
	wantErr(t, WriteSheetSeq(w, "scalars", slices.Values([]any{1})), ErrUnsupported)
	noErr(t, w.Err())

	type badWidth struct {
		A int `xlsx:"A,width=wide"`
	}
	type badOption struct {
		A int `xlsx:"A,bold"`
	}
	type badEmbedded struct{ badOption }
	wantErr(t, WriteSheet(w, "w", []badWidth{{}}), ErrInvalidArgument)
	wantErr(t, WriteSheet(w, "o", []badOption{{}}), ErrInvalidArgument)
	err := WriteSheet(w, "e", []badEmbedded{{}})
	wantErr(t, err, ErrInvalidArgument)
	if !strings.Contains(err.Error(), `badOption.A: unknown option "bold"`) {
		t.Errorf("error does not name the field: %v", err)
	}

	boom := errors.New("cursor failed")
	err = WriteSheetSeq2(w, "cursor", func(yield func(audit, error) bool) {
		if yield(audit{CreatedBy: "a"}, nil) {
			yield(audit{}, boom)
		}
	})
	wantErr(t, err, boom)

	ctx, cancel := context.WithCancelCause(context.Background())
	cancel(boom)
	wantErr(t, WriteSheetChan(ctx, w, "cancelled", make(chan audit)), boom)

	foreign := NewWriter(io.Discard)
	sheet := mustSheet(t, foreign, "S")
	sheet.WriteInt(1, 99)
	wantErr(t, WriteSheet(foreign, "T", []audit{{}}), ErrInvalidStyle)

	for name, write := range map[string]func(w *Writer) error{
		"slice": func(w *Writer) error { return WriteSheet(w, "S", make([]audit, 50_000)) },
		"seq":   func(w *Writer) error { return WriteSheetSeq(w, "S", slices.Values(make([]audit, 50_000))) },
		"seq2": func(w *Writer) error {
			return WriteSheetSeq2(w, "S", func(yield func(audit, error) bool) {
				for range 50_000 {
					if !yield(audit{}, nil) {
						return
					}
				}
			})
		},
		"chan": func(w *Writer) error {
			rows := make(chan audit)
			go func() {
				defer close(rows)
				for range 50_000 {
					rows <- audit{}
				}
			}()
			err := WriteSheetChan(context.Background(), w, "S", rows)
			for range rows {
			}
			return err
		},
	} {
		if err := write(NewWriter(&failingWriter{limit: 2000}, WithFlushThreshold(1024))); !errors.Is(err, errDisk) {
			t.Errorf("%s: a failing destination returned %v", name, err)
		}
	}
}

func TestWriteStruct(t *testing.T) {
	orders := sampleOrders()
	parts := book(t, func(w *Writer) {
		columns, err := w.StructColumns(order{})
		noErr(t, err)
		if len(columns) != 15 || columns[0].Width != 10 || !columns[5].Hidden {
			t.Errorf("columns = %+v", columns)
		}
		// Registered in this order so that the style ids match the expected rows.
		w.AddStyle(Style{Font: Font{Bold: true}})
		s, err := w.AddSheetOptions("S", SheetOptions{Columns: columns})
		noErr(t, err)
		noErr(t, s.WriteStructHeader(&orders[0], 3))
		noErr(t, s.WriteStruct(&orders[0]))
		noErr(t, s.WriteStruct(orders[1]))
		var none *order
		noErr(t, s.WriteStruct(none))
		noErr(t, s.WriteStructHeader(audit{}))

		wantErr(t, s.WriteStruct(nil), ErrUnsupported)
		wantErr(t, s.WriteStruct(42), ErrUnsupported)
		wantErr(t, s.WriteStruct(time.Now()), ErrUnsupported)
		wantErr(t, s.WriteStructHeader(nil), ErrUnsupported)
		wantErr(t, s.WriteStructHeader("x"), ErrUnsupported)
		_, err = w.StructColumns(nil)
		wantErr(t, err, ErrUnsupported)
		_, err = w.StructColumns(3)
		wantErr(t, err, ErrUnsupported)
	})
	want := orderHeader + orderRows + `<row r="4"></row><row r="5"><c t="inlineStr"><is><t>Created by</t></is></c></row>`
	if got := sheetData(t, parts, 1); got != want {
		t.Errorf("\n got %s\nwant %s", got, want)
	}
}

func TestParseTag(t *testing.T) {
	for tag, want := range map[string]tagOptions{
		"":                               {},
		"Header":                         {header: "Header"},
		",width=20":                      {width: 20},
		"H,hidden,date":                  {header: "H", hidden: true, dateOnly: true},
		"format=#,##0.00":                {format: "#,##0.00"},
		"H,width=12.5,format=#,##0.00":   {header: "H", width: 12.5, format: "#,##0.00"},
		"H,format=0.00,width=3":          {header: "H", format: "0.00,width=3"},
		`Total,format=#,##0 "pcs",date`:  {header: "Total", format: `#,##0 "pcs",date`},
		"Größe (m²),date,format=dd.mm.":  {header: "Größe (m²)", dateOnly: true, format: "dd.mm."},
		"Header with spaces,width=255.0": {header: "Header with spaces", width: 255},
	} {
		got, err := parseTag(tag)
		if err != nil || got != want {
			t.Errorf("parseTag(%q) = %+v, %v; want %+v", tag, got, err, want)
		}
	}
	for _, tag := range []string{"H,width", "H,width=", "H,width=0", "H,width=256", "H,width=-1", "H,width=NaN", "H,unknown", "H,Hidden", "H,,date"} {
		if _, err := parseTag(tag); err == nil {
			t.Errorf("parseTag(%q) succeeded", tag)
		}
	}
}

func TestStructRowsDoNotAllocate(t *testing.T) {
	type row struct {
		ID      int
		Name    string
		Price   float64 `xlsx:"Price,format=0.00"`
		Created time.Time
		Took    time.Duration
		Active  bool
		Count   uint32
	}
	w := NewWriter(io.Discard)
	s := mustSheet(t, w, "S")
	value := row{ID: 1, Name: "Customer 17", Price: 9.99, Created: time.Date(2024, 5, 17, 13, 45, 30, 0, time.UTC), Took: time.Hour, Active: true, Count: 3}
	write := func() {
		if err := s.WriteStruct(&value); err != nil {
			t.Fatal(err)
		}
	}
	for range 2000 {
		write()
	}
	if allocs := testing.AllocsPerRun(5000, write); allocs != 0 {
		t.Errorf("%v allocations per struct row, want 0", allocs)
	}
	noErr(t, w.Close())
}
