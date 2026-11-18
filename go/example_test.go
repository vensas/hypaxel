package hypaxel_test

import (
	"bytes"
	"fmt"
	"slices"
	"time"

	hypaxel "github.com/vensas/hypaxel/go"
)

func Example() {
	var out bytes.Buffer
	w := hypaxel.NewWriter(&out)
	header := w.AddStyle(hypaxel.Style{Font: hypaxel.Font{Bold: true, Color: hypaxel.White}, Fill: hypaxel.RGB(0x30, 0x54, 0x96)})
	money := w.AddStyle(hypaxel.Style{NumberFormat: hypaxel.FormatThousands2})

	sheet, err := w.AddSheetOptions("Sales", hypaxel.SheetOptions{FrozenRows: 1, Columns: hypaxel.Widths(20, 12, 14)})
	if err != nil {
		panic(err)
	}
	sheet.WriteStrings([]string{"Product", "Date", "Revenue"}, header)
	sheet.WriteRow("Widget", time.Date(2024, 5, 1, 0, 0, 0, 0, time.UTC), hypaxel.Styled(1234.5, money))
	sheet.WriteRow("Total", nil, hypaxel.Styled(hypaxel.Formula("SUM(C2:C2)"), money))
	if err := w.Close(); err != nil {
		panic(err)
	}
	fmt.Println(string(out.Bytes()[:2]))
	// Output: PK
}

func ExampleSheet_typed() {
	var out bytes.Buffer
	w := hypaxel.NewWriter(&out)
	sheet, err := w.AddSheet("Orders")
	if err != nil {
		panic(err)
	}
	for id := range 3 {
		sheet.WriteInt(int64(id))
		sheet.WriteString("Customer")
		sheet.WriteFloat(99.5)
		sheet.WriteTime(time.Date(2024, 5, 1, 9, 30, 0, 0, time.UTC))
		if err := sheet.EndRow(); err != nil {
			panic(err)
		}
	}
	fmt.Println(sheet.Row(), w.Close())
	// Output: 3 <nil>
}

func ExampleWriteSheetSeq() {
	type Order struct {
		ID      int       `xlsx:"Order #,width=10"`
		Created time.Time `xlsx:"Created,date"`
		Amount  float64   `xlsx:"Amount,width=14,format=#,##0.00"`
		Note    string    `xlsx:"-"`
	}
	orders := []Order{{ID: 1, Amount: 1234.5}, {ID: 2, Amount: 99.99}}

	var out bytes.Buffer
	w := hypaxel.NewWriter(&out)
	if err := hypaxel.WriteSheetSeq(w, "Orders", slices.Values(orders)); err != nil {
		panic(err)
	}
	fmt.Println(w.Close())
	// Output: <nil>
}
