import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'

import * as native from '../lib/index.js'
import * as wasm from '../lib/browser/browser.js'
import { STRICT, defineSuite, read, values } from './suite.mjs'

const wasmBytes = await readFile(new URL('../lib/browser/wasm/hypaxel_bg.wasm', import.meta.url))

// Tests in a file run in order, so this one sees the engine before it is loaded and then loads it
// for the rest of the file.
test('wasm: the API explains that init() is needed before the engine is loaded', async () => {
  assert.throws(() => new wasm.Writer(), (error) => error instanceof wasm.XlsxError && /await init\(\) first/.test(error.message))
  assert.throws(() => wasm.toBufferSync([[1]]), /await init\(\) first/)
  assert.throws(() => wasm.columnName(1), /await init\(\) first/)
  await assert.rejects(wasm.init(new Uint8Array([0, 1, 2, 3])), (error) => error instanceof wasm.XlsxError && /failed to load/.test(error.message))

  wasm.initSync(wasmBytes)
  assert.equal(wasm.columnName(1), 'A')
})

defineSuite('wasm', wasm)

test('wasm: init after loading resolves without reloading', async () => {
  await wasm.init()
  wasm.initSync(new Uint8Array([0]))
})

test('wasm: produces the same bytes as the native engine', () => {
  const header = { bold: true, fill: '#D9D9D9', border: 'thin', align: 'center' }
  const build = (api) => {
    const writer = new api.Writer({ dates: 'utc', sharedStrings: true })
    const bold = writer.addStyle(header)
    const money = writer.addStyle({ numFormat: '#,##0.00', fontColor: '#FF0000' })
    const sheet = writer.addWorksheet('Data', { freezeRows: 1, autofit: true, rowOutlineLevels: 1, tabColor: '#00FF00' })
    sheet.writeRow(['id', 'name', 'amount', 'when', 'ok', 'formula'], bold)
    for (let i = 0; i < 20_000; i++) {
      if (i % 100 === 0) sheet.beginRow({ outlineLevel: 1, height: 18 })
      sheet.writeRow([
        i,
        `naïve name ${i % 997} 🚀`,
        new api.Styled(i * 1.25, money),
        new Date(Date.UTC(2024, 0, 1 + (i % 365))),
        i % 2 === 0,
        new api.Formula(`C${i + 2}*2`),
      ])
    }
    sheet.addTable(1, 1, sheet.row, 6, { columns: ['id', 'name', 'amount', 'when', 'ok', 'formula'] })
    writer.addWorksheet('Second', { hidden: true }).writeRows([[null, undefined, 1n, 'x']])
    return Buffer.from(writer.finish())
  }
  assert.deepEqual(build(wasm), build(native))
  assert.deepEqual(Buffer.from(wasm.toBufferSync([{ a: 1, b: 'é' }])), native.toBufferSync([{ a: 1, b: 'é' }]))
})

test('wasm: toBlob returns an XLSX blob', async () => {
  const blob = await wasm.toBlob([{ id: 1, name: 'Ada' }], STRICT)
  assert.ok(blob instanceof Blob)
  assert.equal(blob.type, wasm.CONTENT_TYPE)
  const book = await read(Buffer.from(await blob.arrayBuffer()))
  assert.deepEqual(values(book.getWorksheet('Sheet1')), [['id', 'name'], [1, 'Ada']])
})

test('wasm: webStream pulls from the source only as the consumer reads', async () => {
  const total = 60_000
  let produced = 0
  async function* source() {
    for (let i = 0; i < total; i++) {
      produced++
      yield [i, `row ${i}`]
    }
  }
  const reader = wasm.webStream(source(), { ...STRICT, batchRows: 1000 }).getReader()
  const first = await reader.read()
  assert.ok(first.value.length > 0)
  assert.ok(produced < total, 'the source must not be drained before the output is read')

  const parts = [first.value]
  for (let next = await reader.read(); !next.done; next = await reader.read()) parts.push(next.value)
  assert.equal(produced, total)
  const sheet = (await read(Buffer.concat(parts))).getWorksheet('Sheet1')
  assert.equal(sheet.rowCount, total)
})

test('wasm: cancelling a webStream stops the source', async () => {
  let finished = false
  async function* source() {
    try {
      for (let i = 0; ; i++) yield [i]
    } finally {
      finished = true
    }
  }
  const reader = wasm.webStream(source(), { batchRows: 100 }).getReader()
  await reader.read()
  await reader.cancel()
  assert.equal(finished, true)
})

test('wasm: rejects malformed options instead of ignoring them', () => {
  const writer = new wasm.Writer()
  assert.throws(() => writer.addStyle({ indent: 'four' }), wasm.XlsxError)
  assert.throws(() => writer.addWorksheet('S', { freezeRows: -1 }), wasm.XlsxError)
})

test('wasm: memory stays bounded while streaming', async () => {
  function* rows(count) {
    for (let i = 0; i < count; i++) yield [i, `Customer ${i % 5000}`, i * 1.25, i % 2 === 0, `Unique comment #${i}`]
  }
  const exportOnce = async () => {
    const reader = wasm.webStream(rows(300_000)).getReader()
    while (!(await reader.read()).done);
    return process.memoryUsage().rss
  }
  const baseline = await exportOnce()
  let last = baseline
  for (let run = 0; run < 3; run++) last = await exportOnce()
  const grownMegabytes = (last - baseline) / 1024 / 1024
  assert.ok(grownMegabytes < 25, `resident memory grew by ${grownMegabytes.toFixed(1)} MB over three more exports`)
})
