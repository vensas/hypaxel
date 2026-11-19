package tests

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tealeg/xlsx/v3"
	"github.com/thedatashed/xlsxreader"
	"github.com/xuri/excelize/v2"

	hypaxel "github.com/vensas/hypaxel/go"
)

// Checksum summarizes what a reader returned for the workload, so that readers can be checked
// against each other. Dates are compared as Unix seconds because some readers return them as
// formatted text.
type Checksum struct {
	Rows        int
	Cells       int
	Numbers     float64
	DateSeconds int64
	StringBytes int
	Trues       int
}

func (c Checksum) String() string {
	return fmt.Sprintf("%d rows, %d cells, numbers %.6g, dates %d, string bytes %d, trues %d",
		c.Rows, c.Cells, c.Numbers, c.DateSeconds, c.StringBytes, c.Trues)
}

// Comparable is the part of the checksum a reader that formats values can reproduce: cell
// counts and the text of string cells.
func (c Checksum) Comparable() Checksum {
	return Checksum{Rows: c.Rows, Cells: c.Cells, StringBytes: c.StringBytes}
}

// Matches reports whether got, from this reader, agrees with want, from the Hypaxel reader, in
// everything the reader can reproduce.
func (c *ReadCase) Matches(got, want Checksum) bool {
	switch {
	case c.Formatted:
		return got.Comparable() == want.Comparable()
	case c.TruncatedDates:
		behind := want.DateSeconds - got.DateSeconds
		got.DateSeconds = want.DateSeconds
		return got == want && behind >= 0 && behind <= int64(want.Rows)
	}
	return got == want
}

// A ReadCase reads the workload sheet "Data" from a file.
type ReadCase struct {
	Name string
	// Formatted readers return display text, so only Comparable() matches.
	Formatted bool
	// TruncatedDates readers drop the fraction of a second of a date-time instead of rounding
	// it, so a time such as 00:01:00, stored as a serial slightly below the minute, comes back
	// as 00:00:59; DateSeconds may be up to one second per row lower.
	TruncatedDates bool
	// MaxRows skips readers that hold the whole sheet in memory on large inputs.
	MaxRows int
	Run     func(path string) (Checksum, error)
}

var ReadCases = []ReadCase{
	{Name: "Hypaxel, Rows.Next", Run: HypaxelRead},
	{Name: "Hypaxel, Rows.All", Run: HypaxelReadAll},
	{Name: "excelize, Rows (raw values)", Run: func(path string) (Checksum, error) { return ExcelizeRead(path, true) }},
	{Name: "excelize, Rows (formatted)", Formatted: true, Run: func(path string) (Checksum, error) { return ExcelizeRead(path, false) }},
	{Name: "xlsxreader, ReadRows", TruncatedDates: true, Run: XlsxreaderRead},
	{Name: "tealeg/xlsx, OpenFile", MaxRows: 200_000, Run: TealegRead},
}

// WriteReadInput writes count rows of the workload to a file in dir for the read benchmarks.
// Every cell gets a reference such as B7, as Excel writes them, because xlsxreader and
// tealeg/xlsx cannot read cells without one.
func WriteReadInput(dir string, count int, opts ...hypaxel.Option) (string, error) {
	f, err := os.CreateTemp(dir, "workload-*.xlsx")
	if err != nil {
		return "", err
	}
	defer f.Close()
	opts = append([]hypaxel.Option{hypaxel.WithCellReferences(true)}, opts...)
	if err := HypaxelTyped(f, Generate(count), opts...); err != nil {
		return "", err
	}
	return f.Name(), f.Close()
}

func (c *Checksum) addValue(v hypaxel.Value) {
	c.Cells++
	switch v.Kind() {
	case hypaxel.ValueNumber:
		n, _ := v.Float()
		c.Numbers += n
	case hypaxel.ValueDateTime:
		t, _ := v.Time()
		c.DateSeconds += t.Unix()
	case hypaxel.ValueString:
		c.StringBytes += len(v.Text())
	case hypaxel.ValueBool:
		if b, _ := v.Bool(); b {
			c.Trues++
		}
	}
}

// HypaxelRead reads the sheet with Rows.Next.
func HypaxelRead(path string) (Checksum, error) {
	r, err := hypaxel.OpenReader(path)
	if err != nil {
		return Checksum{}, err
	}
	defer r.Close()
	rows, err := r.Rows("Data")
	if err != nil {
		return Checksum{}, err
	}
	var c Checksum
	for rows.Next() {
		row := rows.Row()
		c.Rows++
		for i := range row.Len() {
			_, v := row.Cell(i)
			c.addValue(v)
		}
	}
	return c, rows.Err()
}

// HypaxelReadAll reads the sheet with the range-over-func iterators.
func HypaxelReadAll(path string) (Checksum, error) {
	r, err := hypaxel.OpenReader(path)
	if err != nil {
		return Checksum{}, err
	}
	defer r.Close()
	rows, err := r.Rows("Data")
	if err != nil {
		return Checksum{}, err
	}
	var c Checksum
	for row, err := range rows.All() {
		if err != nil {
			return c, err
		}
		c.Rows++
		for _, v := range row.Cells() {
			c.addValue(v)
		}
	}
	return c, nil
}

// addText adds a cell given as text, using the workload's column types.
func (c *Checksum) addText(column int, s string, date func(string) (int64, error)) error {
	if s == "" {
		return nil
	}
	c.Cells++
	switch column {
	case 0, 3, 4, 7, 9:
		n, err := strconv.ParseFloat(s, 64)
		if err != nil {
			return fmt.Errorf("column %d: %w", column+1, err)
		}
		c.Numbers += n
	case 5:
		seconds, err := date(s)
		if err != nil {
			return fmt.Errorf("column %d: %w", column+1, err)
		}
		c.DateSeconds += seconds
	case 6:
		if s == "1" || s == "TRUE" || s == "true" {
			c.Trues++
		}
	default:
		c.StringBytes += len(s)
	}
	return nil
}

// serialSeconds converts a 1900-system date serial (after 1900-03-01) to Unix seconds,
// rounded to the second.
func serialSeconds(s string) (int64, error) {
	serial, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0, err
	}
	return int64(math.Round((serial - 25_569) * 86_400)), nil
}

// ExcelizeRead reads the sheet with excelize's streaming Rows iterator, as raw cell values or
// as the display text of their number formats.
func ExcelizeRead(path string, raw bool) (Checksum, error) {
	f, err := excelize.OpenFile(path)
	if err != nil {
		return Checksum{}, err
	}
	defer f.Close()
	rows, err := f.Rows("Data")
	if err != nil {
		return Checksum{}, err
	}
	defer rows.Close()
	opts := excelize.Options{RawCellValue: raw}
	date := serialSeconds
	if !raw {
		date = func(string) (int64, error) { return 0, nil }
	}
	var c Checksum
	for rows.Next() {
		columns, err := rows.Columns(opts)
		if err != nil {
			return c, err
		}
		c.Rows++
		for i, s := range columns {
			if raw {
				if err := c.addText(i, s, date); err != nil {
					return c, err
				}
			} else if s != "" {
				c.Cells++
				if i == 1 || i == 2 || i == 8 {
					c.StringBytes += len(s)
				}
			}
		}
	}
	return c, rows.Error()
}

// XlsxreaderRead reads the sheet with github.com/thedatashed/xlsxreader, which streams rows
// through a channel and returns dates as RFC 3339 text.
func XlsxreaderRead(path string) (Checksum, error) {
	f, err := xlsxreader.OpenFile(path)
	if err != nil {
		return Checksum{}, err
	}
	defer f.Close()
	date := func(s string) (int64, error) {
		t, err := time.Parse(time.RFC3339, s)
		if err != nil {
			// Midnight is returned as a date without a time.
			t, err = time.Parse(time.DateOnly, s)
		}
		return t.Unix(), err
	}
	var c Checksum
	for row := range f.ReadRows("Data") {
		if row.Error != nil {
			return c, row.Error
		}
		c.Rows++
		for _, cell := range row.Cells {
			if err := c.addText(cell.ColumnIndex(), cell.Value, date); err != nil {
				return c, err
			}
		}
	}
	return c, nil
}

// TealegRead reads the sheet with github.com/tealeg/xlsx/v3, which loads it into memory.
func TealegRead(path string) (Checksum, error) {
	f, err := xlsx.OpenFile(path)
	if err != nil {
		return Checksum{}, err
	}
	sheet, ok := f.Sheet["Data"]
	if !ok {
		return Checksum{}, os.ErrNotExist
	}
	defer sheet.Close()
	var c Checksum
	err = sheet.ForEachRow(func(r *xlsx.Row) error {
		c.Rows++
		return r.ForEachCell(func(cell *xlsx.Cell) error {
			column, _ := cell.GetCoordinates()
			return c.addText(column, cell.Value, serialSeconds)
		})
	})
	return c, err
}
