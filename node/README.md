# Hypaxel for Node.js and browsers

**High-performance, constant-memory XLSX (Excel) writer and streaming reader for Node.js and browsers, powered by Rust.**

Hypaxel writes `.xlsx` files as a forward-only stream. Rows are serialized and compressed in Rust
as you write them, so memory stays flat whether you export 10 rows or a million, and a download
can start while your query is still running. Node.js runs the native module; browsers run the
same engine compiled to WebAssembly, with the same API.

- **Fast:** writes 100k rows × 10 columns in about 0.13 s, about **6x faster than ExcelJS's
  streaming writer** and **30x faster than an in-memory ExcelJS workbook** (see [Performance](#performance)).
- **Streaming:** a Node `Readable` or a web `ReadableStream` that plugs into Express, Fastify, Koa,
  Next.js and Hono. Backpressure from the client pauses your data source.
- **Data you already have:** arrays, plain objects, sync and async iterables such as database cursors.
- **Real Excel types:** `number`, `string`, `boolean`, `Date`, `bigint`, formulas. `null`,
  `undefined` and invalid dates become empty cells.
- **Styles and layout:** fonts, fills, borders, alignment, number formats, merged cells, frozen
  panes, auto filters, Excel tables, row grouping, auto-fit columns, hidden sheets, tab colors.
- **Browsers too:** one package, picked by your bundler: native in Node.js, WebAssembly (171 KB
  gzipped) in the browser. Generates a 100k-row file in about 0.15 s in Chrome, about **11x faster
  than SheetJS and 19x faster than ExcelJS** there (see [Browsers](#browsers)).
- **Reading too:** streams rows from uploads and files about **8x faster than ExcelJS's streaming
  reader** and 13x faster than SheetJS, with memory bounded by the shared strings plus one batch
  of rows. In Node.js, `for await` parses on the thread pool and leaves the event loop free
  (see [Reading](#reading)).
- **Typed:** written in TypeScript (strict), ships its own type declarations, works from ESM and CommonJS.

Hypaxel is built on the [`hypaxel` Rust crate](../rust/README.md) through [napi-rs](https://napi.rs)
(Node.js) and [wasm-bindgen](https://github.com/wasm-bindgen/wasm-bindgen) (WebAssembly).
The [.NET](../dotnet/README.md) and [Python](../python/README.md) versions use the same design.

## Install

```bash
npm install hypaxel        # not published yet: build from source, see "Development"
```

Requires Node.js 22 or later, or a browser with WebAssembly (every current browser).

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

## Browsers

The same `import ... from 'hypaxel'` resolves to the WebAssembly build in browser bundles (Vite,
webpack, esbuild, Rollup and others honor the `browser` export condition). Node.js, Deno and Bun
resolve the `node` condition and load the native module. Any other runtime gets the WebAssembly
build through the `default` condition; `import ... from 'hypaxel/wasm'` selects it explicitly
everywhere.

```ts
import { toBlob } from 'hypaxel'

const blob = await toBlob(rows, { columns: ['id', { key: 'amount', numFormat: '#,##0.00' }] })
const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: 'orders.xlsx' })
link.click()
URL.revokeObjectURL(link.href)
```

The WebAssembly module (`hypaxel_bg.wasm`) is loaded on first use:

- `toBlob`, `toBuffer` and `webStream` load it themselves.
- `new Writer()` and `toBufferSync` are synchronous, so call `await init()` once before them.
  Without it they throw an `XlsxError` that says so.
- By default the module is fetched from next to the JavaScript file, which bundlers resolve and copy.
  To serve it from elsewhere (a CDN, a strict Content Security Policy), pass its URL or bytes:
  `await init('/assets/hypaxel_bg.wasm')`. The file is exported as `hypaxel/hypaxel_bg.wasm`.
- In workers and server runtimes that read the file from disk, `initSync(bytes)` loads it
  synchronously. Runtimes that import `.wasm` files as compiled modules (Cloudflare Workers, for
  example) can pass the module: `import wasm from 'hypaxel/hypaxel_bg.wasm'; await init(wasm)`.
  Only Chrome and Node.js are tested so far.

`init()` also exists in Node.js, where it resolves immediately, so shared code can call it
unconditionally.

| API | Node.js | Browsers and WebAssembly runtimes |
| --- | --- | --- |
| `Writer`, `Worksheet`, styles, tables, ... | ✓ | ✓ (after `init()`) |
| `drain()`, `finish()`, `toBuffer`, `toBufferSync` | `Buffer` | `Uint8Array` |
| `webStream` | ✓ | ✓ (pull-based: rows are produced as the consumer reads) |
| `toBlob` | | ✓ |
| `stream` (Node `Readable`), `writeFile` | ✓ | |
| `init`, `initSync` | `init` (no-op) | ✓ |
| `openWorkbook`, `readRows`, `readRecords`, `iterRows`, `iterRecords` | paths, bytes, streams | bytes, `Blob`/`File`, `Response`, streams |
| `openWorkbookSync` | path or bytes | bytes (after `init()`) |

Large exports in the browser: `webStream` keeps memory bounded when its consumer writes to disk,
e.g. through the File System Access API (`showSaveFilePicker()` and `pipeTo`). `toBlob` holds the
finished file in memory, compressed.

## Reading

```ts
import { openWorkbook, readRecords, readRows } from 'hypaxel'

const rows = await readRows('orders.xlsx', 'Orders')       // sheet name or 0-based index
// [['Product', 'Qty', 'Price', 'Ordered'], ['Widget', 3, 9.99, Date], ...]

const records = await readRecords(upload)                  // [{ Product: 'Widget', Qty: 3, ... }]

using workbook = await openWorkbook(request)               // path, bytes, Blob, stream, ...
console.log(workbook.sheetNames, workbook.sheets, workbook.date1904)
for await (const row of workbook.rows('Orders', { skipEmpty: true })) {
  // ...
}
```

- **Rows** are arrays: `row[i]` is column `i + 1`, and a row ends at its last value. Gaps are
  `null`. Missing rows come back as `[]`, so the row at index `i` is Excel row `i + 1` (pass
  `skipEmpty: true` to drop them).
- **Values** are `string`, `number`, `boolean`, `Date` and `null`. Cells formatted as a date, date
  and time or time of day become a `Date` (a time of day falls on Excel's day zero, 1899-12-31 or
  1904-01-01 in the 1904 date system, so read it with `getHours()` and friends). `[h]:mm` durations become a number of elapsed
  milliseconds. Errors such as `#N/A` are strings. Formulas yield their cached result. Dates are
  recognised from the cell's number format, in both date systems, and rounded to the millisecond.
- **Time zones:** like the writer, `dates: 'local'` (default) returns the cell's wall-clock time in
  the local time zone and `dates: 'utc'` in UTC, so a `Date` written and read with the same
  setting comes back unchanged.
- **Records:** `readRecords` / `iterRecords` / `workbook.records()` key each row by the header row
  (`headerRow: 1`). Empty header cells become `column_<n>`, repeated names get `_2`, `_3`, …, and
  rows without values are skipped. Missing cells are `null`.
- **Sources:** a path or `file:` URL is read from disk as rows are requested. Anything else (a
  `Buffer` or `Uint8Array`, an `ArrayBuffer`, a `Blob` or `File`, a `Response`, a web
  `ReadableStream`, a Node.js `Readable` such as an HTTP request) is held in memory once, because
  the XLSX directory sits at the end of the file.
- **Sync or async:** `workbook.rows()` iterates with `for...of` or `for await...of`. In Node.js the
  async form parses each batch on the libuv thread pool, so a large upload does not block other
  requests; the sync form is slightly faster. `openWorkbookSync` opens a path or bytes without a
  promise. In browsers both forms run on the calling thread; use a worker for very large files.
- **Memory:** beyond the source, memory is bounded by the workbook's shared string table and one
  batch of rows (about 64 KB). `maxSharedStringsBytes` (default 1 GiB) caps the table for untrusted
  uploads. Several sheets, or the same sheet twice, can be read at the same time.
- **Closing:** `workbook.close()` (or `using`) releases the file; rows already started keep
  reading. Breaking out of a loop releases that sheet. `iterRows` and the other helpers close
  everything themselves.
- **Errors:** everything throws an `XlsxError` whose message names the problem: a missing file, a
  legacy `.xls` or encrypted workbook, a sheet that does not exist (with the sheets that do), a
  chart sheet, a damaged part or an exceeded limit.

```ts
// Express: the request body is the .xlsx file itself (not a multipart form).
app.post('/import', async (req, res) => {
  let imported = 0
  for await (const record of iterRecords(req, 'Orders')) {
    await saveOrder(record)
    imported++
  }
  res.json({ imported })
})
```

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
| `beginRow({ height, style, outlineLevel, hidden, collapsed })` | Start a row with row properties; the next `writeRow` fills it |
| `skipCells(n)`, `skipRows(n)` | Leave cells or rows empty |
| `mergeCells(r1, c1, r2, c2)`, `autofilter(r1, c1, r2, c2)` | Ranges, 1-based and inclusive |
| `addTable(r1, c1, r2, c2, { columns, name, style, ... })` | Excel table; `columns` must equal the header texts |

```js
const sheet = writer.addWorksheet('Regions', { autofit: true, rowOutlineLevels: 1, outlineSummaryAbove: true })
sheet.writeRow(['Region', 'Revenue'])
sheet.writeRow(['North', 300])
sheet.beginRow({ outlineLevel: 1 })
sheet.writeRow(['Store A', 120])
sheet.addTable(1, 1, sheet.row, 2, { name: 'Regions', columns: ['Region', 'Revenue'], style: 'TableStyleMedium9' })
```

`autofit` (or `{ sampleRows, maxWidth }`) holds back the sheet's output until the sample (default
1,000 rows, at most 8 MiB of XML) has been measured, then streams as usual. `rowOutlineLevels`
declares the deepest grouping level up front, because Excel reads it before the rows.

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
  the engine in batches, so there is no native or WebAssembly call per cell.
- **Identical output:** the native and WebAssembly engines are the same Rust code and produce
  byte-identical files; the test suite checks this.
- **WebAssembly baseline:** the module uses the extensions Rust enables by default (reference
  types, bulk memory, multi-value, sign extension, non-trapping float-to-int), so it needs
  Chrome/Edge 96+, Firefox 79+ or Safari 15+ (all from 2021). It does not use SIMD, which would
  raise the Safari minimum to 16.4 for about 8% more speed.

## Performance

Writing 10 columns per row: five numbers, three strings (one unique per row), a `Date` and a boolean. Each case
runs in its own process; "peak RSS" is that process's peak resident memory, which includes Node.js
itself and both libraries: the smallest case peaks at 84 MB, nearly all of it Node.js.

Measured on an Apple M1 Max (macOS 26.6) with Node.js 22.23.2 against ExcelJS 4.4.0.

| Case | 100k rows | Peak RSS | 1M rows | Peak RSS |
| --- | ---: | ---: | ---: | ---: |
| Hypaxel, arrays, streamed | 130 ms | 84 MB | 1.19 s | 101 MB |
| Hypaxel, arrays, to buffer | 124 ms | 94 MB | 1.19 s | 202 MB |
| Hypaxel, objects, to buffer | 140 ms | 96 MB | 1.30 s | 203 MB |
| Hypaxel, WebAssembly engine, arrays, to buffer | 202 ms | 108 MB | 1.82 s | 271 MB |
| ExcelJS, streaming writer | 811 ms | 170 MB | 7.63 s | 604 MB |
| ExcelJS, in-memory workbook | 3,830 ms | 1,300 MB | not run | not run |

These are single runs, so treat small differences as noise. The 1M-row file is 50 MB from Hypaxel
and 67 MB from ExcelJS's streaming writer. The WebAssembly row runs the browser build in Node.js;
it is about 1.5x slower than the native module, which is why Node.js uses the native one.

Run them yourself:

```bash
node benchmarks/bench.mjs            # 100,000 rows
node benchmarks/bench.mjs 1000000
```

### Reading

The write benchmark's 10 columns plus a header row, written by Hypaxel with a shared string table
and a reference on every cell, as Excel saves them (ExcelJS needs the references). The files are
9.5 MB (100k rows) and 94 MB (1M rows). Each case runs in its own process and reports the
non-empty cells it read; every library returns the same 1,000,010 and 10,000,010 cells. Peak RSS
includes Node.js itself, about 81 MB here.

Measured on an Apple M1 Max (macOS 26.6) with Node.js 22.23.2 against ExcelJS 4.4.0 and SheetJS 0.20.3.

| Case | 100k rows | Peak RSS | 1M rows | Peak RSS |
| --- | ---: | ---: | ---: | ---: |
| Hypaxel, rows, sync | 163 ms | 108 MB | 1.57 s | 148 MB |
| Hypaxel, rows, for await (thread pool) | 168 ms | 107 MB | 1.60 s | 144 MB |
| Hypaxel, WebAssembly engine, rows | 244 ms | 141 MB | 2.25 s | 355 MB |
| ExcelJS, streaming reader | 1,297 ms | 275 MB | 13.05 s | 595 MB |
| SheetJS, read and sheet_to_json | 2,160 ms | 440 MB | not run | not run |

These are single runs. SheetJS reads the whole file into memory and is not run at 1M rows. With
inline strings instead of a shared string table, Hypaxel's peak RSS stays at 88 MB at both sizes
(1.68 s for 1M rows), and ExcelJS takes 12.92 s. The WebAssembly row runs the browser build in
Node.js; it holds the file in WebAssembly memory as well as in a `Buffer`.

```bash
node benchmarks/read_bench.mjs                    # 100,000 rows, shared strings
node benchmarks/read_bench.mjs 1000000 --inline   # 1,000,000 rows, inline strings
```

### Browsers

The same 10 columns, turned into an XLSX `Blob` in headless Chrome. The rows are built before
timing; every library gets the same array. Each library runs in a fresh browser, and the time is
the median of three runs after the library has loaded. SheetJS writes with `compression: true`.

Measured on an Apple M1 Max (macOS 26.6) with Chrome 158.0.8127.36, SheetJS 0.20.3 and ExcelJS 4.4.0.

| Library | 100k rows | File | 300k rows | File |
| --- | ---: | ---: | ---: | ---: |
| Hypaxel (WebAssembly) | 145 ms | 5.0 MB | 455 ms | 15.2 MB |
| SheetJS | 1,653 ms | 15.1 MB | 6,502 ms | 45.5 MB |
| ExcelJS | 2,816 ms | 5.8 MB | 8,775 ms | 17.5 MB |

Hypaxel's WebAssembly memory stayed at 2.4 MB in both runs. The browser does not expose the peak
JavaScript heap, so memory is not compared across libraries here. Download sizes, gzipped: Hypaxel
171 KB of WebAssembly plus about 7 KB of minified JavaScript; SheetJS `xlsx.full.min.js` 335 KB
(`xlsx.mini.min.js` 86 KB); ExcelJS `exceljs.min.js` 256 KB.

```bash
npm run bench:browser                        # 100,000 rows; needs Chrome (or set CHROME_PATH)
node benchmarks/browser/run.mjs 300000
```

## Development

Requires Node.js 22+, a Rust toolchain (1.88+) with the `wasm32-unknown-unknown` target, and
`wasm-bindgen-cli` matching the `wasm-bindgen` version in `wasm/Cargo.toml`.

```bash
rustup target add wasm32-unknown-unknown
cargo install wasm-bindgen-cli --version 0.2.130 --locked

cd node
npm install
npm run build          # native addon (napi-rs), WebAssembly module (wasm-bindgen) and TypeScript
npm test               # both engines, in Node.js
npm run test:browser   # the WebAssembly build in headless Chrome
```

Layout: `engine/` holds the Rust code both bindings share (options, cell-batch decoding for
writing, row-batch encoding for reading); `src/` adapts it to N-API and `wasm/` to WebAssembly.
`ts/core.ts` (writing) and `ts/read.ts` (reading) are the platform-neutral API, `ts/index.ts` the
Node.js entry and `ts/browser.ts` the browser entry.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2026 vensas GmbH.
