# Hypaxel for Go

**High-performance, constant-memory XLSX (Excel) writer for Go, with no dependencies.**

Hypaxel writes `.xlsx` files as a forward-only stream. Each cell is serialized to XML the moment
you write it and deflated into a small buffer that is flushed to your `io.Writer`, so memory stays
flat whether you export 10 rows or a million, and a download can start while your query is still
running.

- **Fast:** writes 100k rows × 10 columns in about 0.09 s, about **5x faster than excelize's
  `StreamWriter`** and **20x faster than excelize's in-memory API** (see [Performance](#performance)).
- **Constant memory:** the typed cell methods allocate nothing per cell or per row. A million-row
  export peaks at the same resident memory as a 100k-row one.
- **Streaming:** the destination is any `io.Writer` and does not need to be seekable. The
  `hypaxelhttp` package streams to an `http.ResponseWriter` and stops when the client goes away.
- **Data you already have:** typed cells, `[]any` rows, structs with `xlsx` tags, `iter.Seq`,
  `iter.Seq2[T, error]` for database cursors, and channels.
- **Real Excel types:** strings, integers, floats, booleans, `time.Time`, `time.Duration`, formulas.
- **Styles and layout:** fonts, fills, borders, alignment, number formats, column widths, row
  heights, merged cells, frozen panes, auto filters, hidden sheets, tab colors.
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
| `BeginRow(RowOptions{Index, Height, Style})` | Start a row with a height, a row style or a row number further down |
| `SkipCells(n)`, `SkipRows(n)` | Leave cells or rows empty |
| `MergeCells(r1, c1, r2, c2)`, `AutoFilter(r1, c1, r2, c2)` | Ranges, 1-based and inclusive |
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
| `SheetOptions` | `Columns` (`Width`, `Style`, `Hidden`), `FrozenRows`, `FrozenColumns`, `HideGridLines`, `Hidden`, `TabColor` |

## Options

| Option | Default | Meaning |
| --- | --- | --- |
| `WithCompressionLevel(n)` | `1` | Deflate level 0-9. 0 stores without compressing |
| `WithSharedStrings(true)` | off | Shared string table: can be smaller for repeated text, uses more memory |
| `WithFlushThreshold(n)` | `65536` | Compressed bytes buffered before they are written to the destination |
| `WithDate1904(true)` | off | Use the 1904 date system |
| `WithCellReferences(true)` | off | Write a reference such as `B7` on every cell |

## Errors

Functions that validate their arguments (`AddSheet`, `BeginRow`, `MergeCells`, `AutoFilter`)
return the error and leave the workbook usable. The cell methods return nothing, so that hot loops
stay cheap: a failed cell write or a failed write to the destination is recorded, and every later
call (`EndRow`, `WriteRow`, `Close`, ...) returns it. After such an error the output is incomplete
and must be discarded.

Test for causes with `errors.Is`: `ErrInvalidSheetName`, `ErrDuplicateSheetName`, `ErrRowOrder`,
`ErrRowOutOfRange`, `ErrTooManyColumns`, `ErrTextTooLong`, `ErrInvalidStyle`, `ErrInvalidArgument`,
`ErrNoVisibleSheet`, `ErrUnsupported`, `ErrSheetClosed`, `ErrClosed`. Errors from the destination
are returned unchanged.

## Notes and limits

- **Write order:** rows are written top to bottom, and one worksheet at a time. Column widths
  and frozen panes are set when the worksheet is added.
- **Excel limits apply:** 1,048,576 rows, 16,384 columns and 32,767 characters per cell, 31
  characters per worksheet name.
- **Readers that need cell references:** by default Hypaxel omits cell references that follow
  from a cell's position. That is valid and is read by Excel, LibreOffice, excelize, openpyxl and
  calamine, but ExcelJS fails on such files. Use `WithCellReferences(true)` for those.
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
Other processes were using the machine during the runs, so treat small differences as noise.

| Case | 100k rows | Allocated | Peak RSS | 1M rows | Allocated | Peak RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Hypaxel, typed cells | 88 ms | 0.2 MB | 18 MB | 0.92 s | 0.4 MB | 19 MB |
| Hypaxel, struct mapping | 95 ms | 0.2 MB | 18 MB | 0.97 s | 0.4 MB | 19 MB |
| Hypaxel, `WriteRow` | 100 ms | 10.9 MB | 19 MB | 1.02 s | 107 MB | 20 MB |
| Hypaxel, shared strings | 113 ms | 17.7 MB | 28 MB | 1.37 s | 214 MB | 157 MB |
| excelize, `StreamWriter` | 432 ms | 171 MB | 78 MB | 5.62 s | 1,317 MB | 179 MB |
| excelize, in-memory | 1,740 ms | 1,250 MB | 631 MB | not run | not run | not run |

Time and allocated bytes come from `go test -bench` with the rows built beforehand and a
discarding destination: about a dozen iterations per case at 100k rows (one for the in-memory
case), three at 1M. Peak RSS comes from separate runs, one process per case, that generate the
rows on the fly; it includes the Go runtime and both libraries.

The typed and struct-mapping cases make 27 to 66 allocations per workbook, whatever the number of
rows. With shared strings, memory grows with the number of distinct strings, here one per row.
The 1M-row file is 40.5 MB from Hypaxel and 62.9 MB from excelize's `StreamWriter`. At compression
level 6 it is 29.0 MB and takes 1.32 s; at level 0 it is 331 MB and takes 0.37 s.

Run them yourself:

```bash
cd go/tests
go test -run '^$' -bench Write -benchmem                                 # 100,000 rows
go test -run '^$' -bench Write -benchmem -rows 1000000 -benchtime 3x     # 1,000,000 rows
go run ./cmd/bench                                                       # peak RSS, 100,000 rows
go run ./cmd/bench 1000000
```

## Development

Requires Go 1.26 or later. The library has no dependencies; the read-back tests and comparison
benchmarks, which need excelize, live in a module of their own under `tests/`.

```bash
cd go
go test ./...                 # unit tests, standard library only
go vet ./... && gofmt -l .
cd tests && go test ./...     # reads every feature back with excelize
```

Until the module is published, use it from another module with a replace directive:

```
require github.com/vensas/hypaxel/go v0.0.0
replace github.com/vensas/hypaxel/go => /path/to/hypaxel/go
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2026 vensas GmbH.
