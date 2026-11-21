# Hypaxel for Go

**High-performance, constant-memory XLSX (Excel) writer and reader for Go, with no dependencies.**

Hypaxel writes `.xlsx` files as a forward-only stream. Each cell is serialized to XML the moment
you write it and deflated into a small buffer that is flushed to your `io.Writer`, so memory stays
flat whether you export 10 rows or a million, and a download can start while your query is still
running.

- **Fast:** writes 100k rows × 10 columns in about 0.09 s, about **5x faster than excelize's
  `StreamWriter`** and **18x faster than excelize's in-memory API** (see [Performance](#performance)).
- **Constant memory:** the typed cell methods allocate nothing per cell or per row. A million-row
  export peaks at the same resident memory as a 100k-row one.
- **Streaming:** the destination is any `io.Writer` and does not need to be seekable. The
  `hypaxelhttp` package streams to an `http.ResponseWriter` and stops when the client goes away.
- **Data you already have:** typed cells, `[]any` rows, structs with `xlsx` tags, `iter.Seq`,
  `iter.Seq2[T, error]` for database cursors, and channels.
- **Real Excel types:** strings, integers, floats, booleans, `time.Time`, `time.Duration`, formulas.
- **Styles and layout:** fonts, fills, borders, alignment, number formats, column widths, row
  heights, merged cells, frozen panes, auto filters, hidden sheets, tab colors.
- **Streaming reader:** reads rows one at a time with memory bounded by the shared string table
  plus one row, **about 13x faster than excelize's `Rows` iterator** (see [Reading performance](#reading-performance)).
- **Pure Go:** standard library only, no cgo. Errors are returned; bad input never panics.

This is a native Go port of the [`hypaxel` Rust crate](../rust/README.md). The
[.NET](../dotnet/README.md), [Python](../python/README.md) and [Node.js](../node/README.md)
versions use the same design.

## Install

```bash
go get github.com/vensas/hypaxel/go        # not published yet: use a replace directive, see "Development"
```

Requires Go 1.26 or later. The package name is `hypaxel`:

```go
import hypaxel "github.com/vensas/hypaxel/go"
```

## Quick start

```go
type Order struct {
	ID       int       `xlsx:"Order #,width=10"`
	Customer string    `xlsx:"Customer,width=30"`
	Created  time.Time `xlsx:"Created,date"`
	Amount   float64   `xlsx:"Amount,width=14,format=#,##0.00"`
	Internal string    `xlsx:"-"`
}

func export(path string, orders []Order) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()

	w := hypaxel.NewWriter(file)
	if err := hypaxel.WriteSheet(w, "Orders", orders); err != nil {
		return err
	}
	return w.Close()
}
```

Structs get a bold, frozen header row, the tagged column widths and an auto filter. Slices are
written as plain rows:

```go
err := hypaxel.WriteSheet(w, "Matrix", [][]any{{1, "a"}, {2, "b"}})
```

`WriteSheetSeq` takes an `iter.Seq[T]`, `WriteSheetSeq2` an `iter.Seq2[T, error]` and stops at the
first error, and `WriteSheetChan` reads from a channel until it is closed or the context is done.
All of them consume their source lazily.

### Struct tags

```go
`xlsx:"Header,width=20,hidden,date,format=#,##0.00"`
```

| Element | Meaning |
| --- | --- |
| first element | Column header. Empty means the field name |
| `width=N` | Column width in characters, up to 255 |
| `hidden` | Hide the column |
| `date` | Write a `time.Time` as a date without time of day |
| `format=CODE` | Excel number format. Must come last: it takes the rest of the tag, because format codes contain commas |
| `-` | Skip the field |

Fields map to columns in declaration order. Embedded structs are flattened, unexported fields
are skipped, and a header cannot contain a comma.

## Streaming HTTP responses

```go
import (
	hypaxel "github.com/vensas/hypaxel/go"
	"github.com/vensas/hypaxel/go/hypaxelhttp"
)

http.Handle("/orders.xlsx", hypaxelhttp.Handler("orders", func(r *http.Request, w *hypaxel.Writer) error {
	return hypaxel.WriteSheetSeq2(w, "Orders", db.Orders(r.Context()))
}))
```

`Handler` sets `Content-Type` and `Content-Disposition`, flushes every chunk to the client, and
stops the export when the request context is cancelled. If the export fails before anything was
sent, the client gets a 500; if it fails later, the connection is aborted with
`http.ErrAbortHandler` so the browser reports a failed download instead of saving a truncated file.

`Serve` does the same inside your own handler and returns the error, which wraps
`hypaxelhttp.ErrPartial` when part of the workbook had already been sent:

```go
func export(rw http.ResponseWriter, r *http.Request) {
	err := hypaxelhttp.Serve(rw, r, "report.xlsx", func(w *hypaxel.Writer) error {
		sheet, err := w.AddSheet("Report")
		if err != nil {
			return err
		}
		return sheet.WriteRow("Generated", time.Now())
	})
	if err != nil && !errors.Is(err, hypaxelhttp.ErrPartial) {
		http.Error(rw, "export failed", http.StatusInternalServerError)
	}
}
```

Any other framework works the same way: pass its response writer to `hypaxel.NewWriter`.

## Full control with `Writer`

```go
w := hypaxel.NewWriter(dst)
header := w.AddStyle(hypaxel.Style{
	Font:      hypaxel.Font{Bold: true, Color: hypaxel.White},
	Fill:      hypaxel.RGB(0x30, 0x54, 0x96),
	Alignment: hypaxel.Alignment{Horizontal: hypaxel.AlignCenter},
})
money := w.AddStyle(hypaxel.Style{NumberFormat: hypaxel.FormatThousands2})

sheet, err := w.AddSheetOptions("Sales", hypaxel.SheetOptions{
	FrozenRows: 1,
	Columns:    hypaxel.Widths(20, 12, 14),
})
if err != nil {
	return err
}
sheet.WriteStrings([]string{"Product", "Date", "Revenue"}, header)
sheet.WriteRow("Widget", time.Date(2024, 5, 1, 0, 0, 0, 0, time.UTC), hypaxel.Styled(1234.5, money))
sheet.WriteRow("Gadget", time.Date(2024, 5, 2, 0, 0, 0, 0, time.UTC), hypaxel.Styled(99.99, money))
sheet.WriteRow("Total", nil, hypaxel.Styled(hypaxel.Formula("SUM(C2:C3)"), money))
return w.Close()
```

For the hottest loops, write cells one at a time. Nothing is boxed and nothing is allocated:

```go
for _, o := range orders {
	sheet.WriteInt(int64(o.ID))
	sheet.WriteString(o.Customer)
	sheet.WriteFloat(o.Total, money)
	sheet.WriteTime(o.CreatedAt)
	if err := sheet.EndRow(); err != nil {
		return err
	}
}
```

Worksheets are written one at a time: adding a worksheet completes the previous one.

**`Writer`**

| Function | Purpose |
| --- | --- |
| `NewWriter(dst, opts...)` | Start a workbook on any `io.Writer` |
| `AddStyle(style)` | Register a cell format; identical styles share a `StyleID` |
| `AddSheet(name)`, `AddSheetOptions(name, opts)` | Add a worksheet |
| `StructColumns(v)` | Column widths and visibility from a struct type's tags |
| `Flush()` | Send everything written so far to the destination now |
| `Close()` | Complete the workbook. Does not close the destination |
| `Err()` | The error that stopped the writer, if any |

**`Sheet`**

| Method | Purpose |
| --- | --- |
| `WriteString`, `WriteInt`, `WriteUint`, `WriteFloat`, `WriteBool` | Typed cells, each with an optional style |
| `WriteTime`, `WriteDate`, `WriteDuration` | Date and time, date only, and elapsed time |
| `WriteFormula`, `WriteBlank` | A formula, and an empty cell that carries a style |
| `Write(v, style?)` | Any supported value |
| `EndRow()` | End the row and return any pending error |
| `WriteRow(values...)`, `WriteRowStyled(style, values...)` | One row from values |
| `WriteStrings(values, style?)` | One row of text, such as a header |
| `WriteStruct(v)`, `WriteStructHeader(v, style?)` | One row from a struct, and its header row |
| `BeginRow(RowOptions{Index, Height, Style, OutlineLevel, Hidden, Collapsed})` | Start a row with a height, a row style, a grouping level or a row number further down |
| `SkipCells(n)`, `SkipRows(n)` | Leave cells or rows empty |
| `MergeCells(r1, c1, r2, c2)`, `AutoFilter(r1, c1, r2, c2)` | Ranges, 1-based and inclusive |
| `AddTable(Table{...})` | Turn a range into an Excel table |
| `Row()`, `Column()` | Current position |

**Package functions**

| Function | Purpose |
| --- | --- |
| `WriteSheet`, `WriteSheetSeq`, `WriteSheetSeq2`, `WriteSheetChan` | A whole worksheet from a slice, sequence or channel |
| `Styled(v, style)`, `Formula("...")` | A value with its own style, and a formula, for `Write` and `WriteRow` |
| `RGB(r, g, b)`, `ParseColor("#RRGGBB")` | Colors |
| `BorderAll(style, color)`, `Widths(w...)` | Shorthands for borders and column widths |
| `ColumnName(n)`, `CellRef(row, column)` | `27` to `"AA"`, `(7, 2)` to `"B7"` |

`Write`, `WriteRow` and struct fields accept strings, all integer and float types, `bool`,
`time.Time`, `time.Duration`, `[]byte` (as text), `Formula`, `Cell`, `nil`, pointers to these
(a nil pointer is an empty cell) and named types based on them. Types implementing
`driver.Valuer` (such as `sql.NullString`), `encoding.TextMarshaler` or `fmt.Stringer` are written
through those methods.

**Styles** are plain structs whose zero value means "default":

| Type | Fields |
| --- | --- |
| `Style` | `NumberFormat`, `Font`, `Fill`, `Border`, `Alignment` |
| `Font` | `Name`, `Size`, `Bold`, `Italic`, `Underline`, `Strike`, `Color` |
| `Border` | `Left`, `Right`, `Top`, `Bottom`, each a `BorderLine{Style, Color}` with 13 line styles |
| `Alignment` | `Horizontal`, `Vertical`, `WrapText`, `ShrinkToFit`, `Indent`, `Rotation` |
| `SheetOptions` | `Columns` (`Width`, `Style`, `Hidden`), `FrozenRows`, `FrozenColumns`, `HideGridLines`, `Hidden`, `TabColor`, `RowOutlineLevels`, `OutlineSummaryAbove`, `AutoFit` |

### Grouped rows

Give rows an outline level to make them collapsible. The deepest level is declared when the
worksheet is added, because Excel reads it before the rows:

```go
sheet, _ := w.AddSheetOptions("Regions", hypaxel.SheetOptions{RowOutlineLevels: 2, OutlineSummaryAbove: true})
sheet.WriteRow("North", 300)
sheet.BeginRow(hypaxel.RowOptions{OutlineLevel: 1})
sheet.WriteRow("Store A", 120)
sheet.BeginRow(hypaxel.RowOptions{OutlineLevel: 2, Hidden: true})
sheet.WriteRow("Till 1", 60)
```

A row begun with `BeginRow` is filled by the next whole-row write (`WriteRow`, `WriteStrings`,
`WriteStruct`, ...) as long as it has no cells yet. `Hidden` and `Collapsed` mark the rows of a
collapsed group and its summary row. `OutlineSummaryAbove` puts the expand button above the group.

### Excel tables

`AddTable` turns a range into a named, styled table with filter buttons. With a header row (the
default), `Columns` must repeat the header texts exactly:

```go
sheet.WriteRow("Product", "Units")
sheet.WriteRow("Widget", 3)
sheet.WriteRow("Gadget", 5)
err := sheet.AddTable(hypaxel.Table{
	FirstRow: 1, FirstColumn: 1, LastRow: sheet.Row(), LastColumn: 2,
	Name:    "Orders",
	Columns: []string{"Product", "Units"},
	Style:   hypaxel.TableStyleMedium(9),
})
```

The range may include rows not written yet. Names are unique in the workbook and follow Excel's
name rules; an empty `Name` gives `Table1`, `Table2`, ... `Style` defaults to Medium 2;
`TableStyleNone` and `TableStyleLight(n)`, `TableStyleMedium(n)`, `TableStyleDark(n)` select the
others. `NoHeaderRow`, `NoFilterButtons`, `NoRowStripes`, `ColumnStripes`,
`HighlightFirstColumn` and `HighlightLastColumn` adjust the rest. Tables cannot overlap each
other, merged cells or the worksheet's `AutoFilter`.

### Auto-fit columns

`AutoFit` sizes columns without an explicit width to their content:

```go
sheet, _ := w.AddSheetOptions("Report", hypaxel.SheetOptions{
	AutoFit: &hypaxel.AutoFit{},          // measure the first 1,000 rows, widths up to 255
	Columns: []hypaxel.Column{{Width: 12}}, // explicit widths win
})
```

Column widths come before the cells in the file, so the worksheet's XML is held back until
`SampleRows` rows are written (default 1,000), 8 MiB of XML is reached or the sheet ends. Then
the widths are fixed and streaming continues as usual; memory is bounded by the sample. Widths are
estimated from the text, number format, font size and boldness of each cell. Formulas, blank
cells and rows after the sample are not measured. `MaxWidth` caps a fitted width.

## Reading

```go
r, err := hypaxel.OpenReader("orders.xlsx")   // or hypaxel.NewReader(readerAt, size)
if err != nil {
	return err
}
defer r.Close()
fmt.Println(r.SheetNames())

rows, err := r.Rows("Orders")                  // or r.RowsAt(0)
if err != nil {
	return err
}
defer rows.Close()
for rows.Next() {
	row := rows.Row()                          // reused by the next call to Next
	// row.Index() is the 1-based Excel row; rows without values are skipped.
	for column, v := range row.Cells() {
		switch v.Kind() {
		case hypaxel.ValueString:
			fmt.Println(hypaxel.CellRef(row.Index(), column), v.Text())
		case hypaxel.ValueNumber:
			n, _ := v.Float()
			fmt.Println(n)
		case hypaxel.ValueDateTime:
			t, _ := v.Time()                   // or v.Duration() for [h]:mm:ss formats
			fmt.Println(t)
		case hypaxel.ValueBool:
			b, _ := v.Bool()
			fmt.Println(b)
		case hypaxel.ValueError:
			fmt.Println("error", v.Text())    // #N/A, #DIV/0!, ...
		}
	}
	amount := row.Get(3)                       // by 1-based column; empty Value if missing
	dense := row.AppendValues(nil)             // columns 1..LastColumn, empty Values for gaps
	_, _ = amount, dense
}
return rows.Err()
```

`rows.All()` returns an `iter.Seq2[*Row, error]` for `for row, err := range rows.All()`; the loop
closes the rows when it ends. `Row` also has `Len`, `LastColumn` and `Cell(i)` for index loops.

### Reading tables, row properties and column widths

What the writer adds on top of the values can be read back, so a test can check a written file
with Hypaxel alone:

```go
tables, err := r.Tables("Orders")              // or r.TablesAt(0); []hypaxel.Table, as passed to AddTable
if err != nil {
	return err
}
fmt.Println(tables[0].Name, tables[0].Columns, tables[0].Style)

rows, err := r.Rows("Orders")
if err != nil {
	return err
}
defer rows.Close()
layout, err := rows.Layout()                   // available before the first row
if err != nil {
	return err
}
width, ok := layout.ColumnWidth(1)             // or layout.ColumnWidths: runs of columns with one width
fmt.Println(width, ok, layout.OutlineSummaryAbove, layout.RowOutlineLevels)
for rows.Next() {
	row := rows.Row()
	height, hasHeight := row.Height()          // points
	fmt.Println(row.OutlineLevel(), row.Hidden(), row.Collapsed(), height, hasHeight)
}
return rows.Err()
```

- **Tables** live in parts of their own, which are read on each call to `Tables`; reading rows
  never touches them. A table comes back as the `Table` that `AddTable` takes: `TableStyleMedium2`
  as the zero `TableStyle`, a custom (not built-in) style as `TableStyleNone`, generated names such
  as `Table1` and `Column1` filled in, and `NoFilterButtons` set for a table without a header row.
- **Row properties** are those of the rows that `Next` returns; rows without values are skipped
  as before. `Height` is the stored height, whether set explicitly or by Excel.
- **Layout** precedes the rows in the file, so `Layout` reads nothing extra. `ColumnWidths` holds
  explicit and auto-fitted widths alike; columns that are not listed have the default width.

**`Reader`**

| Function | Purpose |
| --- | --- |
| `OpenReader(path, opts...)`, `NewReader(src io.ReaderAt, size, opts...)` | Open a workbook; `Close` closes a file opened by path |
| `Sheets()`, `SheetNames()` | Sheets in workbook order with `Name`, `Visibility` (`SheetVisible`, `SheetHidden`, `SheetVeryHidden`) and `Kind` (`SheetKindWorksheet`, `SheetKindChartsheet`, `SheetKindOther`) |
| `Rows(name)`, `RowsAt(index)` | Stream a sheet, by name (exact, then case-insensitive) or 0-based index |
| `Tables(name)`, `TablesAt(index)` | The Excel tables of a sheet as `[]Table` |
| `Rows.Layout()` | `*SheetLayout`: `ColumnWidths`, `ColumnWidth(column)`, `OutlineSummaryAbove`, `RowOutlineLevels` |
| `Row.OutlineLevel()`, `Hidden()`, `Collapsed()`, `Height()` | Properties of the current row |
| `Date1904()` | Whether the workbook uses the 1904 date system |
| `WithMaxSharedStringsBytes(n)` | Option: cap the shared string table (default 1 GiB) |
| `WithConcurrentInflate(on)` | Option: decompress on a separate goroutine while parsing (default on, off when `GOMAXPROCS` is 1) |

**`Value`** is a small immutable struct; the zero value is empty.

| Method | Returns |
| --- | --- |
| `Kind()` | `ValueEmpty`, `ValueString`, `ValueNumber`, `ValueBool`, `ValueDateTime` or `ValueError` |
| `Text()` | The text of a string or error value |
| `Float()` | A number, or the serial number of a date |
| `Bool()` | A boolean |
| `Time()` | A date value as a UTC `time.Time` (the wall clock Excel shows), rounded to the millisecond |
| `Duration()` | A date value as elapsed time, as `[h]:mm:ss` formats show it |
| `DateKind()` | What the number format shows: `DateKindDate`, `DateKindDateTime`, `DateKindTime`, `DateKindDuration` |
| `String()` | The value as text, like Excel's General format, with dates in ISO 8601 |

- **Dates:** Excel stores dates as numbers. A number whose built-in or custom number format shows
  a date, time or duration becomes a `ValueDateTime`; `Float()` still gives the serial. Both date
  systems are supported, including Excel's fictitious 1900-02-29 (serial 60, for which `Time()`
  returns false). A time of day without a date falls on 1899-12-31 (1904-01-01 in the 1904
  system); use its `Clock()`. `t="d"` cells with ISO 8601 text are read as dates too.
- **Formulas:** the cached result is returned; a formula that was never calculated has no value.
- **Memory:** rows are decompressed and parsed as a stream. What stays in memory is the shared
  string table (text that Excel stores once per workbook, loaded when the first sheet is opened)
  plus the current row. Values never point into reused buffers: strings stay valid after the
  row is replaced, and taking a row's inline strings allocates once per row.
- **Concurrent inflate:** by default each sheet (and the shared string table) is decompressed on
  a goroutine of its own, up to 256 KiB ahead of the parser, which roughly halves the time to read
  a sheet when a CPU core is free. It costs about 4% more CPU time in total, is off when
  `GOMAXPROCS` is 1, and `WithConcurrentInflate(false)` turns it off for batch jobs that already
  keep every core busy. Call `rows.Close()` when you stop reading early; a range loop over
  `rows.All()` does that for you.
- **Concurrency:** a `Reader` is safe for concurrent use: open several sheets, or the same sheet
  twice, from different goroutines. Each `Rows` belongs to one goroutine.
- **Compatibility:** files from Excel, LibreOffice, excelize, openpyxl and other producers:
  package relationships instead of fixed paths, Strict Open XML, prefixed namespaces, cells and
  rows without references, out-of-order cells, rich text, inline strings, phonetic runs, CDATA,
  and Excel's `_xHHHH_` escapes.
- **Safety:** document type declarations are rejected (no entity expansion), every XML token is
  limited to 16 MiB and every row to 16,384 cells and 256 MiB of text, every sheet to 4,096 tables, ZIP entries must match
  their declared size and CRC-32, and `WithMaxSharedStringsBytes` caps the shared string table.
  Damaged input returns an error, never a panic or a hang; the reader is fuzz-tested.

Errors name the part they come from, as `*ReadError` with a `Part` field:

| Error | Cause |
| --- | --- |
| `ErrInvalidFile` | Not a ZIP archive; a legacy `.xls` or password-protected workbook (OLE file); a Word or PowerPoint file |
| `ErrSheetNotFound` | No sheet with that name or index |
| `ErrMalformed` | Invalid XML, a bad cell value or reference, a missing part, or a ZIP entry that fails its size or CRC check |
| `ErrLimitExceeded` | A safety limit above was reached |
| `ErrUnsupported` | A chart sheet, a UTF-16 part, an encrypted ZIP entry or an unknown compression method |

Not read (yet): formulas' text, styles beyond date detection, merged ranges, comments and
hyperlinks.

## Options

Writer options:

| Option | Default | Meaning |
| --- | --- | --- |
| `WithCompressionLevel(n)` | `1` | Deflate level 0-9. 0 stores without compressing |
| `WithSharedStrings(true)` | off | Shared string table: can be smaller for repeated text, uses more memory |
| `WithFlushThreshold(n)` | `65536` | Compressed bytes buffered before they are written to the destination |
| `WithDate1904(true)` | off | Use the 1904 date system |
| `WithCellReferences(true)` | off | Write a reference such as `B7` on every cell |

## Errors

Functions that validate their arguments (`AddSheet`, `BeginRow`, `MergeCells`, `AutoFilter`,
`AddTable`) return the error and leave the workbook usable. The cell methods return nothing, so
that hot loops stay cheap: a failed cell write or a failed write to the destination is recorded,
and every later call (`EndRow`, `WriteRow`, `Close`, ...) returns it. After such an error the
output is incomplete and must be discarded.

Test for causes with `errors.Is`: `ErrInvalidSheetName`, `ErrDuplicateSheetName`, `ErrRowOrder`,
`ErrRowOutOfRange`, `ErrTooManyColumns`, `ErrTextTooLong`, `ErrInvalidStyle`, `ErrInvalidArgument`,
`ErrInvalidTableName`, `ErrDuplicateTableName`, `ErrOverlappingRange`, `ErrNoVisibleSheet`,
`ErrUnsupported`, `ErrSheetClosed`, `ErrClosed`. Errors from the destination are returned
unchanged.

## Notes and limits

- **Write order:** rows are written top to bottom, and one worksheet at a time. Column widths
  (unless auto-fitted), frozen panes and the outline depth are set when the worksheet is added.
- **Excel limits apply:** 1,048,576 rows, 16,384 columns and 32,767 characters per cell, 31
  characters per worksheet name.
- **Readers that need cell references:** by default Hypaxel omits cell references that follow
  from a cell's position. That is valid and is read by Excel, LibreOffice, excelize, openpyxl,
  calamine and Hypaxel's own reader, but ExcelJS, `thedatashed/xlsxreader` and `tealeg/xlsx` read
  such files wrongly or fail. Use `WithCellReferences(true)` for those.
- **Colors in excelize:** Hypaxel writes no theme part. excelize 2.11 returns empty fill and
  border colors for such workbooks when reading styles back; the colors are in the file and other
  readers show them.
- **Dates:** Excel has no time zones, so a `time.Time` is written as the wall-clock time of its
  location; convert with `t.UTC()` or `t.In(loc)` first if you need another one. The zero
  `time.Time` is an empty cell. Dates Excel cannot represent (before 1900, or before 1904 in the
  1904 system) are written as ISO 8601 text.
- **Numbers:** Excel stores every number as a float64, so integers beyond ±2^53 lose precision.
  `NaN` and infinities are written as `#NUM!`.
- **Text:** invalid UTF-8 is replaced with U+FFFD, and control characters are written with
  Excel's `_xHHHH_` escape.
- **Formulas:** they are stored without cached results; Excel recalculates them when the file opens.
- **Styles** belong to the writer that created them.
- **Concurrency:** a `Writer` is not safe for concurrent use. Separate writers are independent.
- **`WriteRow` allocates:** passing values as `any` boxes them, which costs about one small
  allocation per cell. The typed methods and struct mapping do not.

## Performance

Writing 10 columns per row (int, 2× string, 2× float, a decimal-like float, `time.Time`, bool,
int64, unique string), the same workload as the other Hypaxel implementations.

Measured on an Apple M1 Max (10 cores, macOS 26.6) with Go 1.27.4 against excelize 2.11.1.
The machine was otherwise idle.

| Case | 100k rows | Allocated | Peak RSS | 1M rows | Allocated | Peak RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Hypaxel, typed cells | 87 ms | 0.2 MB | 19 MB | 0.84 s | 0.4 MB | 21 MB |
| Hypaxel, struct mapping | 92 ms | 0.2 MB | 19 MB | 0.91 s | 0.2 MB | 21 MB |
| Hypaxel, `WriteRow` | 98 ms | 10.9 MB | 20 MB | 0.96 s | 107 MB | 21 MB |
| Hypaxel, shared strings | 107 ms | 17.8 MB | 30 MB | 1.17 s | 214 MB | 159 MB |
| excelize, `StreamWriter` | 403 ms | 171 MB | 79 MB | 4.02 s | 1,317 MB | 191 MB |
| excelize, in-memory | 1,598 ms | 1,250 MB | 650 MB | not run | not run | not run |

Time and allocated bytes come from `go test -bench` with the rows built beforehand and a
discarding destination: about a dozen iterations per case at 100k rows (one for the in-memory
case), three at 1M. Peak RSS comes from separate runs, one process per case, that generate the
rows on the fly; it includes the Go runtime and both libraries.

The typed and struct-mapping cases make 27 to 62 allocations per workbook, whatever the number of
rows. With shared strings, memory grows with the number of distinct strings, here one per row.
The 1M-row file is 40.5 MB from Hypaxel and 62.9 MB from excelize's `StreamWriter`. At compression
level 6 it is 29.0 MB and takes 1.25 s; at level 0 it is 331 MB and takes 0.31 s.

### Reading performance

The same workload, 1M rows written by Hypaxel with a cell reference on every cell (as Excel writes
them; two of the other readers need them), read cell by cell. Every reader must return the same
checksum (rows, cells, sum of numbers, dates as Unix seconds, string bytes, `TRUE` count), which the
harness verifies. Measured on an Apple M1 Max (10 cores, macOS 26.6) with Go 1.27.4, excelize
2.11.1, thedatashed/xlsxreader 1.2.8 and tealeg/xlsx 3.3.13, each run in its own process, best of
three, on an otherwise idle machine.

| Reader | Inline strings (70.7 MB) | Peak RSS | Shared strings (71.5 MB) | Peak RSS |
| --- | ---: | ---: | ---: | ---: |
| **Hypaxel**, `Rows.Next` | **1.14 s** | **21 MB** | **1.18 s** | **74 MB** |
| **Hypaxel**, `Rows.All` | **1.14 s** | **21 MB** | **1.18 s** | **73 MB** |
| excelize, `Rows` with `RawCellValue` | 14.72 s | 22 MB | 16.54 s | 142 MB |
| excelize, `Rows` (formatted values) | 66.34 s | 23 MB | 67.41 s | 142 MB |
| xlsxreader, `ReadRows` | 13.79 s | 22 MB | 12.26 s | 163 MB |
| tealeg/xlsx, `OpenFile` | not run | | not run | |

With `GOMAXPROCS=1` there is no core to overlap decompression with, so the reader inflates on the
caller's goroutine and takes about 2.0–2.1 s (`GOMAXPROCS=1 go run ./cmd/bench read 1000000`).

At 100k rows tealeg/xlsx, which loads the whole sheet, takes 2.21 s and peaks at 694 MB, against
0.12 s and 19 MB for Hypaxel. Per 100k rows, Hypaxel allocates 5.3 MB in 102k allocations (one string
per row holding its inline text) with inline strings, and 15 MB in 1.6k allocations with shared
strings (mostly the table itself); excelize allocates 0.97 GB in 20M allocations.

Notes on comparability: formatted excelize values only match on cell counts and text, because
numbers and dates come back as display text. xlsxreader truncates date-times instead of rounding
them, so about half of the dates in this workload come back one second early (00:00:59 for a
stored 00:01:00); the harness allows for that. xlsxreader and tealeg/xlsx 3.3.13 cannot read
cells without references, and tealeg's `ValueOnly` option crashes on inline strings, so it runs
with default options.

Inflate and CRC-32 in Go's `compress/flate` and `archive/zip` take as long as all the parsing,
so Hypaxel decompresses each sheet and the shared string table on a separate goroutine while
the caller's goroutine parses (see `WithConcurrentInflate`); a single reader's time is then
bounded by inflate alone. The parser is purpose-built: it tokenizes the worksheet XML at
470 MB/s, 7x faster than `encoding/xml`'s `RawToken` (66 MB/s) on the same input
(`go test -bench Tokenize` in `go/`), with single-pass fast paths for common cells and plain
shared strings.

Run them yourself:

```bash
cd go/tests
go test -run '^$' -bench Write -benchmem                                 # 100,000 rows
go test -run '^$' -bench Write -benchmem -rows 1000000 -benchtime 3x     # 1,000,000 rows
go run ./cmd/bench                                                       # peak RSS, 100,000 rows
go run ./cmd/bench 1000000
go test -run '^$' -bench Read -benchmem                                  # every reader, 100,000 rows
go run ./cmd/bench read 1000000                                          # time and peak RSS
```

## Development

Requires Go 1.26 or later. The library has no dependencies; the read-back tests and comparison
benchmarks, which need excelize and the other readers compared above, live in a module of their
own under `tests/`.

```bash
cd go
go test ./...                 # unit tests, standard library only
go vet ./... && gofmt -l .
go test -fuzz FuzzReader -fuzztime 1m    # or FuzzSheetXML
cd tests && go test ./...     # reads every feature back with excelize, compares the readers
```

Until the module is published, use it from another module with a replace directive:

```
require github.com/vensas/hypaxel/go v0.0.0
replace github.com/vensas/hypaxel/go => /path/to/hypaxel/go
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2026 vensas GmbH.
