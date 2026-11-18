package tests

import (
	"io"
	"iter"
	"strconv"
	"time"

	"github.com/xuri/excelize/v2"

	hypaxel "github.com/vensas/hypaxel/go"
)

// Order is one row of the benchmark workload shared with the other Hypaxel implementations:
// int, two strings, two floats, a decimal-like float, a time, a bool, an int64 and a unique string.
type Order struct {
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

var categories = [...]string{"Hardware", "Software", "Services", "Consulting", "Support", "Training"}

var start = time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC)

func order(i int) Order {
	return Order{
		ID:       i,
		Name:     "Customer " + strconv.Itoa(i%5000),
		Category: categories[i%len(categories)],
		Price:    float64(i) * 1.25,
		Amount:   float64(i) * 0.01,
		Created:  start.Add(time.Duration(i) * time.Minute),
		Active:   i%2 == 0,
		Quantity: int64(i) * 3,
		Comment:  "Unique comment #" + strconv.Itoa(i),
		Ratio:    float64(i) / 7,
	}
}

// Data builds the workload up front so that benchmarks measure writing only.
func Data(count int) []Order {
	rows := make([]Order, count)
	for i := range rows {
		rows[i] = order(i)
	}
	return rows
}

// Generate yields the workload row by row, so that a process measuring peak memory holds no
// dataset.
func Generate(count int) iter.Seq[Order] {
	return func(yield func(Order) bool) {
		for i := range count {
			if !yield(order(i)) {
				return
			}
		}
	}
}

// HypaxelTyped writes the workload cell by cell with the typed methods.
func HypaxelTyped(dst io.Writer, rows iter.Seq[Order], opts ...hypaxel.Option) error {
	w := hypaxel.NewWriter(dst, opts...)
	s, err := w.AddSheet("Data")
	if err != nil {
		return err
	}
	for r := range rows {
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
			return err
		}
	}
	return w.Close()
}

// HypaxelRow writes the workload with WriteRow, one any per cell.
func HypaxelRow(dst io.Writer, rows iter.Seq[Order]) error {
	w := hypaxel.NewWriter(dst)
	s, err := w.AddSheet("Data")
	if err != nil {
		return err
	}
	for r := range rows {
		if err := s.WriteRow(r.ID, r.Name, r.Category, r.Price, r.Amount, r.Created, r.Active, r.Quantity, r.Comment, r.Ratio); err != nil {
			return err
		}
	}
	return w.Close()
}

// HypaxelStructs writes the workload through struct mapping.
func HypaxelStructs(dst io.Writer, rows iter.Seq[Order]) error {
	w := hypaxel.NewWriter(dst)
	if err := hypaxel.WriteSheetSeq(w, "Data", rows); err != nil {
		return err
	}
	return w.Close()
}

// ExcelizeStream writes the workload with excelize's StreamWriter.
func ExcelizeStream(dst io.Writer, rows iter.Seq[Order]) error {
	f := excelize.NewFile()
	defer f.Close()
	date, err := f.NewStyle(&excelize.Style{NumFmt: 22})
	if err != nil {
		return err
	}
	sw, err := f.NewStreamWriter("Sheet1")
	if err != nil {
		return err
	}
	row := make([]any, 10)
	i := 0
	for r := range rows {
		i++
		row[0], row[1], row[2], row[3], row[4] = r.ID, r.Name, r.Category, r.Price, r.Amount
		row[5] = excelize.Cell{StyleID: date, Value: r.Created}
		row[6], row[7], row[8], row[9] = r.Active, r.Quantity, r.Comment, r.Ratio
		cell, err := excelize.CoordinatesToCellName(1, i)
		if err != nil {
			return err
		}
		if err := sw.SetRow(cell, row); err != nil {
			return err
		}
	}
	if err := sw.Flush(); err != nil {
		return err
	}
	return f.Write(dst)
}

// ExcelizeInMemory writes the workload with excelize's regular, in-memory API.
func ExcelizeInMemory(dst io.Writer, rows iter.Seq[Order]) error {
	f := excelize.NewFile()
	defer f.Close()
	date, err := f.NewStyle(&excelize.Style{NumFmt: 22})
	if err != nil {
		return err
	}
	row := make([]any, 10)
	i := 0
	for r := range rows {
		i++
		row[0], row[1], row[2], row[3], row[4] = r.ID, r.Name, r.Category, r.Price, r.Amount
		row[5], row[6], row[7], row[8], row[9] = r.Created, r.Active, r.Quantity, r.Comment, r.Ratio
		cell, err := excelize.CoordinatesToCellName(1, i)
		if err != nil {
			return err
		}
		if err := f.SetSheetRow("Sheet1", cell, &row); err != nil {
			return err
		}
	}
	if err := f.SetColStyle("Sheet1", "F", date); err != nil {
		return err
	}
	return f.Write(dst)
}
