/**
 * Browser entry: the hypaxel API on the WebAssembly engine. Also used by runtimes without native
 * modules (Deno, Bun, edge workers) through the `default` export condition.
 */
import loadWasm, { WasmWorkbook, WasmWriter, columnName as wasmColumnName, initSync as loadWasmSync } from './wasm/hypaxel.js'
import {
  CONTENT_TYPE,
  type ExportOptions,
  type RecordLike,
  type Row,
  type Source,
  XlsxError,
  buildSync,
  chunks,
  setEngine,
  setMissingEngineMessage,
} from './core.js'
import {
  type BytesSource,
  type ReadOptions,
  type ReadRecord,
  type ReadValue,
  type RecordsOptions,
  type RowsOptions,
  type SheetRef,
  type Workbook,
  bytesOf,
  collectBytes,
  iterRecordsOf,
  iterRowsOf,
  openBytes,
} from './read.js'

export {
  CONTENT_TYPE,
  MAX_COLUMNS,
  MAX_ROWS,
  Formula,
  Style,
  Styled,
  Worksheet,
  Writer,
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
} from './core.js'
export {
  SheetRecords,
  SheetRows,
  Workbook,
  type BytesSource,
  type ReadOptions,
  type ReadRecord,
  type ReadValue,
  type RecordsOptions,
  type RowsOptions,
  type SheetInfo,
  type SheetRef,
} from './read.js'

/** Where to load the WebAssembly module from: a URL, a `fetch` response, its bytes or a compiled module. */
export type WasmSource = RequestInfo | URL | Response | BufferSource | WebAssembly.Module

setMissingEngineMessage(
  'the WebAssembly engine is not loaded; await init() first, or use the async helpers (toBuffer, toBlob, webStream), which load it',
)

const encoder = new TextEncoder()
const decoder = new TextDecoder('utf-8', { ignoreBOM: true })
let loaded = false
let loading: Promise<void> | undefined

function install(): void {
  setEngine({
    createWriter: (options) => new WasmWriter(options),
    columnName: wasmColumnName,
    alloc: (size) => new Uint8Array(size),
    writeUtf8: (buffer, offset, value) => encoder.encodeInto(value, buffer.subarray(offset)).written,
    openWorkbook: (bytes, options) => WasmWorkbook.openBytes(bytes, options),
    decodeUtf8: (buffer, start, end) => decoder.decode(buffer.subarray(start, end)),
  })
  loaded = true
}

/**
 * Loads the WebAssembly engine. Call it once before constructing a {@link Writer} or calling
 * {@link toBufferSync}; the async helpers call it themselves.
 *
 * By default the module is fetched from `hypaxel_bg.wasm` next to this file, which bundlers such as
 * Vite and webpack resolve and copy. Pass `source` to load it from elsewhere, e.g. a CDN URL or
 * bytes you fetched yourself. Calling it again after success does nothing.
 */
export function init(source?: WasmSource): Promise<void> {
  if (loaded) return Promise.resolve()
  loading ??= loadWasm(source === undefined ? undefined : { module_or_path: source }).then(
    () => install(),
    (error: unknown) => {
      loading = undefined
      throw new XlsxError(`failed to load the WebAssembly engine: ${error instanceof Error ? error.message : String(error)}`)
    },
  )
  return loading
}

/**
 * Loads the WebAssembly engine synchronously from its bytes or a compiled module. Browsers limit
 * synchronous compilation on the main thread, so prefer {@link init} there; this suits workers and
 * server runtimes that read the file from disk.
 */
export function initSync(source: BufferSource | WebAssembly.Module): void {
  if (loaded) return
  try {
    loadWasmSync({ module: source })
  } catch (error) {
    throw new XlsxError(`failed to load the WebAssembly engine: ${error instanceof Error ? error.message : String(error)}`)
  }
  install()
}

function concat(parts: readonly Uint8Array[]): Uint8Array {
  let length = 0
  for (const part of parts) length += part.length
  const out = new Uint8Array(length)
  let offset = 0
  for (const part of parts) {
    out.set(part, offset)
    offset += part.length
  }
  return out
}

/**
 * Returns a web `ReadableStream` of an XLSX file, produced while `source` is consumed lazily.
 * Chunks are produced only as the consumer reads, so memory stays bounded; pass it to a
 * `Response`, a service worker or a file system writable stream.
 */
export function webStream(source: Source, options: ExportOptions = {}): ReadableStream<Uint8Array> {
  let iterator: AsyncGenerator<Uint8Array, void, undefined> | undefined
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        if (iterator === undefined) {
          await init()
          iterator = chunks(source, options)
        }
        const next = await iterator.next()
        if (next.done) controller.close()
        else controller.enqueue(next.value)
      } catch (error) {
        controller.error(error)
      }
    },
    async cancel() {
      await iterator?.return(undefined)
    },
  })
}

/** Builds a whole XLSX file in memory. */
export async function toBuffer(source: Source, options: ExportOptions = {}): Promise<Uint8Array> {
  await init()
  const parts: Uint8Array[] = []
  for await (const chunk of chunks(source, options)) parts.push(chunk)
  return parts.length === 1 ? parts[0]! : concat(parts)
}

/** Builds a whole XLSX file as a `Blob` with the XLSX content type, ready for a download link. */
export async function toBlob(source: Source, options: ExportOptions = {}): Promise<Blob> {
  await init()
  const parts: BlobPart[] = []
  for await (const chunk of chunks(source, options)) parts.push(chunk as Uint8Array<ArrayBuffer>)
  return new Blob(parts, { type: CONTENT_TYPE })
}

/** Builds a whole XLSX file in memory from a synchronous source. Requires a prior {@link init}. */
export function toBufferSync(source: Iterable<Row | RecordLike>, options: ExportOptions = {}): Uint8Array {
  return buildSync(source, options)
}

/** A workbook to read: its bytes, a `Blob` or `File`, a `Response`, or a stream of its bytes. */
export type WorkbookSource = BytesSource

/**
 * Opens an XLSX workbook held in memory, synchronously. Requires a prior {@link init}. The bytes
 * are copied into WebAssembly memory once; rows are then decoded as they are requested.
 */
export function openWorkbookSync(source: Uint8Array | ArrayBuffer | ArrayBufferView, options: ReadOptions = {}): Workbook {
  const bytes = bytesOf(source)
  if (bytes === undefined) throw new XlsxError('openWorkbookSync takes bytes; use openWorkbook for a Blob, Response or stream')
  return openBytes(bytes, options)
}

/**
 * Opens an XLSX workbook, e.g. a `File` from an `<input type="file">` or a `fetch` `Response`.
 * Loads the WebAssembly engine if needed. The file is held in memory once.
 */
export async function openWorkbook(source: WorkbookSource, options: ReadOptions = {}): Promise<Workbook> {
  await init()
  return openBytes(await collectBytes(source), options)
}

/**
 * Yields the rows of a sheet (name or 0-based index, default the first) as arrays: element `i`
 * is the value in column `i + 1`. Missing rows are yielded as `[]` unless `skipEmpty` is set.
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
