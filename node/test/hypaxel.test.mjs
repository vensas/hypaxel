import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { test } from 'node:test'

import * as hypaxel from '../lib/index.js'
import { CONTENT_TYPE, Writer, columnName, init, stream, toBuffer, toBufferSync, webStream, writeFile } from '../lib/index.js'
import { STRICT, defineSuite, read } from './suite.mjs'

defineSuite('native', hypaxel)

test('streams lazily from an async source in several chunks', async () => {
  const total = 60_000
  let produced = 0
  async function* source() {
    for (let i = 0; i < total; i++) {
      produced++
      yield [i, `row ${i}`, i * 1.5, i % 2 === 0]
    }
  }
  const readable = stream(source(), { ...STRICT, columns: ['n', 'label', 'value', 'even'] })
  assert.ok(readable instanceof Readable)

  const iterator = readable[Symbol.asyncIterator]()
  const first = await iterator.next()
  assert.ok(first.value.length > 0)
  assert.ok(produced < total, 'the source must not be drained before the output is read')

  const parts = [first.value]
  for (let next = await iterator.next(); !next.done; next = await iterator.next()) parts.push(next.value)
  assert.ok(parts.length > 3)
  assert.equal(produced, total)

  const sheet = (await read(Buffer.concat(parts))).getWorksheet('Sheet1')
  assert.equal(sheet.rowCount, total + 1)
  assert.deepEqual(sheet.getRow(total + 1).values.slice(1), [total - 1, `row ${total - 1}`, (total - 1) * 1.5, false])
})

test('toBuffer, webStream and writeFile produce the same workbook', async () => {
  const rows = Array.from({ length: 5000 }, (_, i) => ({ id: i, name: `name ${i}` }))
  const expected = toBufferSync(rows)

  assert.deepEqual(await toBuffer(rows), expected)

  const web = Buffer.concat(await Array.fromAsync(webStream(rows)))
  assert.deepEqual(web, expected)

  const directory = await mkdtemp(join(tmpdir(), 'hypaxel-'))
  try {
    const path = join(directory, 'out.xlsx')
    await writeFile(path, rows)
    assert.deepEqual(await readFile(path), expected)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('works from CommonJS and exposes constants', () => {
  const required = createRequire(import.meta.url)('../lib/index.js')
  assert.equal(required.CONTENT_TYPE, CONTENT_TYPE)
  assert.equal(CONTENT_TYPE, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
  assert.equal(columnName(1), 'A')
  assert.equal(columnName(27), 'AA')
  assert.equal(columnName(16384), 'XFD')
})

test('memory does not grow with the amount of data streamed', async () => {
  function* rows(count) {
    for (let i = 0; i < count; i++) yield [i, `Customer ${i % 5000}`, i * 1.25, i % 2 === 0, `Unique comment #${i}`]
  }
  const exportOnce = async () => {
    for await (const chunk of stream(rows(500_000))) assert.ok(chunk.length > 0)
    return process.memoryUsage().rss
  }
  const baseline = await exportOnce()
  let last = baseline
  for (let run = 0; run < 3; run++) last = await exportOnce()

  const grownMegabytes = (last - baseline) / 1024 / 1024
  assert.ok(grownMegabytes < 25, `resident memory grew by ${grownMegabytes.toFixed(1)} MB over three more exports`)
})

test('init resolves immediately and drain returns Buffers', async () => {
  await init()
  const writer = new Writer()
  writer.addWorksheet('S').writeRow([1])
  assert.ok(Buffer.isBuffer(writer.drain()))
  assert.ok(Buffer.isBuffer(writer.finish()))
  assert.ok(Buffer.isBuffer(toBufferSync([[1]])))
})
