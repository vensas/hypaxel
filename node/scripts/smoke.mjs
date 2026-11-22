// Release smoke test: run from a directory where the packed tarball is installed. Writes a
// workbook with the native addon and reads it back, so a missing or unloadable binary fails.
import { readRows, toBuffer } from 'hypaxel'

const buffer = await toBuffer([{ id: 1, name: 'Ada' }], { sheetName: 'Smoke' })
const rows = await readRows(buffer, 'Smoke')
const expected = [['id', 'name'], [1, 'Ada']]
if (JSON.stringify(rows) !== JSON.stringify(expected)) {
  throw new Error(`read back ${JSON.stringify(rows)}, expected ${JSON.stringify(expected)}`)
}
console.log(`hypaxel works on ${process.platform}-${process.arch} with Node.js ${process.version}`)
