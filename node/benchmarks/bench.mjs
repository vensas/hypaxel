// Compares hypaxel with ExcelJS. Each case runs in its own process so peak memory is per case.
//
//   node benchmarks/bench.mjs            # 100,000 rows
//   node benchmarks/bench.mjs 1000000    # 1,000,000 rows (skips the non-streaming ExcelJS case)

import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { Writable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { fileURLToPath } from 'node:url'

import ExcelJS from 'exceljs'

import { stream, toBufferSync } from '../lib/index.js'
import * as wasm from '../lib/browser/browser.js'

const CATEGORIES = ['Hardware', 'Software', 'Services', 'Consulting', 'Support', 'Training']
const START = Date.UTC(2024, 0, 1)

function row(i) {
  return [
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
  ]
}

function* rows(count) {
  for (let i = 0; i < count; i++) yield row(i)
}

function* records(count) {
  for (let i = 0; i < count; i++) {
    const [id, name, category, price, amount, created, active, quantity, comment, ratio] = row(i)
    yield { id, name, category, price, amount, created, active, quantity, comment, ratio }
  }
}

class Counter extends Writable {
  bytes = 0
  _write(chunk, _encoding, done) {
    this.bytes += chunk.length
    done()
  }
}

const CASES = {
  'hypaxel, arrays to buffer': async (count) => toBufferSync(rows(count)).length,
  'hypaxel, records to buffer': async (count) => toBufferSync(records(count)).length,
  'hypaxel, arrays streamed': async (count) => {
    const sink = new Counter()
    await pipeline(stream(rows(count)), sink)
    return sink.bytes
  },
  'hypaxel (WebAssembly), arrays to buffer': async (count) => {
    wasm.initSync(await readFile(new URL('../lib/browser/wasm/hypaxel_bg.wasm', import.meta.url)))
    return wasm.toBufferSync(rows(count)).length
  },
  'ExcelJS, streaming writer': async (count) => {
    const sink = new Counter()
    const workbook = new ExcelJS.stream.xlsx.WorkbookWriter({ stream: sink, useSharedStrings: false, useStyles: false })
    const sheet = workbook.addWorksheet('Sheet1')
    for (const values of rows(count)) sheet.addRow(values).commit()
    await workbook.commit()
    return sink.bytes
  },
  'ExcelJS, in-memory workbook': async (count) => {
    const workbook = new ExcelJS.Workbook()
    const sheet = workbook.addWorksheet('Sheet1')
    for (const values of rows(count)) sheet.addRow(values)
    return (await workbook.xlsx.writeBuffer()).byteLength
  },
}

const IN_MEMORY_LIMIT = 200_000

if (process.argv[2] === '--case') {
  const [name, count] = [process.argv[3], Number(process.argv[4])]
  const started = performance.now()
  const bytes = await CASES[name](count)
  const elapsed = performance.now() - started
  console.log(JSON.stringify({ ms: elapsed, bytes, rss: process.resourceUsage().maxRSS }))
} else {
  const count = Number(process.argv[2] ?? 100_000)
  console.log(`${count.toLocaleString('en-US')} rows x 10 columns, Node ${process.version}, ${process.platform} ${process.arch}`)
  for (const name of Object.keys(CASES)) {
    if (name.includes('in-memory') && count > IN_MEMORY_LIMIT) continue
    const output = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--case', name, String(count)], { encoding: 'utf8' })
    const { ms, bytes, rss } = JSON.parse(output)
    const megabytes = (value) => (value / 1024 / 1024).toFixed(1).padStart(8)
    console.log(`${name.padEnd(40)} ${ms.toFixed(0).padStart(8)} ms  ${megabytes(rss * 1024)} MB peak RSS  ${megabytes(bytes)} MB file`)
  }
}
