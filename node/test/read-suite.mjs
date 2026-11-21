// Reader tests every engine must pass. `read.test.mjs` runs them on the native engine and
// `wasm.test.mjs` on the WebAssembly engine.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test as nodeTest } from 'node:test'

import ExcelJS from 'exceljs'

/** A workbook written by ExcelJS: shared strings, built-in date formats, errors and cached formula results. */
export async function excelJsWorkbook({ date1904 = false } = {}) {
  const book = new ExcelJS.Workbook()
  book.properties.date1904 = date1904
  const sheet = book.addWorksheet('Report')
  sheet.addRow(['name', 'amount', 'when', 'ok', 'formula', 'error'])
  sheet.addRow(['Grüße', 12.5, new Date(Date.UTC(2024, 1, 29, 13, 30)), true, { formula: 'B2*2', result: 25 }, { error: '#N/A' }])
  sheet.getCell('C2').numFmt = 'yyyy-mm-dd hh:mm'
  book.addWorksheet('Hidden', { state: 'hidden' }).addRow(['secret'])
  book.addWorksheet('VeryHidden', { state: 'veryHidden' }).addRow(['more secret'])
  return Buffer.from(await book.xlsx.writeBuffer())
}

export function defineReadSuite(label, api) {
  const { Formula, Styled, Writer, XlsxError, iterRows, openWorkbook, openWorkbookSync, readRecords, readRows, toBufferSync } = api
  const test = (name, fn) => nodeTest(`${label}: ${name}`, fn)

  test('reads every value type written by hypaxel, inline and shared', async () => {
    for (const sharedStrings of [false, true]) {
      const writer = new Writer({ sharedStrings })
      const hours = writer.addStyle({ numFormat: '[h]:mm:ss' })
      const time = writer.addStyle({ numFormat: 'hh:mm' })
      const date = writer.addStyle({ numFormat: 'yyyy-mm-dd' })
      const sheet = writer.addWorksheet('Types')
      const when = new Date(2024, 4, 1, 13, 30, 15, 250)
      sheet.writeRow(['text', 42, -1.5, true, false, null, when, new Formula('1+1')])
      sheet.writeRow([new Styled(1.5, hours), new Styled(0.75, time), new Styled(45_000, date), 12n, 1e300])
      const book = openWorkbookSync(writer.finish())

      const rows = book.rows().toArray()
      // A formula without a cached result has no value, so the row ends before it.
      assert.deepEqual(rows[0], ['text', 42, -1.5, true, false, null, when], `sharedStrings: ${sharedStrings}`)
      const [duration, timeOfDay, day, big, huge] = rows[1]
      assert.equal(duration, 36 * 3_600_000)
      assert.ok(timeOfDay instanceof Date)
      assert.deepEqual([timeOfDay.getHours(), timeOfDay.getMinutes()], [18, 0])
      assert.deepEqual([day.getFullYear(), day.getMonth(), day.getDate()], [2023, 2, 15])
      assert.equal(big, 12)
      assert.equal(huge, 1e300)
    }
  })

  test('returns dates in local time by default and in UTC on request', () => {
    const local = new Date(2024, 0, 15, 8, 45)
    const utc = new Date(Date.UTC(2024, 6, 1, 23, 59, 59, 999))
    const bytes = toBufferSync([[local], [new Date(2024, 6, 1, 23, 59, 59, 999)]])
    const [[a], [b]] = openWorkbookSync(bytes).rows().toArray()
    assert.equal(a.getTime(), local.getTime())
    assert.equal(b.getTime(), new Date(2024, 6, 1, 23, 59, 59, 999).getTime())

    const written = toBufferSync([[utc]], { dates: 'utc' })
    const [[c]] = openWorkbookSync(written, { dates: 'utc' }).rows().toArray()
    assert.equal(c.getTime(), utc.getTime())
  })

  test('reads workbooks from other producers: shared strings, errors, formula results, 1904 dates', async () => {
    for (const date1904 of [false, true]) {
      const book = openWorkbookSync(await excelJsWorkbook({ date1904 }))
      assert.equal(book.date1904, date1904)
      assert.deepEqual(book.sheets, [
        { name: 'Report', visible: 'visible', kind: 'worksheet' },
        { name: 'Hidden', visible: 'hidden', kind: 'worksheet' },
        { name: 'VeryHidden', visible: 'veryHidden', kind: 'worksheet' },
      ])
      const rows = book.rows('Report').toArray()
      assert.deepEqual(rows[0], ['name', 'amount', 'when', 'ok', 'formula', 'error'])
      const [name, amount, when, ok, formula, error] = rows[1]
      assert.deepEqual([name, amount, ok, formula, error], ['Grüße', 12.5, true, 25, '#N/A'])
      assert.deepEqual([when.getFullYear(), when.getMonth(), when.getDate(), when.getHours(), when.getMinutes()], [2024, 1, 29, 13, 30])
      assert.deepEqual(book.rows('hidden').toArray(), [['secret']])
    }
  })

  test('keeps text exact across batches and the shared string cache', () => {
    const texts = ['', 'plain', '<a href="x">Tom & Jerry</a>', 'Grüße aus Köln', '日本語', 'emoji 🚀', 'x'.repeat(24) + 'é', 'ü'.repeat(32_767)]
    for (const sharedStrings of [false, true]) {
      const bytes = toBufferSync(
        texts.map((text) => [text, text]),
        { sharedStrings },
      )
      const rows = openWorkbookSync(bytes).rows().toArray()
      texts.forEach((text, i) => assert.deepEqual(rows[i], [text, text]))
    }

    // Indices 4096 apart share a cache slot; alternating them evicts the slot on every cell.
    const unique = 10_000
    const rows = Array.from({ length: 30_000 }, (_, i) => {
      const n = i % 2 === 0 ? i % unique : (i + 4096) % unique
      return [`value ${n}`, n]
    })
    const read = openWorkbookSync(toBufferSync(rows, { sharedStrings: true })).rows().toArray()
    assert.deepEqual(read, rows)

    // Within one row, strings that share a slot are sent once and then copied from the earlier cell.
    const first = Array.from({ length: 4097 }, (_, i) => `s${i}`)
    const alternating = Array.from({ length: 1000 }, (_, i) => (i % 2 === 0 ? 's0' : 's4096'))
    const mixed = ['s4096', 's1', 's0', 's4096', 's0', 's1', 's4096']
    const collide = [first, alternating, mixed, mixed]
    assert.deepEqual(openWorkbookSync(toBufferSync(collide, { sharedStrings: true })).rows().toArray(), collide)

    const bom = [['\uFEFFcell', '\uFEFF', 'a\uFEFFb']]
    for (const sharedStrings of [false, true]) {
      assert.deepEqual(openWorkbookSync(toBufferSync(bom, { sharedStrings })).rows().toArray(), bom)
    }
  })

  test('streams many rows in several batches, synchronously and asynchronously', async () => {
    const total = 60_000
    const rows = Array.from({ length: total }, (_, i) => [i, `row ${i}`, i * 1.5, i % 2 === 0])
    const bytes = toBufferSync(rows)
    const book = openWorkbookSync(bytes)

    assert.deepEqual(book.rows().toArray(), rows)
    const viaAsync = []
    for await (const row of book.rows()) viaAsync.push(row)
    assert.deepEqual(viaAsync, rows)
    assert.deepEqual(await readRows(bytes), rows)
  })

  test('keeps row positions: gaps are null and missing rows are empty unless skipped', () => {
    const writer = new Writer()
    const sheet = writer.addWorksheet('Gaps')
    sheet.writeRow([1])
    sheet.skipRows(2)
    sheet.write('a')
    sheet.skipCells(3)
    sheet.write('e')
    sheet.endRow()
    sheet.writeRow([null, null])
    sheet.writeRow([undefined, 'b'])
    const bytes = writer.finish()

    assert.deepEqual(openWorkbookSync(bytes).rows().toArray(), [[1], [], [], ['a', null, null, null, 'e'], [], [null, 'b']])
    assert.deepEqual(openWorkbookSync(bytes).rows(0, { skipEmpty: true }).toArray(), [[1], ['a', null, null, null, 'e'], [null, 'b']])
  })

  test('selects sheets by name, case-insensitive name and index', () => {
    const writer = new Writer()
    for (const name of ['First', 'Second', 'Third']) writer.addWorksheet(name).writeRow([name])
    const book = openWorkbookSync(writer.finish())

    assert.deepEqual(book.sheetNames, ['First', 'Second', 'Third'])
    assert.deepEqual(book.rows().toArray(), [['First']])
    assert.deepEqual(book.rows('Second').toArray(), [['Second']])
    assert.deepEqual(book.rows('THIRD').toArray(), [['Third']])
    assert.deepEqual(book.rows(1).toArray(), [['Second']])
    assert.deepEqual(book.rows(-1).toArray(), [['Third']])

    assert.throws(() => book.rows('Fourth'), (e) => e instanceof XlsxError && e.message === 'no worksheet "Fourth"; the workbook has ["First","Second","Third"]')
    assert.throws(() => book.rows(3), (e) => e instanceof XlsxError && /no worksheet at index 3; the workbook has 3/.test(e.message))
    assert.throws(() => book.rows(-4), XlsxError)
    assert.throws(() => book.rows(1.5), XlsxError)
    assert.throws(() => book.rows({}), XlsxError)
  })

  test('reads several sheets, and one sheet twice, at the same time', () => {
    const writer = new Writer()
    writer.addWorksheet('A').writeRows(Array.from({ length: 20_000 }, (_, i) => [`a${i}`]))
    writer.addWorksheet('B').writeRows(Array.from({ length: 20_000 }, (_, i) => [`b${i}`]))
    const book = openWorkbookSync(writer.finish())
    const a = book.rows('A')
    const b = book.rows('B')
    const again = book.rows('A')
    for (let i = 0; i < 20_000; i++) {
      assert.deepEqual(a.next().value, [`a${i}`])
      assert.deepEqual(b.next().value, [`b${i}`])
      if (i % 2 === 0) assert.deepEqual(again.next().value, [`a${i / 2}`])
    }
    assert.equal(a.next().done, true)
  })

  test('reads records keyed by the header row', async () => {
    const bytes = toBufferSync([
      ['title'],
      ['id', 'name', null, 'name', 'id_2'],
      [1, 'one', 'x', 'uno'],
      [],
      [2, 'two', null, null, null, 'extra'],
    ])
    assert.deepEqual(await readRecords(bytes, 0, { headerRow: 2 }), [
      { id: 1, name: 'one', column_3: 'x', name_2: 'uno', id_2: null },
      { id: 2, name: 'two', column_3: null, name_2: null, id_2: null, column_6: 'extra' },
    ])

    const collisions = openWorkbookSync(
      toBufferSync([
        ['name', 'name_2', 'name', 'column_5', null, 'name_3', 'name'],
        [1, 2, 3, 4, 5, 6, 7],
      ]),
    )
    assert.deepEqual(collisions.records().toArray(), [
      { name: 1, name_2: 2, name_3: 3, column_5: 4, column_5_2: 5, name_3_2: 6, name_4: 7 },
    ])

    const book = openWorkbookSync(toBufferSync([{ a: 1, b: 'x' }, { a: 2, b: 'y' }]))
    assert.deepEqual(book.records().toArray(), [
      { a: 1, b: 'x' },
      { a: 2, b: 'y' },
    ])
    const viaAsync = []
    for await (const record of book.records()) viaAsync.push(record)
    assert.equal(viaAsync.length, 2)

    const proto = openWorkbookSync(toBufferSync([['__proto__'], [1]])).records().toArray()
    assert.equal(Object.getPrototypeOf(proto[0]), Object.prototype)
    assert.equal(Object.keys(proto[0])[0], '__proto__')
    assert.throws(() => book.records(0, { headerRow: 0 }), XlsxError)
  })

  test('reports damaged, legacy and oversized input with clear errors', async () => {
    assert.throws(() => openWorkbookSync(new Uint8Array([1, 2, 3, 4])), XlsxError)
    const ole = new Uint8Array(4096)
    ole.set([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1])
    assert.throws(() => openWorkbookSync(ole), (e) => e instanceof XlsxError && /legacy \.xls/.test(e.message))
    await assert.rejects(readRows(ole), /legacy \.xls/)

    const bytes = toBufferSync([['some shared text']], { sharedStrings: true })
    const rows = openWorkbookSync(bytes, { maxSharedStringsBytes: 4 }).rows()
    assert.throws(() => rows.next(), (e) => e instanceof XlsxError && /limit/.test(e.message))
    assert.equal(rows.next().done, true)
    assert.throws(() => openWorkbookSync(bytes, { maxSharedStringsBytes: -1 }), XlsxError)
  })

  test('closing stops reading and rejects further use', async () => {
    const bytes = toBufferSync(Array.from({ length: 50_000 }, (_, i) => [i]))
    const book = openWorkbookSync(bytes)
    const rows = book.rows()
    for (const row of rows) if (row[0] === 10) break
    assert.equal(rows.next().done, true)

    const pending = book.rows()
    book.close()
    assert.throws(() => book.rows(), /the workbook is closed/)
    assert.equal(pending.toArray().length, 50_000)
    book.close()

    let seen = 0
    for await (const row of iterRows(bytes)) if (++seen === 3) break
    assert.equal(seen, 3)
  })

  test('opens workbooks from bytes, array buffers, blobs and web streams', async () => {
    const bytes = toBufferSync([['hello']])
    const expected = [['hello']]
    const copy = new Uint8Array(bytes)
    assert.deepEqual((await openWorkbook(copy)).rows().toArray(), expected)
    assert.deepEqual((await openWorkbook(copy.buffer)).rows().toArray(), expected)
    assert.deepEqual((await openWorkbook(new DataView(copy.buffer))).rows().toArray(), expected)
    assert.deepEqual((await openWorkbook(new Blob([copy]))).rows().toArray(), expected)
    assert.deepEqual((await openWorkbook(new Response(copy))).rows().toArray(), expected)
    const web = new ReadableStream({
      start(controller) {
        controller.enqueue(copy.subarray(0, 100))
        controller.enqueue(copy.subarray(100))
        controller.close()
      },
    })
    assert.deepEqual((await openWorkbook(web)).rows().toArray(), expected)
    await assert.rejects(openWorkbook(42), XlsxError)
  })

  test('reads back tables, row properties, column widths and outline settings', async () => {
    const writer = new Writer()
    const sheet = writer.addWorksheet('Data', { columnWidths: [12.5, 30], rowOutlineLevels: 2, outlineSummaryAbove: true })
    sheet.writeRow(['Product', 'Units & more', 'Line\nbreak'])
    sheet.beginRow({ outlineLevel: 1 })
    sheet.writeRow(['Widget', 3])
    sheet.beginRow({ outlineLevel: 2, hidden: true })
    sheet.writeRow(['Gadget', 5])
    sheet.beginRow({ outlineLevel: 1, collapsed: true, height: 20.5 })
    sheet.writeRow(['Gizmo'])
    sheet.skipRows(2)
    sheet.writeRow([1, 2])
    sheet.writeRow([3, 4])
    const full = {
      name: 'Orders',
      columns: ['Product', 'Units & more', 'Line\nbreak'],
      style: 'TableStyleDark3',
      headerRow: true,
      autofilter: true,
      rowStripes: false,
      columnStripes: true,
      firstColumn: true,
      lastColumn: true,
      totalsRow: false,
    }
    sheet.addTable(1, 1, 4, 3, full)
    sheet.addTable(7, 1, 8, 2, { headerRow: false, style: 'none' })
    writer.addWorksheet('Plain').writeRow(['x'])
    const bytes = writer.finish()

    const book = openWorkbookSync(bytes)
    const tables = book.tables('data')
    assert.deepEqual(tables, book.tables())
    assert.deepEqual(tables, [
      { firstRow: 1, firstColumn: 1, lastRow: 4, lastColumn: 3, options: full },
      {
        firstRow: 7,
        firstColumn: 1,
        lastRow: 8,
        lastColumn: 2,
        options: {
          name: 'Table2',
          columns: ['Column1', 'Column2'],
          style: 'none',
          headerRow: false,
          autofilter: false,
          rowStripes: true,
          columnStripes: false,
          firstColumn: false,
          lastColumn: false,
          totalsRow: false,
        },
      },
    ])
    assert.deepEqual(book.tables(1), [])
    assert.throws(() => book.tables('Missing'), /no worksheet/)

    const rows = book.rows('Data')
    assert.equal(rows.rowNumber, 0)
    const layout = rows.layout()
    assert.deepEqual(layout.columnWidths, [
      { firstColumn: 1, lastColumn: 1, width: 12.5, hidden: false },
      { firstColumn: 2, lastColumn: 2, width: 30, hidden: false },
    ])
    assert.equal(layout.columnHidden(2), false)
    assert.deepEqual([layout.columnWidth(2), layout.columnWidth(3)], [30, undefined])
    assert.deepEqual([layout.outlineSummaryAbove, layout.rowOutlineLevels], [true, 2])
    const seen = []
    for (const row of rows) seen.push([rows.rowNumber, row.length, rows.outlineLevel, rows.hidden, rows.collapsed, rows.height])
    assert.deepEqual(seen, [
      [1, 3, 0, false, false, undefined],
      [2, 2, 1, false, false, undefined],
      [3, 2, 2, true, false, undefined],
      [4, 1, 1, false, true, 20.5],
      [5, 0, 0, false, false, undefined],
      [6, 0, 0, false, false, undefined],
      [7, 2, 0, false, false, undefined],
      [8, 2, 0, false, false, undefined],
    ])
    assert.equal(rows.layout(), layout)

    const compact = book.rows('Data', { skipEmpty: true })
    const numbers = []
    for await (const row of compact) numbers.push([compact.rowNumber, compact.outlineLevel, row.length])
    assert.deepEqual(numbers.map((n) => n[0]), [1, 2, 3, 4, 7, 8])
    assert.equal(numbers[2][1], 2)

    const plain = book.rows('Plain')
    const plainLayout = await plain.layoutAsync()
    assert.deepEqual([plainLayout.columnWidths, plainLayout.outlineSummaryAbove, plainLayout.rowOutlineLevels], [[], false, 0])
    assert.deepEqual(plain.toArray(), [['x']])
    assert.equal(plain.layout(), plainLayout)

    const closed = book.rows('Data')
    closed.close()
    assert.throws(() => closed.layout(), /closed before the sheet layout was read/)

    const copy = new Writer()
    const target = copy.addWorksheet('Copy')
    target.writeRow(['Product', 'Units & more', 'Line\nbreak'])
    for (let i = 0; i < 3; i++) target.writeRow([i, i, i])
    const [table] = tables
    target.addTable(table.firstRow, table.firstColumn, table.lastRow, table.lastColumn, table.options)
    assert.throws(() => target.addTable(6, 1, 8, 3, { ...table.options, name: 'Totals', totalsRow: true }), /totals row/)
    assert.deepEqual(openWorkbookSync(copy.finish()).tables(), [table])
  })

  for (const producer of ['excel', 'libreoffice']) {
    test(`reads tables, rows and layout saved by ${producer}`, async () => {
      // LibreOffice drops table styles and stores a height on every row.
      const styled = producer === 'excel'
      const height = styled ? undefined : 15
      const bytes = await readFile(new URL(`../../testdata/reader/${producer}.xlsx`, import.meta.url))
      const book = openWorkbookSync(bytes, { includeEmptyRows: true })
      assert.deepEqual(book.sheetNames, ['Orders', 'Plain'])
      assert.deepEqual(book.tables('Orders'), [
        {
          firstRow: 1,
          firstColumn: 1,
          lastRow: 4,
          lastColumn: 3,
          options: {
            name: 'Orders',
            columns: ['Product', 'Units & more', 'Price'],
            style: styled ? 'TableStyleDark3' : 'none',
            headerRow: true,
            autofilter: true,
            rowStripes: false,
            columnStripes: styled,
            firstColumn: styled,
            lastColumn: styled,
            totalsRow: false,
          },
        },
        {
          firstRow: 7,
          firstColumn: 1,
          lastRow: 8,
          lastColumn: 2,
          options: {
            name: 'Table2',
            columns: ['Column1', 'Column2'],
            style: 'none',
            headerRow: false,
            autofilter: false,
            rowStripes: styled,
            columnStripes: false,
            firstColumn: false,
            lastColumn: false,
            totalsRow: false,
          },
        },
      ])
      assert.deepEqual(book.tables('Plain'), [])

      const rows = book.rows('Orders')
      const layout = rows.layout()
      assert.deepEqual(layout.columnWidths, [
        { firstColumn: 1, lastColumn: 1, width: 14, hidden: false },
        { firstColumn: 2, lastColumn: 2, width: 22.5, hidden: false },
        { firstColumn: 4, lastColumn: 4, width: 6, hidden: true },
      ])
      assert.deepEqual([layout.columnHidden(4), layout.outlineSummaryAbove, layout.rowOutlineLevels], [true, true, 2])
      const seen = []
      for (const row of rows) seen.push([rows.rowNumber, row.length, rows.outlineLevel, rows.hidden, rows.collapsed, rows.height])
      assert.deepEqual(seen, [
        [1, 3, 0, false, false, height],
        [2, 3, 1, false, styled, height],
        [3, 3, 2, true, false, height],
        [4, 3, 1, false, true, 30],
        [5, 0, 0, false, false, undefined],
        [6, 0, 0, false, false, 45],
        [7, 2, 0, false, false, height],
        [8, 2, 0, false, false, height],
      ])
    })
  }

  test('reports rows without values on request', async () => {
    const writer = new Writer()
    const sheet = writer.addWorksheet('Data', { rowOutlineLevels: 1 })
    sheet.writeRow(['first'])
    sheet.beginRow({ height: 40 })
    sheet.endRow()
    sheet.skipRows(1)
    sheet.writeRow([4])
    sheet.beginRow({ outlineLevel: 1, hidden: true })
    sheet.endRow()
    const bytes = writer.finish()

    const scan = (readOptions, rowsOptions) => {
      const rows = openWorkbookSync(bytes, readOptions).rows(0, rowsOptions)
      const seen = []
      for (const row of rows) seen.push([rows.rowNumber, row, rows.outlineLevel, rows.hidden, rows.height])
      return seen
    }
    assert.deepEqual(scan(), [
      [1, ['first'], 0, false, undefined],
      [2, [], 0, false, undefined],
      [3, [], 0, false, undefined],
      [4, [4], 0, false, undefined],
    ])
    assert.deepEqual(scan({ includeEmptyRows: true }), [
      [1, ['first'], 0, false, undefined],
      [2, [], 0, false, 40],
      [3, [], 0, false, undefined],
      [4, [4], 0, false, undefined],
      [5, [], 1, true, undefined],
    ])
    assert.deepEqual(scan({ includeEmptyRows: true }, { skipEmpty: true }).map((r) => r[0]), [1, 4])
    assert.deepEqual(await readRecords(bytes, 0, { includeEmptyRows: true }), [{ first: 4 }])
  })
}
