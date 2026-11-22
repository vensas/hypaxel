# Hypaxel for Java

**High-performance, constant-memory XLSX (Excel) writer and streaming reader for the JVM, written in pure Java.**

Hypaxel writes `.xlsx` files as a forward-only stream. Each cell is serialized to UTF-8 XML the
moment you write it and compressed straight into a small buffer, so memory stays flat whether you
export 10 rows or a million, and a download can start while your query is still running. It
[reads](#reading) workbooks the same way: one row at a time, with memory bounded by the shared
string table plus one row.

- **Fast:** writes 100k rows × 10 columns in about 0.11 s, **2.5 to 3.7x faster than FastExcel**
  and **9x faster than Apache POI's streaming writer (SXSSF)**; reads 1M rows × 10 columns in
  about 0.7 s, **about 4x faster than FastExcel's reader** and **5 to 7x faster than POI's event
  API** (see [Performance](#performance)).
- **No garbage per cell:** a workbook allocates its buffers once (about 0.17 MB in total), no
  matter how many rows follow.
- **Streaming:** writes to any `OutputStream`, seekable or not: a servlet response, a Spring
  `StreamingResponseBody`, a socket, a file.
- **Zero dependencies:** only `java.base`. No JNI, no native library. A Java module
  (`de.vensas.hypaxel`) that also works on the class path.
- **Real Excel types:** numbers, strings, booleans, `java.time` dates, times and durations,
  `BigDecimal`, formulas. `null` becomes an empty cell.
- **Object mapping:** records and JavaBeans become rows through cached method handles, with
  `@ExcelColumn` for headers, order, number formats and widths.
- **Styles and layout:** fonts, fills, borders, alignment, number formats, merged cells, frozen
  panes, auto filters, Excel tables, auto-fit columns, row grouping, hidden sheets, tab colors.

Hypaxel for Java is a native port of the [`hypaxel` Rust crate](../rust/README.md). The
[.NET](../dotnet/README.md), [Python](../python/README.md) and [Node.js](../node/README.md)
versions use the same design. It is written in Java so that it reads naturally from Java, Kotlin
and Scala.

> Status: **creating and reading Excel files.** Editing existing workbooks is not implemented.

## Install

```xml
<dependency>
  <groupId>de.vensas</groupId>
  <artifactId>hypaxel</artifactId>
  <version>0.4.0</version>
</dependency>
```

```kotlin
implementation("de.vensas:hypaxel:0.4.0")
```

Requires Java 17 or later.

## Quick start

```java
import de.vensas.hypaxel.*;

public record Order(
        @ExcelColumn(name = "Order #", order = 0, width = 10) int id,
        String customer,
        @ExcelColumn(format = "#,##0.00") BigDecimal amount,
        @ExcelColumn(format = "yyyy-mm-dd") LocalDate created,
        @ExcelIgnore String internalNote) {
}

try (XlsxWriter writer = XlsxWriter.create(Path.of("orders.xlsx"))) {
    writer.writeWorksheet("Orders", Order.class, orders);
}
```

`writeWorksheet` adds a worksheet with a styled, frozen header row, an auto filter and column
widths. `orders` is any `Iterable` or `Stream`, so rows can come straight from a database cursor.

Cell by cell, with the typed `write` overloads (no boxing, nothing allocated per cell):

```java
try (XlsxWriter writer = XlsxWriter.create(out)) {
    Worksheet sheet = writer.addWorksheet("Orders");
    for (Order order : orders) {
        sheet.write(order.id()).write(order.customer()).write(order.amount()).write(order.created());
        sheet.endRow();
    }
}
```

## Reading

```java
try (XlsxReader reader = XlsxReader.open(Path.of("orders.xlsx"))) {
    System.out.println(reader.sheetNames());

    try (SheetReader rows = reader.worksheet("Orders")) {          // or worksheet(0)
        for (Row row = rows.nextRow(); row != null; row = rows.nextRow()) {
            // row.number() is the 1-based Excel row; rows without values are skipped by default.
            String customer = row.getString(1);                     // 1-based columns
            double amount = row.getNumber(3);                       // NaN if not a number
            LocalDateTime created = row.getLocalDateTime(4);        // null if not a date
            switch (row.type(5)) {
                case STRING -> System.out.println(row.getString(5));
                case NUMBER, DATE_TIME -> System.out.println(row.getNumber(5));
                case BOOLEAN -> System.out.println(row.getBoolean(5));
                case ERROR -> System.out.println(row.getError(5));  // "#N/A", ...
                case EMPTY -> { }
            }
        }
    }
}
```

`XlsxReader.open` takes a `Path`, a `SeekableByteChannel`, a `byte[]` or an `InputStream`. The
XLSX directory sits at the end of the file, so an `InputStream` is read into memory first; use a
`Path` for large files. Closing the reader closes the file or channel.

Convenience over the same stream:

```java
for (Row row : reader.worksheet(0)) { ... }                 // Iterable; the Row is reused
List<Value> dense = row.values();                           // columns 1..lastColumn(), Value.EMPTY for gaps
List<Object> objects = row.toList();                        // String, Double, Boolean, LocalDateTime, ..., null for gaps
for (CellValue cell : row.cells()) { cell.column(); cell.value(); }

try (Stream<Row> rows = reader.worksheet("Orders").stream()) {   // independent copies
    List<List<Object>> table = rows.map(Row::toList).toList();
}
```

`SheetReader.nextRow()` and its iterator reuse one `Row`: it is valid until the next row is read.
Keep `row.copy()`, `row.toList()` or the values you need. `stream()` yields copies, so they may be
collected.

Tables, row properties and column widths that the writer can add are read back as well, so a
workbook written with Hypaxel can be checked with Hypaxel alone:

```java
List<ExcelTable> tables = reader.tables("Orders");          // or tables(0); the same class addTable takes
tables.get(0).name(); tables.get(0).columns(); tables.get(0).style();

try (SheetReader rows = reader.worksheet("Orders")) {
    List<ColumnWidth> widths = rows.columnWidths();         // known before the first row is read
    OptionalDouble width = rows.columnWidth(2);             // empty: default width
    boolean hidden = rows.columnHidden(4);
    boolean summaryAbove = rows.outlineSummaryAbove();
    for (Row row : rows) {
        row.outlineLevel(); row.hidden(); row.collapsed(); row.height();
    }
}
```

Tables are stored in their own parts and are read only when `tables` is called; reading rows does
not touch them. They are returned from top to bottom, then left to right, whatever order the file
lists them in. A table style that is not one of Excel's built-in styles reads as
`TableStyle.NONE`. `totalsRow()` tells whether the last row of a table's range is a totals row;
such a table can be read but not written, so `addTable` rejects it. Row properties are reported for the rows that are returned, that is rows with at
least one value. To also get the rows that the file stores without a value (a spacer row with a
height, an empty hidden row), open the workbook with
`ReadOptions.builder().includeEmptyRows(true).build()`: they come back with `cellCount() == 0` and
their number and properties. Rows that the file does not store are never returned.

**`XlsxReader`**

| Method | Purpose |
| --- | --- |
| `open(Path \| SeekableByteChannel \| byte[] \| InputStream, ReadOptions?)` | Reads the package structure, sheet list and cell formats. `ReadOptions`: `maxSharedStringsBytes` (default 1 GiB), `includeEmptyRows` (default false) |
| `sheets()`, `sheetNames()` | Sheets in workbook order: name, `SheetVisibility` (`VISIBLE`, `HIDDEN`, `VERY_HIDDEN`), `SheetKind` (`WORKSHEET`, `CHARTSHEET`, `OTHER`) |
| `worksheet(name)` | Sheet by name: exact match first, then ignoring case |
| `worksheet(index)` | Sheet by 0-based position |
| `isDate1904()` | Whether the workbook uses the 1904 date system |
| `sharedStringCount()` | Entries in the shared string table (loads it) |
| `tables(name)`, `tables(index)` | The sheet's Excel tables as `ExcelTable`: name, range, columns, style, options and `totalsRow()`; read on each call, ordered by position |

**`SheetReader`**

| Method | Purpose |
| --- | --- |
| `nextRow()`, `iterator()`, `stream()` | The rows with at least one value, front to back; with `includeEmptyRows`, every row the file stores |
| `columnWidths()` | `ColumnWidth` runs (`firstColumn()`, `lastColumn()`, `width()`, `hidden()`) the sheet defines, explicit and auto-fitted, in file order |
| `columnWidth(column)` | Width of a 1-based column as `OptionalDouble`; empty for the default width |
| `columnHidden(column)` | Whether a 1-based column is hidden |
| `outlineSummaryAbove()`, `rowOutlineLevels()` | The sheet's outline settings |

The layout getters work before the first row, after the rows were read and after `close()` once the
layout has been read. They throw `INVALID_STATE` if the reader was closed before the layout was read,
and repeat the error if reading the layout failed.

**`Row`**

| Method | Purpose |
| --- | --- |
| `number()`, `cellCount()`, `lastColumn()`, `column(i)` | Row number, cells with a value and their columns |
| `outlineLevel()`, `hidden()`, `collapsed()`, `height()` | Grouping level 0 to 7, hidden and collapsed state, height in points (`OptionalDouble`) |
| `type(column)` | `CellType`: `EMPTY`, `STRING`, `NUMBER`, `BOOLEAN`, `DATE_TIME`, `ERROR` |
| `getString`, `getNumber`, `getBoolean`, `getError` | Typed values without allocation (except the `String` of inline text) |
| `getDateTime(column)` | `ExcelDateTime`: `serial()`, `kind()`, `toLocalDateTime()`, `toLocalDate()`, `toLocalTime()`, `toDuration()`, `toObject()` |
| `getLocalDateTime(column)` | Shortcut for `getDateTime(column).toLocalDateTime()` |
| `getValue(column)`, `getObject(column)`, `getText(column)` | Value as `Value`, as a plain Java object, or as text like Excel's General format |
| `sharedStringIndex(column)` | Index in the shared string table, to convert repeated text once |
| `cells()`, `values()`, `toList()`, `copy()` | Cells with values, dense values, dense objects, a detached copy |

- **Values:** text (shared strings, inline strings, cached formula results), numbers, booleans,
  errors and dates. A number whose built-in or custom number format shows a date, time or
  duration becomes `DATE_TIME` with a `DateKind` (`DATE`, `DATE_TIME`, `TIME`, `DURATION`); the
  raw serial stays available. `toObject()` gives a `LocalDateTime`, a `LocalTime` for times below
  one day, or a `Duration`. Both date systems are supported, including Excel's fictitious
  1900-02-29 (serial 60, which converts to null). Times are rounded to the millisecond. Formulas
  yield their cached result; a formula that was never calculated has no value.
- **Memory:** worksheets are decompressed and parsed as a stream; what stays in memory is the
  shared string table (text that a workbook stores once) plus the current row. Reading the 1M-row
  benchmark file completes with `-Xmx16m` when its text is inline and with `-Xmx96m` when its
  1M distinct strings are in the shared table.
- **Compatibility:** files from Excel, LibreOffice, Apache POI, openpyxl and other producers:
  package relationships instead of fixed paths, Strict Open XML, prefixed namespaces, cells and
  rows without references, out-of-order cells, rich text, phonetic runs, inline strings, CDATA,
  ISO 8601 dates (`t="d"`) and Excel's `_xHHHH_` escapes.
- **Safety:** document type declarations are rejected (no entity expansion or external entities),
  every XML token (16 MiB) and row (16,384 cells, 256 MiB of text) is bounded, ZIP entries must
  match their declared size and CRC-32, and `ReadOptions.builder().maxSharedStringsBytes(...)`
  (default 1 GiB) caps the shared string table. A sheet may have at most 4,096 tables holding
  64 MiB of table and column names between them, and a table part that is referenced more than
  once is read once. A part may have at most 65,536 relationships to other parts (external links
  do not count). Legacy `.xls` files, encrypted workbooks, CSV and
  Word files fail with `XlsxException` of kind `INVALID_FILE`, damaged parts with `MALFORMED` and
  a `part()` such as `xl/worksheets/sheet1.xml`; messages name the part and row. Invalid UTF-8 in
  text is replaced with U+FFFD.
- **Threads:** one `XlsxReader` can open several worksheets at once, also from different threads;
  they share the parsed workbook and the shared string table. Each `SheetReader` is used by one
  thread at a time.

Not read (yet): formula text, styles beyond date detection, merged ranges, comments, hyperlinks
and other drawing or table parts.

## Streaming HTTP responses

The core artifact has no web dependencies; a response body is just an `OutputStream`.
`XlsxWriter.CONTENT_TYPE` is the XLSX media type. Output reaches the client in chunks while rows
are written, and closing the writer completes the file.

**Servlet (Jakarta or javax)**

```java
@Override
protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType(XlsxWriter.CONTENT_TYPE);
    response.setHeader("Content-Disposition", "attachment; filename=\"orders.xlsx\"");
    try (XlsxWriter writer = XlsxWriter.create(response.getOutputStream());
            Stream<Order> orders = repository.streamAll()) {
        writer.writeWorksheet("Orders", Order.class, orders);
    }
}
```

**Spring MVC**

```java
@GetMapping("/orders.xlsx")
public ResponseEntity<StreamingResponseBody> orders() {
    StreamingResponseBody body = out -> {
        try (XlsxWriter writer = XlsxWriter.create(out);
                Stream<Order> orders = repository.streamAll()) {
            writer.writeWorksheet("Orders", Order.class, orders);
        }
    };
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(XlsxWriter.CONTENT_TYPE))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"orders.xlsx\"")
            .body(body);
}
```

A `Stream` backed by a database cursor needs an open transaction for as long as it is read; with
Spring Data, read it inside a read-only `TransactionTemplate` in the `StreamingResponseBody`.

**When an export fails half-way.** Closing the writer completes the workbook, also when
try-with-resources closes it because of an exception. The client would then receive a valid
file that is missing rows. Call `abort()` so that the download is truncated and Excel rejects it:

```java
XlsxWriter writer = XlsxWriter.create(response.getOutputStream());
try {
    writer.writeWorksheet("Orders", Order.class, orders);
    writer.close();
} catch (RuntimeException | IOException e) {
    writer.abort();
    throw e;
}
```

When the destination itself fails (the client disconnects), the writer aborts on its own and the
`IOException` propagates.

## Object mapping

Columns are the record components of a record, or the properties of a class: its fields, in
declaration order (superclasses first), that have a public accessor named `getX`, `isX` or `x`,
followed by getters without a field. That covers records, JavaBeans, Kotlin data classes and
Scala case classes.

| Annotation | Purpose |
| --- | --- |
| `@ExcelColumn(name = "Order #")` | Header text; defaults to the property name |
| `@ExcelColumn(order = 0)` | Sort key; columns without one keep declaration order, after ordered ones |
| `@ExcelColumn(format = "#,##0.00")` | Excel number format for the column's cells |
| `@ExcelColumn(width = 30)` | Column width in characters; estimated from header and type when 0 |
| `@ExcelIgnore` | Leaves the property out |

Annotations go on the record component, the field or the getter.

```java
writer.writeWorksheet("Orders", Order.class, orders);       // new sheet: header, frozen row, filter, widths

Worksheet sheet = writer.addWorksheet("Report");
sheet.addRow("Orders of May");
sheet.writeObjects(Order.class, orders,                      // append to a sheet you set up yourself
        ObjectOptions.builder().autoFilter(true).build());
```

Reflection runs once per type; rows are written through cached `MethodHandle`s, and primitive
properties are never boxed. Property types are written like the `write` overloads below; enums are
written by name and any other type through `toString()`. The mapped type must be accessible:
public in an exported package, or in a package opened to the module `de.vensas.hypaxel`. On the
class path every type works.

## Full control with `XlsxWriter`

```java
try (XlsxWriter writer = XlsxWriter.create(Path.of("report.xlsx"))) {
    StyleId header = writer.addStyle(Style.builder()
            .bold().fontColor(Color.WHITE).fill(Color.hex("#305496")).align(HorizontalAlignment.CENTER).build());
    StyleId money = writer.addStyle(Style.builder().numberFormat(NumberFormats.THOUSANDS2).build());

    Worksheet sheet = writer.addWorksheet("Sales",
            SheetOptions.builder().freezeRows(1).columnWidths(20, 12, 14).build());
    sheet.addStyledRow(header, "Product", "Date", "Revenue");
    sheet.write("Widget").write(LocalDate.of(2024, 5, 1)).write(1234.5, money).endRow();
    sheet.write("Gadget").write(LocalDate.of(2024, 5, 2)).write(99.99, money).endRow();
    sheet.write("Total").skipCells(1).writeFormula("SUM(C2:C3)", money).endRow();
}
```

Worksheets are written one at a time: adding a worksheet completes the previous one. Rows go top
to bottom and cells left to right.

**`XlsxWriter`**

| Method | Purpose |
| --- | --- |
| `create(OutputStream \| Path, XlsxOptions?)` | Creates a writer |
| `addStyle(Style)` | Registers a cell format; identical styles share a `StyleId` |
| `addWorksheet(name, SheetOptions?)` | Starts the next worksheet |
| `writeWorksheet(name, type, items)` | Worksheet from objects with header, frozen row, filter and widths |
| `flush()` | Sends buffered output to the destination now |
| `finish()` | Completes the workbook and leaves the destination open |
| `abort()` | Stops without completing the workbook |
| `close()` | Completes the workbook (unless finished or aborted) and closes the destination |

**`Worksheet`**

| Method | Purpose |
| --- | --- |
| `write(value)`, `write(value, style)` | Next cell. Overloads: `String`, `char`, `int`, `long`, `double`, `float`, `boolean`, `BigDecimal`, `LocalDate`, `LocalDateTime`, `LocalTime`, `Instant`, `ZonedDateTime`, `OffsetDateTime`, `java.util.Date`, `Duration` |
| `writeFormula(formula, style?)` | Formula cell; a leading `=` is optional |
| `writeBlank(style)` | Empty cell that carries a format |
| `writeValue(object, style?)` | Next cell by runtime type |
| `endRow()` | Ends the row and hands buffered output to the destination |
| `beginRow(height?, style?)`, `beginRow(RowOptions)`, `beginRowAt(row, ...)` | Starts a row with a height, row style, outline level or hidden state, or at a given row number. A following `addRow` fills it |
| `skipCells(n)`, `skipRows(n)` | Leave cells or rows empty |
| `addRow(values...)`, `addRow(iterable)`, `addStyledRow(style, values...)` | One complete row |
| `addRows(iterable \| stream)` | One row per `Object[]` or `Iterable` element |
| `writeRows(items, (row, item) -> ...)` | One row per item, written with the typed overloads |
| `writeObjects(type, items, ObjectOptions?)` | One row per object, see [Object mapping](#object-mapping) |
| `mergeCells(r1, c1, r2, c2)`, `autoFilter(r1, c1, r2, c2)` | Ranges, 1-based and inclusive |
| `addTable(ExcelTable)` | Excel table over a range, see [Excel tables](#excel-tables) |
| `rowNumber()`, `columnNumber()` | Current position, 1-based |

In rows passed as objects, `Cell.styled(value, style)`, `Cell.formula("SUM(A1:A9)")` and
`Cell.blank(style)` give a single cell its own style or make it a formula.
`CellReference.of(row, column)` and `CellReference.range(...)` build `A1` references for formulas.

**Errors.** Misuse and format limits throw the unchecked `XlsxException`, whose `kind()` tells
them apart (`TEXT_TOO_LONG`, `DUPLICATE_SHEET_NAME`, ...). A rejected cell writes nothing, so the
workbook stays valid. The `write` methods only fill a buffer; the methods that end rows hand output
to the destination and declare `IOException`.

**Styles** are immutable values built with `Style.builder()`: font (`fontName`, `fontSize`,
`bold`, `italic`, `underline`, `strikethrough`, `fontColor`), `fill`, `border` (`Border.all(...)`
or `Border.builder()`, 13 line styles), alignment (`align`, `verticalAlign`, `wrapText`, `indent`,
`rotation`) and `numberFormat`. `NumberFormats` has common format codes.

**Worksheet options** (`SheetOptions.builder()`): `freezeRows`, `freezeColumns`, `columnWidths`,
`column(index, Column)` for width, column style and hidden columns, `hideGridLines`, `hidden`,
`tabColor`, `autoFitColumns`, `rowOutlineLevels`, `outlineSummaryAbove`. They precede the cell data
in the file, so they are fixed when the worksheet is added. Merges, filters and tables can be added
at any time before the worksheet completes.

## Auto-fit columns

Turn on auto-fit and columns without an explicit width are sized to their content:

```java
Worksheet sheet = writer.addWorksheet("Orders", SheetOptions.builder().freezeRows(1).autoFitColumns().build());

// Or tune the sample and cap the width (in characters):
SheetOptions.builder().autoFit(AutoFit.builder().sampleRows(500).maxWidth(60).build()).build();
```

Column widths come before the cell data in the file, so the writer holds back the worksheet's XML
while it measures the first `sampleRows` rows (1,000 by default). It then writes the widths and
streams the rest as usual. Memory is bounded by the sample: at most `sampleRows` rows or 8 MiB of
XML, whichever comes first. Rows after the sample are not measured.

Widths are estimated from the text, the number format (dates, `#,##0.00`, percentages, ...) and
the font size and weight of each cell. Formulas and blank cells are not measured. Widths you set
with `columnWidths` or `column` always win. Without auto-fit, writing a cell costs nothing extra.

## Excel tables

`addTable` turns a range into an Excel table (a `ListObject`), with a header row, filter buttons,
banded rows and a name you can use in formulas such as `=SUM(Orders[Units])`:

```java
Worksheet sheet = writer.addWorksheet("Orders");
sheet.addRow("Product", "Units");
sheet.addRow("Widget", 3);
sheet.addRow("Gadget", 5);

sheet.addTable(ExcelTable.builder(1, 1, sheet.rowNumber(), 2)
        .name("Orders")                  // optional: Table1, Table2, ... otherwise
        .columns("Product", "Units")     // must match the header row
        .style(TableStyle.medium(9))     // light(1-21), medium(1-28), dark(1-11) or NONE
        .build());
```

With a header row (the default), `columns` must list the header texts exactly as written, because
Excel identifies table columns by those names. `headerRow(false)` makes a table without one, whose
columns are named `Column1`, `Column2`, ... unless you name them. Table names are unique in the
workbook, ignoring case, and follow Excel's name rules. Tables cannot overlap each other, merged
cells or the worksheet auto filter; violations throw an `XlsxException`. The range may include rows
you have not written yet.

## Row grouping

Rows can be grouped into collapsible outline levels. Declare the deepest level when you add the
worksheet, because Excel reads it before the rows:

```java
Worksheet sheet = writer.addWorksheet("Regions", SheetOptions.builder()
        .rowOutlineLevels(1)        // deepest level used, 0-7
        .outlineSummaryAbove()      // the group's button sits on the row above it
        .build());

sheet.addRow("North", 300);
sheet.beginRow(RowOptions.builder().outlineLevel(1).build()).addRow("Store A", 120);   // addRow fills the begun row
sheet.beginRow(RowOptions.builder().outlineLevel(1).hidden().build())                  // a collapsed group's rows are hidden
        .write("Store B").write(180).endRow();
```

`RowOptions` also sets `height`, `style` and `collapsed` (on the summary row of a collapsed group).
A row started with `beginRow` that has no cells yet is filled by the next `addRow`, `addRows`,
`writeRows` or `writeObjects`; once it has cells, those start a new row as before.

## Options

`XlsxOptions.builder()`:

| Option | Default | Meaning |
| --- | --- | --- |
| `compressionLevel` | `1` | Deflate level 0-9. Level 6 gives smaller files for more CPU time |
| `stringStorage` | `INLINE` | `SHARED` uses a shared string table: smaller files for repeated text, memory grows with the number of distinct strings |
| `flushThreshold` | `65536` | Compressed bytes buffered before a write to the destination |
| `xmlBufferSize` | `65536` | Uncompressed XML bytes buffered before compression |
| `cellReferences` | `false` | Write a reference such as `B7` on every cell |
| `date1904` | `false` | Use the 1904 date system |
| `zone` | system default | Time zone for `Instant` and `java.util.Date` values |
| `closeOutput` | `true` | Whether closing the writer closes the destination stream |

## Dates and time zones

Excel has no time zones; a cell holds a wall-clock date and time.

- `LocalDate`, `LocalDateTime` and `LocalTime` are written as they are.
- `ZonedDateTime` and `OffsetDateTime` are written with their own local date and time; the zone or
  offset is dropped. 14:00 in New York is written as 14:00.
- `Instant` and `java.util.Date` have no wall-clock time of their own. They are converted in the
  `zone` option, by default the system default zone at the time the writer is created.
- `Duration` is written as a number of days and shown as `[h]:mm:ss`.

Unstyled dates get the viewer's locale date format; a style without a number format keeps its
font, fill and borders and gets that date format too. Dates the workbook's date system cannot
represent (before 1900-01-01, or before 1904-01-01 in the 1904 system, or after 9999-12-31) are
written as ISO-8601 text.

## Kotlin and Scala

The API is plain Java, so it needs no adapters:

```kotlin
data class Order(@field:ExcelColumn(name = "Order #") val id: Int, val customer: String, val amount: BigDecimal)

XlsxWriter.create(out).use { writer ->
    writer.writeWorksheet("Orders", Order::class.java, orders)
}
```

```scala
case class Order(id: Int, customer: String, amount: java.math.BigDecimal)

Using.resource(XlsxWriter.create(out)) { writer =>
  writer.writeWorksheet("Orders", classOf[Order], orders.asJava)
}
```

## Notes and limits

- **ExcelJS as a reader:** by default Hypaxel omits cell references that follow from a cell's
  position. That is valid and is read by Excel, LibreOffice, Apache POI and openpyxl, but ExcelJS
  fails on such files. Set `cellReferences(true)` if your files are read with ExcelJS.
- **Excel limits apply:** 1,048,576 rows, 16,384 columns and 32,767 characters per cell.
  Worksheet names have 1 to 31 characters, none of `: \ / ? * [ ]`, no leading or trailing
  apostrophe, and are unique ignoring case. Violations throw an `XlsxException`.
- **Numbers:** Excel stores every number as a double. `long` values beyond 2^53 and `BigDecimal`
  values with more than 15 significant digits lose precision; write them as text if exactness
  matters. `NaN` and infinities are written as `#NUM!`.
- **Formulas** are stored without cached results; Excel calculates them when the file opens.
- **Text:** characters that XML cannot carry (control characters) are written with Excel's
  `_xHHHH_` escape, and unpaired surrogates are replaced with U+FFFD.
- **Large files:** the ZIP container switches to ZIP64 automatically when a worksheet or the
  file passes 4 GiB.
- **Styles** belong to the writer that created them.
- **Threads:** a writer and its worksheets are not thread-safe. Use one writer per export;
  separate writers are independent.

## Performance

Writing 10 columns per row (int, 2 strings, 2 doubles, `BigDecimal`, `LocalDateTime`, boolean,
long, unique string) to a stream that discards the bytes.

Measured on an Apple M1 Max (10 cores, macOS 26.6) with OpenJDK 24.0.1 against FastExcel 0.20.2
and Apache POI 6.0.0, on an otherwise idle machine.

**100k rows** (JMH 1.37: average time per workbook, 2 forks, 5 warm-up and 5 measured iterations
of 2 s; allocation from the JMH GC profiler)

| Case | Time | Allocated | File |
| --- | ---: | ---: | ---: |
| Hypaxel, typed `write` | 113 ms | 0.17 MB | 3.9 MB |
| Hypaxel, `writeRows` with a `RowWriter` | 113 ms | 0.17 MB | 3.9 MB |
| Hypaxel, object mapping | 118 ms | 0.17 MB | 3.9 MB |
| Hypaxel, `addRow(Object...)` | 117 ms | 14.6 MB | 3.9 MB |
| Hypaxel, shared strings | 127 ms | 3.9 MB | 4.1 MB |
| Hypaxel, compression level 6 | 307 ms | 0.37 MB | 3.0 MB |
| FastExcel, compression level 1 | 288 ms | 260 MB | 6.9 MB |
| FastExcel, default settings | 416 ms | 266 MB | 5.9 MB |
| Apache POI SXSSF | 969 ms | 501 MB | 5.6 MB |

**1M rows** (stopwatch runner: mean of 5 runs after 2 warm-ups, bytes allocated by the writing
thread per run. Peak RSS is from a separate single run in a fresh JVM with default settings that
creates the rows on demand; creating the rows without writing anything peaks at 315 MB.)

| Case | Time | Allocated | Peak RSS | File |
| --- | ---: | ---: | ---: | ---: |
| Hypaxel, typed `write` | 1.14 s | 0.16 MB | 326 MB | 38.8 MB |
| Hypaxel, `writeRows` with a `RowWriter` | 1.13 s | 0.16 MB | 333 MB | 38.8 MB |
| Hypaxel, object mapping | 1.18 s | 0.17 MB | 327 MB | 38.8 MB |
| Hypaxel, `addRow(Object...)` | 1.18 s | 137 MB | 467 MB | 38.8 MB |
| Hypaxel, shared strings | 1.29 s | 30 MB | 367 MB | 41.0 MB |
| Hypaxel, compression level 6 | 3.09 s | 0.35 MB | 324 MB | 29.9 MB |
| FastExcel, compression level 1 | 2.84 s | 2,503 MB | 1,073 MB | 69.3 MB |
| FastExcel, default settings | 4.11 s | 2,487 MB | 1,066 MB | 60.7 MB |
| Apache POI SXSSF | 9.50 s | 4,648 MB | 865 MB | 56.9 MB |

How to read this:

- Hypaxel's default is deflate level 1. At the same level it is about 2.5x faster than
  FastExcel. FastExcel's own default is a higher level, which makes its files smaller than at
  level 1 but still larger than Hypaxel's.
- At level 6 Hypaxel is slower than FastExcel at level 1 (3.09 s against 2.84 s at 1M rows) and
  produces a file less than half the size. About 70% of Hypaxel's time at level 1 is spent in
  the JDK's `Deflater`, so the compression level decides most of the run time.
- Peak RSS is dominated by the JVM and by how much garbage accumulates between collections, not
  by what a writer keeps alive. All three writers stream; FastExcel was flushed every 100 rows
  and POI used a 100-row window.
- Allocation does not grow with the row count for the typed, `RowWriter` and object-mapping
  paths. `addRow(Object...)` boxes its arguments, and shared strings keep every distinct string.
- FastExcel needs a style per date cell to show dates as dates; that call is part of its loop.

Run them yourself:

```bash
cd java
./mvnw -q -DskipTests install                       # the benchmarks use the installed library
./mvnw -q -f benchmarks/pom.xml package
java -jar benchmarks/target/benchmarks.jar jmh WriteBenchmark -prof gc    # 100k rows, JMH
./benchmarks/run.sh 1000000                         # 1M rows: stopwatch and peak memory
```

The benchmarks are a separate Maven project in `benchmarks/`, so JMH, FastExcel and POI are not
dependencies of the library. The read benchmark is described [below](#reading-1).

### Reading

The 1M-row dataset of the write benchmark (10 columns: int, two strings, two doubles, a decimal,
a date-time, a boolean, a long and a unique string), written by Hypaxel once with inline strings
(40.7 MB) and once with a shared string table (43.0 MB, 1M distinct strings). Each reader streams
the first worksheet and visits every cell with its typed accessors; all of them must produce the
same checksum (10,000,000 cells, sum of all numbers and date serials, 42,833,558 characters of
text, 500,000 `TRUE`s).

Measured on an Apple M1 Max (10 cores, 64 GB, macOS 26.6) with OpenJDK 24.0.1 against
FastExcel reader 0.20.2 (`ReadableWorkbook`, Aalto StAX) and Apache POI 6.0.0 (`XSSFReader` with
a SAX handler and `ReadOnlySharedStringsTable`, the event API as POI documents it), on an
otherwise idle machine. Time: mean of 5 runs after 2 warm-ups in one JVM. Allocated: bytes allocated by the
reading thread per run. Peak RSS: a separate single read in a fresh JVM with default settings.

| Reader | Inline strings | Allocated | Peak RSS | Shared strings | Allocated | Peak RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **Hypaxel** `XlsxReader` | **0.69 s** | **322 MB** | **354 MB** | **0.68 s** | **74 MB** | **157 MB** |
| FastExcel `ReadableWorkbook` | 2.70 s | 3,907 MB | 733 MB | 2.87 s | 4,074 MB | 1,013 MB |
| Apache POI `XSSFReader` + SAX | 3.22 s | 1,267 MB | 627 MB | 4.59 s | 7,941 MB | 826 MB |

How to read this:

- All three readers stream with bounded live memory: each one completes with `-Xmx16m` on the
  inline file and with `-Xmx96m` on the shared one, whose string table must be held. The RSS
  differences come from garbage: under default settings the JVM lets it accumulate between
  collections, so the reader that allocates least also has the smallest footprint.
- Hypaxel's allocation on the inline file is the `String` of each text cell, which the benchmark
  asks for; shared strings are created once when the table is loaded. Numbers, booleans and
  dates allocate nothing.
- FastExcel did not read number formats (its default); Hypaxel and the POI handler detect date
  formats.
- About 25% of Hypaxel's time is inflate and CRC-32 verification in the JDK.

```bash
./mvnw -q -DskipTests install
./mvnw -q -f benchmarks/pom.xml package
./benchmarks/read.sh 1000000                        # generates the files, then time and peak memory
```

### Why reading is fast

- **Own XML pull parser over UTF-8 bytes.** No StAX or SAX, no `char[]` decoding of markup;
  names are compared as bytes and namespace prefixes are ignored by matching local names.
- **One-pass fast path for common cells.** `<c r="B2" s="1" t="s"><v>42</v></c>` and
  `<c t="inlineStr"><is><t>text</t></is></c>` are recognized in a single scan; anything else
  (formulas, rich text, comments, unusual whitespace) falls back to the general parser, so both
  give the same result.
- **No objects per cell.** Rows are parallel primitive arrays reused from row to row; text stays
  UTF-8 until you ask for a `String`, and shared strings are created once.
- **Correctly rounded number parsing without `Double.parseDouble`.** Clinger's fast path for up
  to 15 digits, then the Eisel-Lemire algorithm for up to 19 digits; the JDK only handles the
  rare rest. Results are bit-identical to `Double.parseDouble`.
- **Own ZIP reader.** Positional reads and `java.util.zip.Inflater` directly into the parser's
  buffer, which also lets several worksheets be read at once.

### Why writing is fast

- **No XML library.** Markup is appended as precomputed ASCII byte arrays to one reusable buffer;
  text is transcoded from UTF-16 to UTF-8 and escaped in a single pass.
- **No objects per cell.** There are no cell, row or string objects, and no boxing in the typed
  overloads. Integers and doubles are formatted straight into the buffer; doubles use the
  Schubfach shortest-representation algorithm instead of `Double.toString`. Date serials use
  integer arithmetic only.
- **Compact XML.** Cell references (`r="B7"`) are only written after a gap. The format allows
  this, and it removes about 20% of the XML that has to be deflated.
- **Own ZIP container.** Entries are deflated directly into the output buffer with data
  descriptors, so nothing is buffered per entry and the destination never has to seek.

## Development

Requires JDK 17 or later. Maven comes from the wrapper.

```bash
cd java
./mvnw -B verify      # compile for Java 17, run the tests, check 80% line coverage
```

The writer tests read every workbook back with Apache POI; the reader tests round-trip through
the writer, read files written by POI, handcrafted packages and thousands of damaged files. The coverage report is written to
`target/site/jacoco/index.html`.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2026 vensas GmbH.
