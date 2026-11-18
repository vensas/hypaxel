package hypaxel

import (
	"io"
	"strconv"
	"testing"
	"time"
)

type benchRow struct {
	ID       int
	Name     string
	Category string
	Price    float64
	Amount   float64
	Created  time.Time
	Active   bool
	Quantity int64
	Comment  string
	Ratio    float64
}

var benchValue = benchRow{
	ID: 123456, Name: "Customer 1234", Category: "Consulting", Price: 154320, Amount: 1234.56,
	Created: time.Date(2024, 3, 26, 17, 36, 0, 0, time.UTC), Active: true, Quantity: 370368,
	Comment: "Unique comment #123456", Ratio: 17636.571428571428,
}

// BenchmarkTypedRow writes one 10-column row per iteration with the typed cell methods.
func BenchmarkTypedRow(b *testing.B) {
	benchTypedRow(b, SheetOptions{}, MaxRows-1)
}

// BenchmarkTypedRowAutoFit is BenchmarkTypedRow with every row measured for auto-fit: a new
// sheet starts after each 1,000-row sample.
func BenchmarkTypedRowAutoFit(b *testing.B) {
	benchTypedRow(b, SheetOptions{AutoFit: &AutoFit{}}, 1000)
}

func benchTypedRow(b *testing.B, opts SheetOptions, rowsPerSheet int) {
	w := NewWriter(io.Discard)
	s, err := w.AddSheetOptions("Data", opts)
	if err != nil {
		b.Fatal(err)
	}
	r := &benchValue
	b.ReportAllocs()
	for i := 0; b.Loop(); i++ {
		if i%rowsPerSheet == rowsPerSheet-1 {
			if s, err = w.AddSheetOptions(ColumnName(len(w.sheets)%MaxColumns+1)+strconv.Itoa(len(w.sheets)), opts); err != nil {
				b.Fatal(err)
			}
		}
		s.WriteInt(int64(r.ID))
		s.WriteString(r.Name)
		s.WriteString(r.Category)
		s.WriteFloat(r.Price)
		s.WriteFloat(r.Amount)
		s.WriteTime(r.Created)
		s.WriteBool(r.Active)
		s.WriteInt(r.Quantity)
		s.WriteString(r.Comment)
		s.WriteFloat(r.Ratio)
		if err := s.EndRow(); err != nil {
			b.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		b.Fatal(err)
	}
}

// BenchmarkStructRow writes the same row through struct mapping.
func BenchmarkStructRow(b *testing.B) {
	w := NewWriter(io.Discard)
	s, err := w.AddSheet("Data")
	if err != nil {
		b.Fatal(err)
	}
	b.ReportAllocs()
	for i := 0; b.Loop(); i++ {
		if i%MaxRows == MaxRows-1 {
			if s, err = w.AddSheet(ColumnName(len(w.sheets) + 1)); err != nil {
				b.Fatal(err)
			}
		}
		if err := s.WriteStruct(&benchValue); err != nil {
			b.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		b.Fatal(err)
	}
}

func BenchmarkEscape(b *testing.B) {
	for name, text := range map[string]string{
		"ascii":   "Unique comment #123456 with nothing to escape",
		"unicode": "Größe der Bestellung für München, 東京 und Zürich",
		"markup":  `<a href="x">Tom & Jerry</a> said "hello" again`,
	} {
		b.Run(name, func(b *testing.B) {
			buf := make([]byte, 0, 256)
			b.SetBytes(int64(len(text)))
			b.ReportAllocs()
			for b.Loop() {
				buf = appendEscaped(buf[:0], text)
			}
		})
	}
}
