// Read benchmark against ExcelJS and SheetJS. Each case runs in its own process so peak memory is
// per case. The file holds the write benchmark's 10 columns plus a header row and, like files saved
// by Excel, cell references and a shared string table (pass --inline for inline strings). Every
// case reports the number of non-empty cells it read, so the readers can be checked against each
// other.
//
//   node benchmarks/read_bench.mjs                    # 100,000 rows
//   node benchmarks/read_bench.mjs 1000000 --inline   # 1,000,000 rows, inline strings (skips SheetJS)

import { execFileSync } from 'node:child_process'
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import ExcelJS from 'exceljs'
import * as XLSX from 'xlsx'

import { Writer, openWorkbookSync } from '../lib/index.js'
import * as wasm from '../lib/browser/browser.js'

const CATEGORIES = ['Hardware', 'Software', 'Services', 'Consulting', 'Support', 'Training']
const HEADER = ['id', 'name', 'category', 'price', 'amount', 'created', 'active', 'quantity', 'comment', 'ratio']
const START = Date.UTC(2024, 0, 1)

async function generate(path, count, sharedStrings) {
  const { writeFile } = await import('node:fs/promises')
  const writer = new Writer({ sharedStrings, dates: 'utc', cellReferences: true })
  const sheet = writer.addWorksheet('Data')
  sheet.writeRow(HEADER)
  for (let i = 0; i < count; i++) {
    sheet.writeRow([
      i,
      `Customer ${i % 5000}`,
      CATEGORIES[i % CATEGORIES.length],
      i * 1.25,
      i * 0.01,
      new Date(START + i * 60_000),
      i % 2 === 0,
      i * 3,
      `Unique comment #${i}`,
      i / 7,
    ])
  }
  await writeFile(path, writer.finish())
}

function countRow(row) {
  let cells = 0
  for (const value of row) if (value !== null && value !== undefined) cells++
  return cells
}

const CASES = {
  'hypaxel, rows (sync)': async (path) => {
    let cells = 0
    for (const row of openWorkbookSync(path).rows()) cells += countRow(row)
    return cells
  },
  'hypaxel, rows (for await, thread pool)': async (path) => {
    let cells = 0
    for await (const row of openWorkbookSync(path).rows()) cells += countRow(row)
    return cells
  },
  'hypaxel (WebAssembly), rows': async (path) => {
    wasm.initSync(await readFile(new URL('../lib/browser/wasm/hypaxel_bg.wasm', import.meta.url)))
    let cells = 0
    for (const row of wasm.openWorkbookSync(await readFile(path)).rows()) cells += countRow(row)
    return cells
  },
  'ExcelJS, streaming reader': async (path) => {
    let cells = 0
    const reader = new ExcelJS.stream.xlsx.WorkbookReader(path, { sharedStrings: 'cache', styles: 'cache', hyperlinks: 'ignore', worksheets: 'emit' })
    for await (const sheet of reader) {
      for await (const row of sheet) cells += countRow(row.values)
    }
    return cells
  },
  'SheetJS, read and sheet_to_json': async (path) => {
    XLSX.set_fs(await import('node:fs'))
    const book = XLSX.readFile(path, { cellDates: true, dense: true })
    let cells = 0
    for (const row of XLSX.utils.sheet_to_json(book.Sheets[book.SheetNames[0]], { header: 1 })) cells += countRow(row)
    return cells
  },
}

const IN_MEMORY_LIMIT = 200_000

if (process.argv[2] === '--case') {
  const [name, path] = [process.argv[3], process.argv[4]]
  const started = performance.now()
  const cells = await CASES[name](path)
  const elapsed = performance.now() - started
  console.log(JSON.stringify({ ms: elapsed, cells, rss: process.resourceUsage().maxRSS }))
} else {
  const count = Number(process.argv.slice(2).find((arg) => !arg.startsWith('--')) ?? 100_000)
  if (!Number.isSafeInteger(count) || count < 0) throw new Error(`usage: node benchmarks/read_bench.mjs [rows] [--inline]`)
  const sharedStrings = !process.argv.includes('--inline')
  const directory = await mkdtemp(join(tmpdir(), 'hypaxel-read-bench-'))
  try {
    const path = join(directory, 'read.xlsx')
    await generate(path, count, sharedStrings)
    const size = (await stat(path)).size
    console.log(
      `${count.toLocaleString('en-US')} rows x 10 columns, ${sharedStrings ? 'shared strings' : 'inline strings'}, ` +
        `${(size / 1024 / 1024).toFixed(1)} MB, Node ${process.version}, ${process.platform} ${process.arch}`,
    )
    for (const name of Object.keys(CASES)) {
      if (name.startsWith('SheetJS') && count > IN_MEMORY_LIMIT) continue
      const output = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--case', name, path], { encoding: 'utf8' })
      const { ms, cells, rss } = JSON.parse(output)
      const megabytes = (value) => (value / 1024 / 1024).toFixed(1).padStart(8)
      console.log(`${name.padEnd(40)} ${ms.toFixed(0).padStart(8)} ms  ${megabytes(rss * 1024)} MB peak RSS  ${cells} cells`)
    }
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}
