# Hypaxel for Node.js

**High-performance, constant-memory XLSX (Excel) writer for Node.js, powered by Rust.**

Hypaxel writes `.xlsx` files as a forward-only stream. Rows are serialized and compressed in native
code as you write them, so memory stays flat whether you export 10 rows or a million, and a download
can start while your query is still running.

- **Fast:** writes 100k rows × 10 columns in about 0.13 s, about **6x faster than ExcelJS's
  streaming writer** and **30x faster than an in-memory ExcelJS workbook** (see [Performance](#performance)).
- **Streaming:** a Node `Readable` or a web `ReadableStream` that plugs into Express, Fastify, Koa,
  Next.js and Hono. Backpressure from the client pauses your data source.
- **Data you already have:** arrays, plain objects, sync and async iterables such as database cursors.
- **Real Excel types:** `number`, `string`, `boolean`, `Date`, `bigint`, formulas. `null`,
  `undefined` and invalid dates become empty cells.
- **Styles and layout:** fonts, fills, borders, alignment, number formats, merged cells, frozen
  panes, auto filters, hidden sheets, tab colors.
- **Typed:** written in TypeScript (strict), ships its own type declarations, works from ESM and CommonJS.

Hypaxel is built on the [`hypaxel` Rust crate](../rust/README.md) through [napi-rs](https://napi.rs).
The [.NET](../dotnet/README.md) and [Python](../python/README.md) versions use the same design.

## Install

```bash
npm install hypaxel        # not published yet: build from source, see "Development"
```

Requires Node.js 22 or later.

## Quick start

```ts
import { toBuffer, writeFile } from 'hypaxel'

const orders = [
  { id: 1, customer: 'Ada', amount: 1234.5, created: new Date() },
  { id: 2, customer: 'Linus', amount: 99.99, created: new Date() },
]

await writeFile('orders.xlsx', orders)

const buffer = await toBuffer(orders, {
  sheetName: 'Orders',
  columns: ['id', { key: 'customer', header: 'Customer', width: 30 }, { key: 'amount', numFormat: '#,##0.00' }],
})
```

Objects get a styled, frozen header row and an auto filter. `columns` selects and orders the
properties; without it the keys of the first object are used. Arrays are written as plain rows:

```ts
await writeFile('matrix.xlsx', [[1, 'a'], [2, 'b']], { columns: ['Id', 'Label'] })
```

## Streaming HTTP responses

`stream()` consumes its source lazily and yields the file in chunks.

**Express**

```ts
import { CONTENT_TYPE, stream } from 'hypaxel'
import { pipeline } from 'node:stream/promises'

app.get('/orders.xlsx', async (req, res) => {
  res.setHeader('Content-Type', CONTENT_TYPE)
  res.setHeader('Content-Disposition', 'attachment; filename="orders.xlsx"')
  await pipeline(stream(db.orders.cursor()), res)
})
```

**Fastify**

```ts
fastify.get('/orders.xlsx', (request, reply) =>
  reply.header('Content-Type', CONTENT_TYPE).send(stream(db.orders.cursor())))
```

**Next.js route handlers, Hono and other `Response`-based frameworks**

```ts
import { CONTENT_TYPE, webStream } from 'hypaxel'

export function GET() {
  return new Response(webStream(fetchOrders()), { headers: { 'Content-Type': CONTENT_TYPE } })
}
```

The source can be any iterable or async iterable: an array, a generator, a database cursor, a
`Readable` in object mode.

## Full control with `Writer`

```ts
import { Formula, Styled, Writer } from 'hypaxel'
import { writeFileSync } from 'node:fs'

const workbook = new Writer()
const header = workbook.addStyle({ bold: true, fontColor: '#FFFFFF', fill: '#305496', align: 'center' })
const money = workbook.addStyle({ numFormat: '#,##0.00' })

const sheet = workbook.addWorksheet('Sales', { freezeRows: 1, columnWidths: [20, 12, 14] })
sheet.writeRow(['Product', 'Date', 'Revenue'], header)
sheet.writeRows([
  ['Widget', new Date(2024, 4, 1), new Styled(1234.5, money)],
  ['Gadget', new Date(2024, 4, 2), new Styled(99.99, money)],
])
sheet.writeRow(['Total', null, new Styled(new Formula('SUM(C2:C3)'), money)])

writeFileSync('report.xlsx', workbook.finish())
```

A `Writer` keeps its output in memory. `finish()` returns what is left; to keep memory flat while
writing a large workbook yourself, take the bytes produced so far with `drain()` between batches
of rows and send them on.

Worksheets are written one at a time: adding a worksheet completes the previous one.

| Method | Purpose |
| --- | --- |
| `writeRow(values, style?)` | One row. `style` applies to cells without their own |
| `writeRows(rows, style?)` | Many rows; returns the count |
| `writeRecords(records, keys, styles?)` | One row per object, taking `keys` in order |
| `write(value, style?)`, `endRow()` | Cell-by-cell writing |
| `beginRow({ height, style })` | Start a row with a height or row style |
| `skipCells(n)`, `skipRows(n)` | Leave cells or rows empty |
| `mergeCells(r1, c1, r2, c2)`, `autofilter(r1, c1, r2, c2)` | Ranges, 1-based and inclusive |

## Options

| Option | Default | Meaning |
| --- | --- | --- |
| `compressionLevel` | `1` | Deflate level 0-9 |
| `sharedStrings` | `false` | Shared string table: smaller files for repeated text, more memory |
| `flushThreshold` | `65536` | Compressed bytes buffered before a chunk is produced |
| `date1904` | `false` | Use the 1904 date system |
| `dates` | `'local'` | Excel has no time zones. A `Date` is written as wall-clock time in the process time zone (`'local'`) or in UTC (`'utc'`) |
| `cellReferences` | `false` | Write a reference such as `B7` on every cell |

Export helpers (`stream`, `webStream`, `toBuffer`, `toBufferSync`, `writeFile`) also take
`sheetName`, `columns`, `header`, `autofilter` and `batchRows`.

## Notes and limits

- **ExcelJS as a reader:** by default Hypaxel omits cell references that follow from a cell's
  position. That is valid and is read by Excel, LibreOffice, openpyxl and calamine, but ExcelJS
  fails on such files. Pass `cellReferences: true` if your files are read with ExcelJS.
- **Excel limits apply:** 1,048,576 rows, 16,384 columns and 32,767 characters per cell. Exceeding
  them throws an `XlsxError`.
- **Numbers:** `bigint` values are written as doubles, like every Excel number. `NaN` and
  infinities are written as `#NUM!`.
- **Styles** belong to the writer that created them.
- **One call per batch:** cells are encoded into a compact buffer in JavaScript and handed to
  native code in batches, so there is no native call per cell.

## Performance

Writing 10 columns per row: five numbers, three strings (one unique per row), a `Date` and a boolean. Each case
runs in its own process; "peak RSS" is that process's peak resident memory, which includes Node.js
itself and both libraries (about 85 MB before any row is written).

Measured on an Apple M1 Max (macOS 26.6) with Node.js 22.23.2 against ExcelJS 4.4.0.

| Case | 100k rows | Peak RSS | 1M rows | Peak RSS |
| --- | ---: | ---: | ---: | ---: |
| Hypaxel, arrays, streamed | 133 ms | 87 MB | 1.23 s | 104 MB |
| Hypaxel, arrays, to buffer | 131 ms | 97 MB | 1.23 s | 204 MB |
| Hypaxel, objects, to buffer | 151 ms | 99 MB | 1.36 s | 207 MB |
| ExcelJS, streaming writer | 815 ms | 169 MB | 7.89 s | 612 MB |
| ExcelJS, in-memory workbook | 4,186 ms | 1,301 MB | not run | not run |

These are single runs, so treat small differences as noise. The 1M-row file is 50 MB from Hypaxel
and 67 MB from ExcelJS's streaming writer.

Run them yourself:

```bash
node benchmarks/bench.mjs            # 100,000 rows
node benchmarks/bench.mjs 1000000
```

## Development

Requires Node.js 22+ and a Rust toolchain (1.88+).

```bash
cd node
npm install
npm run build      # native addon (napi-rs) and TypeScript
npm test
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2026 vensas GmbH.
