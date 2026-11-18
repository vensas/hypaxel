import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { test } from 'node:test'

import ExcelJS from 'exceljs'
import JSZip from 'jszip'

import {
  CONTENT_TYPE,
  Formula,
  Styled,
  Writer,
  XlsxError,
  columnName,
  stream,
  toBuffer,
  toBufferSync,
  webStream,
  writeFile,
} from '../lib/index.js'

// ExcelJS only reads cells that carry an explicit reference.
const STRICT = { cellReferences: true }

async function read(buffer) {
  const workbook = new ExcelJS.Workbook()
  await workbook.xlsx.load(buffer)
  return workbook
}

function values(sheet) {
  const rows = []
  sheet.eachRow({ includeEmpty: true }, (row) => rows.push(row.values.slice(1)))
  return rows
}

test('writes every value type through the low-level writer', async () => {
  const writer = new Writer({ dates: 'utc', cellReferences: true })
  const sheet = writer.addWorksheet('Types')
  const when = new Date(Date.UTC(2024, 4, 1, 13, 30, 15))
  sheet.writeRow(['text', 42, 1.5, true, false, null, undefined, 12n, when, new Formula('SUM(B1:C1)')])
  const book = await read(writer.finish())

  const row = book.getWorksheet('Types').getRow(1)
  assert.equal(row.getCell(1).value, 'text')
  assert.equal(row.getCell(2).value, 42)
  assert.equal(row.getCell(3).value, 1.5)
  assert.equal(row.getCell(4).value, true)
  assert.equal(row.getCell(5).value, false)
  assert.equal(row.getCell(6).value, null)
  assert.equal(row.getCell(7).value, null)
  assert.equal(row.getCell(8).value, 12)
  assert.equal(row.getCell(9).value.getTime(), when.getTime())
  assert.equal(row.getCell(10).value.formula, 'SUM(B1:C1)')
  assert.equal(writer.finished, true)
})

test('writes local wall-clock time by default', async () => {
  const writer = new Writer(STRICT)
  const when = new Date(2024, 0, 15, 8, 45, 0)
  writer.addWorksheet('Dates').writeRow([when, new Date(Number.NaN)])
  const row = (await read(writer.finish())).getWorksheet('Dates').getRow(1)

  const written = row.getCell(1).value
  assert.deepEqual(
    [written.getUTCFullYear(), written.getUTCMonth(), written.getUTCDate(), written.getUTCHours(), written.getUTCMinutes()],
    [2024, 0, 15, 8, 45],
  )
  assert.equal(row.getCell(2).value, null)
})

test('preserves text exactly, including non-ASCII, markup and long strings', async () => {
  const texts = [
    '',
    'plain ascii',
    '<a href="x">Tom & Jerry</a>',
    'Grüße aus Köln',
    '日本語のテキスト',
    'emoji 🚀 and more',
    'x'.repeat(31) + 'é',
    'long '.repeat(2000),
    'ü'.repeat(32_767),
  ]
  const book = await read(toBufferSync(texts.map((text) => [text]), STRICT))
  const sheet = book.getWorksheet('Sheet1')
  texts.forEach((text, i) => assert.equal(sheet.getCell(i + 1, 1).value ?? '', text))
})

test('writes rows larger than the encoder buffer and rejects over-long text', async () => {
  const wide = Array.from({ length: 40 }, (_, i) => String(i % 10).repeat(30_000))
  const sheet = (await read(toBufferSync([wide, ['after']], STRICT))).getWorksheet('Sheet1')
  assert.equal(sheet.getCell(1, 40).value, wide[39])
  assert.equal(sheet.getCell(2, 1).value, 'after')

  assert.throws(() => toBufferSync([['x'.repeat(32_768)]]), XlsxError)
})

test('omits redundant cell references unless asked to write them', async () => {
  const sheetXml = async (options) => {
    const zip = await JSZip.loadAsync(toBufferSync([[1, 2], [3, 4]], options))
    return zip.file('xl/worksheets/sheet1.xml').async('string')
  }
  assert.doesNotMatch(await sheetXml({}), / r="B2"/)
  assert.match(await sheetXml(STRICT), / r="B2"/)
})

test('applies styles, row styles and per-cell styles', async () => {
  const writer = new Writer(STRICT)
  const header = writer.addStyle({ bold: true, fontColor: '#FFFFFF', fill: '#305496', align: 'center' })
  const money = writer.addStyle({ numFormat: '#,##0.00' })
  const sheet = writer.addWorksheet('Sales', { freezeRows: 1, columnWidths: [20, 12] })
  sheet.writeRow(['Product', 'Revenue'], header)
  sheet.writeRow(['Widget', new Styled(1234.5, money)])
  sheet.writeRow(['Total', new Styled(new Formula('SUM(B2:B2)'), money)])
  sheet.mergeCells(4, 1, 4, 2)
  sheet.autofilter(1, 1, 3, 2)
  const book = await read(writer.finish())

  const read_ = book.getWorksheet('Sales')
  assert.equal(read_.getCell('A1').font.bold, true)
  assert.equal(read_.getCell('B1').alignment.horizontal, 'center')
  assert.equal(read_.getCell('A2').font?.bold ?? false, false)
  assert.equal(read_.getCell('B2').numFmt, '#,##0.00')
  assert.equal(read_.getCell('B3').numFmt, '#,##0.00')
  assert.equal(read_.getColumn(1).width, 20)
  assert.equal(read_.views[0].ySplit, 1)
  assert.equal(read_.getCell('A4').isMerged, true)
  assert.ok(read_.autoFilter)
})

test('supports cell-by-cell writing, row options and skipping', async () => {
  const writer = new Writer(STRICT)
  const sheet = writer.addWorksheet('Cells')
  sheet.beginRow({ height: 30 })
  sheet.write('a')
  sheet.skipCells(2)
  sheet.write('d')
  sheet.endRow()
  sheet.skipRows(2)
  sheet.writeRow(['after'])
  assert.equal(sheet.row, 4)
  const read_ = (await read(writer.finish())).getWorksheet('Cells')

  assert.equal(read_.getRow(1).height, 30)
  assert.equal(read_.getCell('A1').value, 'a')
  assert.equal(read_.getCell('D1').value, 'd')
  assert.equal(read_.getCell('A4').value, 'after')
})

test('writes several worksheets in order', async () => {
  const writer = new Writer(STRICT)
  const first = writer.addWorksheet('First')
  first.writeRow([1])
  const second = writer.addWorksheet('Second', { hidden: true })
  second.writeRows([[2], [3]])
  assert.throws(() => first.writeRow([9]), XlsxError)
  const book = await read(writer.finish())

  assert.deepEqual(book.worksheets.map((sheet) => sheet.name), ['First', 'Second'])
  assert.equal(book.getWorksheet('Second').state, 'hidden')
  assert.deepEqual(values(book.getWorksheet('Second')), [[2], [3]])
})

test('reports misuse with clear errors', () => {
  const writer = new Writer()
  const other = new Writer()
  const sheet = writer.addWorksheet('Errors')

  assert.throws(() => writer.addStyle({ fill: 'red' }), XlsxError)
  assert.throws(() => writer.addStyle({ border: 'wavy' }), XlsxError)
  assert.throws(() => sheet.writeRow(['x'], other.addStyle({ bold: true })), /does not belong to this writer/)
  assert.throws(() => sheet.writeRow('not a row'), TypeError)
  assert.throws(() => sheet.writeRow([{ nested: true }]), /cannot write an object/)
  assert.throws(() => sheet.writeRow([[1, 2]]), /cannot write an array/)
  assert.throws(() => writer.addWorksheet('Errors'), XlsxError)
  assert.throws(() => columnName(0), XlsxError)

  writer.finish()
  assert.throws(() => writer.finish(), /already finished/)
  assert.throws(() => sheet.writeRow([1]), /already finished/)
})

test('a failed row leaves no partial cells behind', async () => {
  const writer = new Writer(STRICT)
  const sheet = writer.addWorksheet('Atomic')
  sheet.writeRow(['kept'])
  assert.throws(() => sheet.writeRow(['dropped', Symbol('bad')]), TypeError)
  sheet.writeRow(['next'])

  assert.deepEqual(values((await read(writer.finish())).getWorksheet('Atomic')), [['kept'], ['next']])
})

test('exports records with a header, selected columns and formats', async () => {
  const records = [
    { id: 1, name: 'Ada', amount: 10.5, internal: 'x' },
    { id: 2, name: 'Linus', amount: 20 },
  ]
  const buffer = toBufferSync(records, {
    ...STRICT,
    sheetName: 'People',
    columns: ['id', { key: 'name', header: 'Full name', width: 30 }, { key: 'amount', numFormat: '0.00' }, 'missing'],
  })
  const sheet = (await read(buffer)).getWorksheet('People')

  assert.deepEqual(values(sheet)[0], ['id', 'Full name', 'amount', 'missing'])
  assert.deepEqual(values(sheet)[1], [1, 'Ada', 10.5])
  assert.equal(sheet.getCell('A1').font.bold, true)
  assert.equal(sheet.getCell('C2').numFmt, '0.00')
  assert.equal(sheet.getColumn(2).width, 30)
  assert.equal(sheet.views[0].ySplit, 1)
  assert.ok(sheet.autoFilter)
})

test('exports records using the keys of the first record', async () => {
  const sheet = (await read(toBufferSync([{ a: 1, b: 'x' }, { b: 'y', a: 2, c: 'ignored' }], STRICT))).getWorksheet('Sheet1')
  assert.deepEqual(values(sheet), [['a', 'b'], [1, 'x'], [2, 'y']])
})

test('exports arrays with and without a header', async () => {
  const plain = (await read(toBufferSync([[1, 'a'], [2, 'b']], STRICT))).getWorksheet('Sheet1')
  assert.deepEqual(values(plain), [[1, 'a'], [2, 'b']])
  assert.equal(plain.autoFilter, undefined)

  const headed = (await read(toBufferSync([[1, 2.5]], { ...STRICT, columns: ['Id', { key: 'Price', numFormat: '0.0' }] }))).getWorksheet('Sheet1')
  assert.deepEqual(values(headed), [['Id', 'Price'], [1, 2.5]])
  assert.equal(headed.getCell('B2').numFmt, '0.0')

  const bare = (await read(toBufferSync([[1]], { ...STRICT, columns: ['Id'], header: false }))).getWorksheet('Sheet1')
  assert.deepEqual(values(bare), [[1]])
})

test('exports an empty source as a valid workbook', async () => {
  assert.deepEqual(values((await read(toBufferSync([], STRICT))).getWorksheet('Sheet1')), [])
  assert.deepEqual(values((await read(toBufferSync([], { ...STRICT, columns: ['a', 'b'] }))).getWorksheet('Sheet1')), [['a', 'b']])
})

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

test('propagates source errors through the stream', async () => {
  async function* failing() {
    yield [1]
    throw new Error('query failed')
  }
  await assert.rejects(toBuffer(failing()), /query failed/)
})

test('drain returns output incrementally', () => {
  const writer = new Writer({ flushThreshold: 1024 })
  const sheet = writer.addWorksheet('Drain')
  let drained = 0
  for (let batch = 0; batch < 20; batch++) {
    sheet.writeRows(Array.from({ length: 1000 }, (_, i) => [batch, i, `value ${batch}-${i}-${Math.sin(i)}`]))
    drained += writer.drain().length
    assert.equal(writer.bufferedBytes, 0)
  }
  assert.ok(drained > 0)
  assert.ok(writer.finish().length > 0)
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

async function part(buffer, name) {
  return (await JSZip.loadAsync(buffer)).file(name).async('string')
}

test('row outline levels', async () => {
  const writer = new Writer(STRICT)
  const sheet = writer.addWorksheet('Tree', { rowOutlineLevels: 2, outlineSummaryAbove: true })
  sheet.writeRow(['North', 300])
  sheet.beginRow({ outlineLevel: 1 })
  sheet.writeRow(['Store A', 120])
  sheet.beginRow({ outlineLevel: 2, hidden: true, height: 20 })
  sheet.writeRow(['Till', 60])
  sheet.writeRow(['South', 1])
  const buffer = writer.finish()

  const xml = await part(buffer, 'xl/worksheets/sheet1.xml')
  assert.match(xml, /<outlinePr summaryBelow="0"\/>/)
  assert.match(xml, /outlineLevelRow="2"/)
  assert.match(xml, /<row r="2" outlineLevel="1">/)
  assert.match(xml, /<row r="3" ht="20" customHeight="1" hidden="1" outlineLevel="2">/)
  const book = await read(buffer)
  assert.deepEqual(values(book.getWorksheet('Tree')), [['North', 300], ['Store A', 120], ['Till', 60], ['South', 1]])

  const other = new Writer().addWorksheet('S')
  assert.throws(() => other.beginRow({ outlineLevel: 1 }), /row_outline_levels/)
})

test('excel tables', async () => {
  const writer = new Writer(STRICT)
  const sheet = writer.addWorksheet('Orders')
  sheet.writeRows([['Product', 'Units'], ['Widget', 3], ['Gadget', 5]])
  sheet.addTable(1, 1, sheet.row, 2, { name: 'Orders', columns: ['Product', 'Units'], style: 'TableStyleDark1' })
  sheet.writeRows([['x', 1], ['y', 2]])
  sheet.addTable(4, 1, 5, 2, { headerRow: false, style: 'none' })
  const buffer = writer.finish()

  const t1 = await part(buffer, 'xl/tables/table1.xml')
  assert.match(t1, /name="Orders" displayName="Orders" ref="A1:B3"/)
  assert.match(t1, /name="TableStyleDark1"/)
  const t2 = await part(buffer, 'xl/tables/table2.xml')
  assert.match(t2, /headerRowCount="0"/)
  assert.doesNotMatch(t2, /TableStyle/)
  await read(buffer)

  const other = new Writer().addWorksheet('S')
  assert.throws(() => other.addTable(1, 1, 3, 2), /column names/)
  assert.throws(() => other.addTable(1, 1, 3, 1, { columns: ['a'], style: 'Fancy' }), /table style/)
  assert.throws(() => other.addTable(1, 1, 3, 1, { columns: ['a'], name: 'A1' }), /invalid table name/)
})

test('auto-fit column widths', async () => {
  const writer = new Writer(STRICT)
  const sheet = writer.addWorksheet('Fit', { autofit: true, columnWidths: [30] })
  sheet.writeRow(['fixed', 'A much longer product name', 1234567.5, true])
  sheet.writeRow(['x', 'short', 1, false])
  const fit = (await read(writer.finish())).getWorksheet('Fit')
  assert.equal(fit.getColumn(1).width, 30)
  assert.ok(fit.getColumn(2).width > 25 && fit.getColumn(2).width < 35)
  assert.ok(fit.getColumn(3).width > 8 && fit.getColumn(3).width < 12)

  const capped = new Writer()
  const s = capped.addWorksheet('Capped', { autofit: { maxWidth: 12, sampleRows: 1 } })
  s.writeRow(['x'.repeat(200)])
  s.writeRow(['y'.repeat(400)])
  assert.match(await part(capped.finish(), 'xl/worksheets/sheet1.xml'), /<col min="1" max="1" width="12" customWidth="1"\/>/)

  assert.throws(() => new Writer().addWorksheet('S', { autofit: { sampleRows: 0 } }), /sample/)
})
