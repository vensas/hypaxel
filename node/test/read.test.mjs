import assert from 'node:assert/strict'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { test } from 'node:test'
import { pathToFileURL } from 'node:url'

import * as hypaxel from '../lib/index.js'
import { XlsxError, iterRecords, iterRows, openWorkbook, openWorkbookSync, readRecords, readRows, toBufferSync } from '../lib/index.js'
import { defineReadSuite } from './read-suite.mjs'

defineReadSuite('native', hypaxel)

async function withFile(bytes, fn) {
  const directory = await mkdtemp(join(tmpdir(), 'hypaxel-read-'))
  try {
    const path = join(directory, 'book.xlsx')
    await writeFile(path, bytes)
    await fn(path)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

test('native: reads from a path and a file URL', async () => {
  const rows = Array.from({ length: 10_000 }, (_, i) => [i, `row ${i}`])
  await withFile(toBufferSync(rows), async (path) => {
    assert.deepEqual(openWorkbookSync(path).rows().toArray(), rows)
    assert.deepEqual((await openWorkbook(pathToFileURL(path))).rows().toArray(), rows)
    assert.deepEqual(await readRows(path), rows)
    assert.equal((await readRecords(path)).length, rows.length - 1)
    let count = 0
    for await (const record of iterRecords(path, 'Sheet1')) {
      assert.deepEqual(Object.keys(record), ['0', 'row 0'])
      count++
    }
    assert.equal(count, rows.length - 1)
  })
})

test('native: reports a missing file', async () => {
  const missing = join(tmpdir(), 'hypaxel-does-not-exist.xlsx')
  assert.throws(() => openWorkbookSync(missing), (e) => e instanceof XlsxError && /file not found/.test(e.message))
  await assert.rejects(readRows(missing), /file not found/)
})

test('native: opens a Node.js readable stream, e.g. an upload', async () => {
  const bytes = toBufferSync([['streamed', 1]])
  const chunks = [bytes.subarray(0, 50), bytes.subarray(50)]
  assert.deepEqual(await readRows(Readable.from(chunks)), [['streamed', 1]])
  await assert.rejects(readRows(Readable.from(['text'])), /must yield bytes/)
  assert.throws(() => openWorkbookSync(Readable.from(chunks)), /use openWorkbook for streams/)
})

test('native: async iteration reads off the main thread and rejects overlapping calls', async () => {
  const bytes = toBufferSync(Array.from({ length: 20_000 }, (_, i) => [i, `text ${i}`]))
  const rows = openWorkbookSync(bytes).rows()
  const first = rows.nextAsync()
  assert.throws(() => rows.next(), /already being read/)
  assert.deepEqual((await first).value, [0, 'text 0'])

  let turns = 0
  let running = true
  const spin = () => {
    turns++
    if (running) setImmediate(spin)
  }
  setImmediate(spin)
  let count = 1
  for await (const _ of rows) count++
  running = false
  assert.equal(count, 20_000)
  assert.ok(turns > 1, 'the event loop must run between batches')
})

test('native: closing during an asynchronous read stops the rows', async () => {
  const bytes = toBufferSync(Array.from({ length: 20_000 }, (_, i) => [i]))
  const rows = openWorkbookSync(bytes).rows()
  const pending = rows.nextAsync()
  rows.close()
  await pending
  assert.equal(rows.next().done, true)
})

test('native: iterRows closes the workbook after an error in the loop body', async () => {
  const bytes = toBufferSync([[1], [2], [3]])
  await assert.rejects(async () => {
    for await (const row of iterRows(bytes)) if (row[0] === 2) throw new Error('stop')
  }, /stop/)
})

test('native: supports explicit resource management', () => {
  const book = openWorkbookSync(toBufferSync([[1]]))
  assert.equal(typeof book[Symbol.dispose], 'function')
  book[Symbol.dispose]()
  assert.throws(() => book.rows(), /closed/)
})
