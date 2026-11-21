import { createWriteStream } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import type { ReadableStream } from 'node:stream/web'

import { NativeWorkbook, NativeWriter, columnName as nativeColumnName } from '../binding'
import {
  type ExportOptions,
  type RecordLike,
  type Row,
  type Source,
  Writer as CoreWriter,
  XlsxError,
  buildSync,
  chunks,
  guard,
  setEngine,
} from './core'
import {
  type BytesSource,
  type ReadOptions,
  type ReadRecord,
  type ReadValue,
  type RecordsOptions,
  type RowsOptions,
  type SheetRef,
  Workbook,
  bytesOf,
  collectBytes,
  iterRecordsOf,
  iterRowsOf,
  openBytes,
} from './read'

export {
  CONTENT_TYPE,
  MAX_COLUMNS,
  MAX_ROWS,
  Formula,
  Style,
  Styled,
  Worksheet,
  XlsxError,
  columnName,
  type BorderStyle,
  type CellValue,
  type ColumnSpec,
  type ExportOptions,
  type PlainValue,
  type RecordLike,
  type Row,
  type RowOptions,
  type Source,
  type StyleOptions,
  type TableOptions,
  type WorksheetOptions,
  type WriterOptions,
} from './core'
export {
  SheetLayout,
  SheetRecords,
  SheetRows,
  Workbook,
  type BytesSource,
  type ColumnWidth,
  type ReadOptions,
  type ReadRecord,
  type ReadTable,
  type ReadValue,
  type RecordsOptions,
  type RowsOptions,
  type SheetInfo,
  type SheetRef,
} from './read'

setEngine({
  createWriter: (options) => new NativeWriter(options),
  columnName: nativeColumnName,
  alloc: (size) => Buffer.allocUnsafe(size),
  writeUtf8: (buffer, offset, value) => (buffer as Buffer).write(value, offset, 'utf8'),
  openWorkbook: (bytes, options) => NativeWorkbook.openBytes(bytes, options),
  decodeUtf8: (buffer, start, end) => (buffer as Buffer).toString('utf8', start, end),
})

/**
 * Forward-only XLSX writer that keeps its output in memory.
 *
 * Take the compressed bytes in chunks with {@link drain} while writing, and the rest with
 * {@link finish}. For most uses the helpers {@link stream}, {@link toBuffer} and {@link writeFile}
 * are simpler.
 */
export class Writer extends CoreWriter {
  /** Returns and removes the compressed bytes produced so far. */
  override drain(): Buffer {
    return super.drain() as Buffer
  }

  /** Completes the workbook and returns the remaining bytes. */
  override finish(): Buffer {
    return super.finish() as Buffer
  }
}

/**
 * Resolves immediately: the native engine needs no initialization. Exists so that code shared
 * with browsers, where `init()` loads the WebAssembly engine, runs unchanged.
 */
export function init(): Promise<void> {
  return Promise.resolve()
}

/**
 * Returns a readable byte stream of an XLSX file, produced while `source` is consumed lazily.
 *
 * Pipe it into an HTTP response or any writable stream. Backpressure from the destination pauses
 * the source, so memory stays constant regardless of the number of rows.
 */
export function stream(source: Source, options: ExportOptions = {}): Readable {
  return Readable.from(chunks(source, options), { objectMode: false })
}

/** Like {@link stream}, as a web `ReadableStream` for `Response` bodies. */
export function webStream(source: Source, options: ExportOptions = {}): ReadableStream<Uint8Array> {
  return Readable.toWeb(stream(source, options)) as ReadableStream<Uint8Array>
}

/** Builds a whole XLSX file in memory. */
export async function toBuffer(source: Source, options: ExportOptions = {}): Promise<Buffer> {
  const parts: Buffer[] = []
  for await (const chunk of chunks(source, options)) parts.push(chunk as Buffer)
  return Buffer.concat(parts)
}

/** Builds a whole XLSX file in memory from a synchronous source. */
export function toBufferSync(source: Iterable<Row | RecordLike>, options: ExportOptions = {}): Buffer {
  return buildSync(source, options) as Buffer
}

/** Writes an XLSX file to `path` without holding it in memory. */
export async function writeFile(path: string, source: Source, options: ExportOptions = {}): Promise<void> {
  await pipeline(stream(source, options), createWriteStream(path))
}

/** A workbook to read: a file path or `file:` URL, its bytes, or a stream of its bytes. */
export type WorkbookSource = string | URL | BytesSource

/** A path, `file:` URL or bytes: the sources {@link openWorkbookSync} accepts. */
export type SyncWorkbookSource = string | URL | Uint8Array | ArrayBuffer | ArrayBufferView

function openPath(path: string | URL, options: ReadOptions): Workbook {
  const file = typeof path === 'string' ? path : fileURLToPath(path)
  const native = guard(() =>
    NativeWorkbook.openPath(file, { maxSharedStringsBytes: options.maxSharedStringsBytes, includeEmptyRows: options.includeEmptyRows }),
  )
  return new Workbook(native, options)
}

/**
 * Opens an XLSX workbook for reading, synchronously. A path is read from disk as rows are
 * requested; bytes are copied into the engine once. See {@link openWorkbook} for streams.
 */
export function openWorkbookSync(source: SyncWorkbookSource, options: ReadOptions = {}): Workbook {
  if (typeof source === 'string' || source instanceof URL) return openPath(source, options)
  const bytes = bytesOf(source)
  if (bytes === undefined) throw new XlsxError('openWorkbookSync takes a path, a file: URL or bytes; use openWorkbook for streams')
  return openBytes(bytes, options)
}

/**
 * Opens an XLSX workbook for reading. A path is read from disk as rows are requested. Bytes, a
 * `Blob`, a web `ReadableStream` or a Node.js `Readable` (such as an upload) are held in memory
 * once, because the XLSX directory sits at the end of the file.
 *
 * ```ts
 * using workbook = await openWorkbook('orders.xlsx')
 * for await (const row of workbook.rows('Orders')) console.log(row)
 * ```
 */
export async function openWorkbook(source: WorkbookSource, options: ReadOptions = {}): Promise<Workbook> {
  if (typeof source === 'string' || source instanceof URL) return openPath(source, options)
  return openBytes(await collectBytes(source), options)
}

/**
 * Yields the rows of a sheet (name or 0-based index, default the first) as arrays: element `i`
 * is the value in column `i + 1`. Missing rows are yielded as `[]` unless `skipEmpty` is set.
 * Rows are parsed on the libuv thread pool, so the event loop stays free.
 */
export async function* iterRows(
  source: WorkbookSource,
  sheet: SheetRef = 0,
  options: ReadOptions & RowsOptions = {},
): AsyncGenerator<ReadValue[], void, undefined> {
  yield* iterRowsOf(await openWorkbook(source, options), sheet, options)
}

/** Reads all rows of a sheet into an array. See {@link iterRows}. */
export async function readRows(source: WorkbookSource, sheet: SheetRef = 0, options: ReadOptions & RowsOptions = {}): Promise<ReadValue[][]> {
  const rows: ReadValue[][] = []
  for await (const row of iterRows(source, sheet, options)) rows.push(row)
  return rows
}

/**
 * Yields one record per row below the header row (`headerRow`, default 1), keyed by its texts.
 * Empty header cells are named `column_<n>`, repeated names get `_2`, `_3`, …; rows without values
 * are skipped and missing cells are `null`.
 */
export async function* iterRecords(
  source: WorkbookSource,
  sheet: SheetRef = 0,
  options: ReadOptions & RecordsOptions = {},
): AsyncGenerator<ReadRecord, void, undefined> {
  yield* iterRecordsOf(await openWorkbook(source, options), sheet, options)
}

/** Reads all records of a sheet into an array. See {@link iterRecords}. */
export async function readRecords(
  source: WorkbookSource,
  sheet: SheetRef = 0,
  options: ReadOptions & RecordsOptions = {},
): Promise<ReadRecord[]> {
  const records: ReadRecord[] = []
  for await (const record of iterRecords(source, sheet, options)) records.push(record)
  return records
}
