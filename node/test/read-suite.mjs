// Reader tests every engine must pass. `read.test.mjs` runs them on the native engine and
// `wasm.test.mjs` on the WebAssembly engine.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test as nodeTest } from 'node:test'

import ExcelJS from 'exceljs'
import JSZip from 'jszip'

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
    assert.throws(() => rows.next(), /limit/, 'a sheet that cannot be opened keeps reporting why')
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
      { firstColumn: 1, lastColumn: 1, width: 12.5, hidden: false, visible: true },
      { firstColumn: 2, lastColumn: 2, width: 30, hidden: false, visible: true },
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
        { firstColumn: 1, lastColumn: 1, width: 14, hidden: false, visible: true },
        { firstColumn: 2, lastColumn: 2, width: 22.5, hidden: false, visible: true },
        { firstColumn: 4, lastColumn: 4, width: 6, hidden: true, visible: false },
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

  test('reads a table with a totals row saved by excel', async () => {
    const bytes = await readFile(new URL('../../testdata/reader/excel-totals.xlsx', import.meta.url))
    const book = openWorkbookSync(bytes)
    assert.deepEqual(book.sheetNames, ['Inventory'])
    assert.deepEqual(book.tables(), [
      {
        firstRow: 1,
        firstColumn: 1,
        lastRow: 1002,
        lastColumn: 11,
        options: {
          name: 'Table1',
          columns: [
            'SKU',
            'Item',
            'Group',
            'Vendor',
            'Cost',
            'Price',
            'Opening',
            'Sold',
            'Closing',
            'Reorder',
            'Status',
          ],
          style: 'TableStyleMedium2',
          headerRow: true,
          autofilter: true,
          rowStripes: true,
          columnStripes: false,
          firstColumn: false,
          lastColumn: false,
          totalsRow: true,
        },
      },
    ])

    const rows = book.rows()
    const { columnWidths, outlineSummaryAbove } = rows.layout()
    assert.equal(columnWidths.length, 11)
    assert.deepEqual(columnWidths[0], { firstColumn: 1, lastColumn: 1, width: 11.6640625, hidden: false, visible: true })
    assert.deepEqual(columnWidths[10], { firstColumn: 11, lastColumn: 11, width: 13.1640625, hidden: false, visible: true })
    assert.equal(outlineSummaryAbove, false)
    const data = []
    for (const row of rows) {
      assert.deepEqual([rows.outlineLevel, rows.hidden, rows.height], [0, false, undefined])
      data.push(row)
    }
    assert.equal(data.length, 1002)
    assert.deepEqual(data.at(-1), ['Total', null, null, null, 21.085, 33.736, null, 99696, null, 34500, 1000])
  })

  test('reads a table downloaded from google sheets', async () => {
    const bytes = await readFile(new URL('../../testdata/reader/google-sheets.xlsx', import.meta.url))
    const book = openWorkbookSync(bytes)
    assert.deepEqual(book.sheetNames, ['Inventory'])
    // The file uses a custom table style and has no filter buttons.
    assert.deepEqual(book.tables(), [
      {
        firstRow: 1,
        firstColumn: 1,
        lastRow: 903,
        lastColumn: 11,
        options: {
          name: 'Table1',
          columns: ['SKU', 'Item', 'Group', 'Vendor', 'Cost', 'Price', 'Opening', 'Sold', 'Closing', 'Reorder', 'Status'],
          style: 'Inventory-style',
          headerRow: true,
          autofilter: false,
          rowStripes: true,
          columnStripes: false,
          firstColumn: true,
          lastColumn: true,
          totalsRow: true,
        },
      },
    ])

    const rows = book.rows()
    const layout = rows.layout()
    assert.equal(layout.columnWidths.length, 12)
    assert.deepEqual(layout.columnWidths[0], { firstColumn: 1, lastColumn: 1, width: 11.63, hidden: false, visible: true })
    assert.deepEqual(layout.columnWidths[11], { firstColumn: 12, lastColumn: 26, width: 8.88, hidden: false, visible: true })
    assert.deepEqual([layout.columnWidth(20), layout.outlineSummaryAbove], [8.88, false])
    const data = []
    for (const row of rows) {
      assert.deepEqual([rows.outlineLevel, rows.hidden, rows.height], [0, false, rows.rowNumber === 1 ? undefined : 15.75])
      data.push(row)
    }
    assert.equal(data.length, 903)
    assert.equal(data[1][6], 250)
    assert.deepEqual(data.at(-1), ['Total', null, null, null, 21.20421754, 33.92674806, null, 90058, null, 31060, 901])
  })

  test('returns the layout of a sheet whose first row cannot be read', async () => {
    const zip = new JSZip()
    zip.file('_rels/.rels', '<Relationships><Relationship Id="rId1" Type="t/officeDocument" Target="xl/workbook.xml"/></Relationships>')
    zip.file('xl/workbook.xml', '<workbook><sheets><sheet name="S" sheetId="1" id="rId1"/></sheets></workbook>')
    zip.file('xl/_rels/workbook.xml.rels', '<Relationships><Relationship Id="rId1" Type="t/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
    zip.file(
      'xl/worksheets/sheet1.xml',
      '<worksheet><cols><col min="2" max="3" width="9.5"/></cols><sheetData><row r="1"><c><v>1,5</v></c></row></sheetData></worksheet>',
    )
    const bytes = await zip.generateAsync({ type: 'uint8array' })

    const rows = openWorkbookSync(bytes).rows()
    assert.equal(rows.layout().columnWidth(3), 9.5)
    assert.throws(() => rows.next(), /invalid number/)

    const later = openWorkbookSync(bytes).rows()
    assert.equal((await later.layoutAsync()).columnWidth(2), 9.5)
    await assert.rejects(later.nextAsync(), /invalid number/)
  })

  test('reads a sheet exported by numbers', async () => {
    const bytes = await readFile(new URL('../../testdata/reader/numbers.xlsx', import.meta.url))
    const book = openWorkbookSync(bytes, { includeEmptyRows: true })
    assert.deepEqual(book.sheetNames, ['Table and Chart'])
    // Numbers writes its table as plain cells, not as an Excel table.
    assert.deepEqual(book.tables(), [])
    const rows = book.rows()
    const layout = rows.layout()
    assert.deepEqual(
      layout.columnWidths.map((c) => [c.firstColumn, c.lastColumn, c.width, c.hidden]),
      [
        [1, 1, 24.6719, false],
        [2, 2, 18.3672, false],
        [3, 5, 18.7188, false],
        [6, 16384, 27.75, false],
      ],
    )
    assert.deepEqual([layout.outlineSummaryAbove, layout.rowOutlineLevels], [false, 0])
    const seen = []
    for (const row of rows) seen.push([rows.rowNumber, rows.height, row])
    assert.deepEqual(seen, [
      [1, 311.6, []],
      [2, 27.65, ['Table 1']],
      [3, 20.25, [null, 'Category A', 'Category B']],
      [4, 20.25, ['Item 1', 5, 7]],
      [5, 20.05, ['Item 2', 10, 8]],
      [6, 20.05, ['Item 3', 9, 15]],
      [7, 20.05, ['Item 4', 7, 12]],
      [8, 20.05, ['Item 5', 16, 21]],
    ])
    assert.deepEqual(openWorkbookSync(bytes).rows(0, { skipEmpty: true }).next().value, ['Table 1'])
  })

  test('reports a layout failure again on every later call', async () => {
    const workbook = async (sheet) => {
      const zip = new JSZip()
      zip.file('_rels/.rels', '<Relationships><Relationship Id="rId1" Type="t/officeDocument" Target="xl/workbook.xml"/></Relationships>')
      zip.file('xl/workbook.xml', '<workbook><sheets><sheet name="S" sheetId="1" id="rId1"/></sheets></workbook>')
      zip.file('xl/_rels/workbook.xml.rels', '<Relationships><Relationship Id="rId1" Type="t/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
      if (sheet !== undefined) zip.file('xl/worksheets/sheet1.xml', sheet)
      return zip.generateAsync({ type: 'uint8array' })
    }
    const cols = '<col min="1" max="1" width="1"/>'.repeat(16_385)
    const cases = [
      [await workbook(`<worksheet><cols>${cols}</cols><sheetData><row><c><v>1</v></c></row></sheetData></worksheet>`), /column ranges/],
      [await workbook(undefined), /missing/],
    ]
    for (const [bytes, expected] of cases) {
      const rows = openWorkbookSync(bytes).rows()
      assert.throws(() => rows.layout(), expected)
      assert.throws(() => rows.layout(), expected)
      await assert.rejects(rows.layoutAsync(), expected)
      assert.throws(() => rows.next(), expected)
      await assert.rejects(rows.nextAsync(), expected)

      const first = openWorkbookSync(bytes).rows()
      assert.throws(() => first.next(), expected)
      assert.throws(() => first.layout(), expected)
      await assert.rejects(first.nextAsync(), expected)

      const later = openWorkbookSync(bytes).rows()
      await assert.rejects(later.layoutAsync(), expected)
      assert.throws(() => later.toArray(), expected)
    }
  })

  test('round-trips table names that look like excel escapes', () => {
    const writer = new Writer()
    const sheet = writer.addWorksheet('S')
    sheet.writeRow(['a_x0042_'])
    sheet.writeRow([1])
    sheet.addTable(1, 1, 2, 1, { name: 'T_x0041_', columns: ['a_x0042_'] })
    const [table] = openWorkbookSync(writer.finish()).tables()
    assert.deepEqual([table.options.name, table.options.columns], ['T_x0041_', ['a_x0042_']])
  })

  test('round-trips custom table styles', () => {
    const writer = new Writer()
    const sheet = writer.addWorksheet('S')
    sheet.writeRow(['a', null, 'b'])
    sheet.writeRow([1, null, 2])
    sheet.addTable(1, 1, 2, 1, { name: 'Branded', columns: ['a'], style: 'Brand & Co_x0041_' })
    sheet.addTable(1, 3, 2, 3, { name: 'Builtin', columns: ['b'], style: 'TableStyleLight9' })
    const tables = openWorkbookSync(writer.finish()).tables()
    assert.deepEqual(tables.map((t) => t.options.style), ['Brand & Co_x0041_', 'TableStyleLight9'])
    const bad = new Writer().addWorksheet('S')
    assert.throws(() => bad.addTable(1, 1, 2, 1, { columns: ['a'], style: 'two\nlines' }), /invalid table style name/)
  })

  async function sheetPackage(sheetXml) {
    const zip = new JSZip()
    zip.file('_rels/.rels', '<Relationships><Relationship Id="rId1" Type="t/officeDocument" Target="xl/workbook.xml"/></Relationships>')
    zip.file('xl/workbook.xml', '<workbook><sheets><sheet name="S" sheetId="1" id="rId1"/></sheets></workbook>')
    zip.file('xl/_rels/workbook.xml.rels', '<Relationships><Relationship Id="rId1" Type="t/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
    zip.file('xl/worksheets/sheet1.xml', sheetXml)
    return zip.generateAsync({ type: 'uint8array' })
  }

  test('reports hidden columns without a width', async () => {
    const bytes = await sheetPackage(
      '<worksheet><cols><col min="1" max="1" width="9"/><col min="2" max="3" hidden="1"/><col min="4" max="4" style="1"/></cols><sheetData/></worksheet>',
    )
    const layout = openWorkbookSync(bytes).rows().layout()
    assert.deepEqual(layout.columnWidths, [
      { firstColumn: 1, lastColumn: 1, width: 9, hidden: false, visible: true },
      { firstColumn: 2, lastColumn: 3, width: undefined, hidden: true, visible: false },
    ])
    assert.deepEqual([layout.columnWidth(2), layout.columnHidden(3), layout.columnHidden(4)], [undefined, true, false])
  })

  test('reports zero-width columns as not visible', async () => {
    const bytes = await sheetPackage(
      '<worksheet><cols><col min="1" max="1" width="0"/><col min="2" max="2" width="8" hidden="1"/><col min="3" max="3" width="8"/></cols><sheetData/></worksheet>',
    )
    const layout = openWorkbookSync(bytes).rows().layout()
    assert.deepEqual(layout.columnWidths[0], { firstColumn: 1, lastColumn: 1, width: 0, hidden: false, visible: false })
    assert.deepEqual(
      [1, 2, 3, 4].map((c) => [layout.columnHidden(c), layout.columnVisible(c)]),
      [[false, false], [true, false], [false, true], [false, true]],
    )
  })

  test('applies configured limits', async () => {
    const text = 'x'.repeat(100_000)
    const cell = `<c t="inlineStr"><is><t>${text}</t></is></c>`
    const bytes = await sheetPackage(`<worksheet><sheetData><row r="1">${cell}${cell}</row></sheetData></worksheet>`)
    assert.equal(openWorkbookSync(bytes).rows().toArray()[0].length, 2)
    assert.throws(() => openWorkbookSync(bytes, { maxTokenBytes: 1 }).rows().toArray(), /maxTokenBytes/)
    assert.throws(() => openWorkbookSync(bytes, { maxRowTextBytes: 199_999 }).rows().toArray(), /maxRowTextBytes/)
    assert.throws(() => openWorkbookSync(bytes, { maxTables: 0 }), XlsxError)

    const writer = new Writer()
    const sheet = writer.addWorksheet('S')
    sheet.writeRow(['a', null, 'b'])
    sheet.writeRow([1, null, 2])
    sheet.addTable(1, 1, 2, 1, { columns: ['a'] })
    sheet.addTable(1, 3, 2, 3, { columns: ['b'] })
    const tables = writer.finish()
    assert.equal(openWorkbookSync(tables).tables().length, 2)
    assert.throws(() => openWorkbookSync(tables, { maxTables: 1 }).tables(), /maxTables/)
    assert.throws(() => openWorkbookSync(tables, { maxTableBytes: 10 }).tables(), /maxTableBytes/)
  })
}
