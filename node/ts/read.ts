/// <reference lib="esnext.disposable" preserve="true" />
/**
 * Platform-neutral reading API, shared by the Node.js entry (native engine) and the browser entry
 * (WebAssembly engine). It uses no Node.js APIs.
 */
import { type EngineRows, type EngineWorkbook, XlsxError, currentEngine, guard } from './core.js'

/**
 * A cell value as read: text, a number, a boolean, a `Date` for cells formatted as a date, date
 * and time or time of day, elapsed milliseconds (a number) for `[h]:mm` durations, an error such
 * as `'#N/A'` as text, or `null` for an empty cell.
 */
export type ReadValue = string | number | boolean | Date | null

/** A sheet by name (matched exactly, then ignoring case) or 0-based index (negative counts from the end). */
export type SheetRef = string | number

export interface SheetInfo {
  readonly name: string
  readonly visible: 'visible' | 'hidden' | 'veryHidden'
  /** A chart sheet has no cells. */
  readonly kind: 'worksheet' | 'chartsheet' | 'other'
}

export interface ReadOptions {
  /**
   * Largest amount of text the shared string table may hold, in bytes (default 1 GiB). The table is
   * the one part of a workbook held in memory while rows stream; lower it for untrusted uploads.
   */
  maxSharedStringsBytes?: number
  /**
   * Excel stores dates without a time zone. `'local'` (default) returns a `Date` with that wall-clock
   * time in the local time zone, `'utc'` in UTC. Matches the writer's `dates` option.
   */
  dates?: 'local' | 'utc'
}

export interface RowsOptions {
  /**
   * Leave out rows without values. By default they are returned as `[]`, so the row at index `i`
   * is Excel row `i + 1`.
   */
  skipEmpty?: boolean
}

export interface RecordsOptions {
  /** 1-based row that holds the column names. Default 1. */
  headerRow?: number
}

/** A sheet's rows as records, keyed by the header row. */
export type ReadRecord = Record<string, ReadValue>

const TAG_ROW = 1
const TAG_SKIP = 2
const TAG_NUMBER = 3
const TAG_STRING = 4
const TAG_TRUE = 5
const TAG_FALSE = 6
const TAG_DATE = 7
const TAG_DURATION = 8
const TAG_ERROR = 9
const TAG_SHARED_NEW = 10
const TAG_SHARED = 11
const TAG_COPY = 12

/** Must equal `STRING_SLOTS` in `node/engine/src/read.rs`. */
const STRING_SLOTS = 4096
const ASCII_FAST_PATH_LENGTH = 24

function localDate(wallClock: number): Date {
  const utc = new Date(wallClock)
  return new Date(
    utc.getUTCFullYear(),
    utc.getUTCMonth(),
    utc.getUTCDate(),
    utc.getUTCHours(),
    utc.getUTCMinutes(),
    utc.getUTCSeconds(),
    utc.getUTCMilliseconds(),
  )
}

/**
 * The rows of one sheet, read lazily. Iterate it with `for...of` (synchronous) or `for await...of`
 * (on Node.js, batches are then read on the libuv thread pool and the event loop stays free). Each
 * row is an array whose element `i` is the value in column `i + 1`; it ends at the row's last value.
 *
 * Memory stays bounded by the workbook's shared string table and one batch of rows, however long
 * the sheet. Stopping early (`break`) releases the sheet.
 */
export class SheetRows implements IterableIterator<ReadValue[]>, AsyncIterable<ReadValue[]> {
  /** Same as `close()`, for `using`. Present where the runtime defines `Symbol.dispose`. */
  declare [Symbol.dispose]: () => void
  readonly #decodeUtf8: (buffer: Uint8Array, start: number, end: number) => string
  readonly #utc: boolean
  readonly #skipEmpty: boolean
  readonly #strings: string[] = new Array<string>(STRING_SLOTS)
  #rows: EngineRows | undefined
  #batch: Uint8Array = new Uint8Array(0)
  #view: DataView = new DataView(this.#batch.buffer)
  #position = 0
  #emitted = 0
  #pending: ReadValue[] | undefined
  #pendingNumber = 0
  #reading = false

  /** @internal */
  constructor(rows: EngineRows, utc: boolean, skipEmpty: boolean) {
    this.#rows = rows
    this.#utc = utc
    this.#skipEmpty = skipEmpty
    this.#decodeUtf8 = currentEngine().decodeUtf8
  }

  [Symbol.iterator](): this {
    return this
  }

  [Symbol.asyncIterator](): AsyncIterator<ReadValue[]> {
    return {
      next: () => this.nextAsync(),
      return: async () => this.return(),
    }
  }

  /** The next row, reading a batch on the calling thread when needed. */
  next(): IteratorResult<ReadValue[], undefined> {
    this.#idle()
    for (;;) {
      const row = this.#take()
      if (row !== undefined) return { done: false, value: row }
      const rows = this.#rows
      if (rows === undefined) return { done: true, value: undefined }
      this.#load(rows, this.#fetch(() => rows.next()))
    }
  }

  /** The next row, reading a batch off the main thread where the engine can. */
  async nextAsync(): Promise<IteratorResult<ReadValue[], undefined>> {
    this.#idle()
    for (;;) {
      const row = this.#take()
      if (row !== undefined) return { done: false, value: row }
      const rows = this.#rows
      if (rows === undefined) return { done: true, value: undefined }
      if (rows.nextAsync === undefined) {
        this.#load(rows, this.#fetch(() => rows.next()))
        continue
      }
      this.#reading = true
      let batch: Uint8Array | null | undefined
      try {
        batch = await rows.nextAsync()
      } catch (error) {
        this.#rows = undefined
        throw error instanceof XlsxError ? error : new XlsxError(error instanceof Error ? error.message : String(error))
      } finally {
        this.#reading = false
      }
      this.#load(rows, batch)
    }
  }

  /** Stops reading and releases the sheet. */
  return(): IteratorResult<ReadValue[], undefined> {
    this.close()
    return { done: true, value: undefined }
  }

  /** Stops reading and releases the sheet. */
  close(): void {
    const rows = this.#rows
    this.#rows = undefined
    this.#pending = undefined
    this.#batch = new Uint8Array(0)
    this.#view = new DataView(this.#batch.buffer)
    this.#strings.fill(undefined as unknown as string)
    this.#position = 0
    if (rows !== undefined && !this.#reading) rows.close()
  }

  /** Reads the remaining rows into an array. */
  toArray(): ReadValue[][] {
    return Array.from(this)
  }

  #idle(): void {
    if (this.#reading) throw new XlsxError('the next rows are already being read; await the pending call first')
  }

  #fetch(read: () => Uint8Array | null | undefined): Uint8Array | null | undefined {
    try {
      return guard(read)
    } catch (error) {
      this.#rows = undefined
      throw error
    }
  }

  #load(rows: EngineRows, batch: Uint8Array | null | undefined): void {
    if (this.#rows !== rows) {
      rows.close()
      return
    }
    if (batch === null || batch === undefined) {
      this.#rows = undefined
      return
    }
    this.#batch = batch
    this.#view = new DataView(batch.buffer, batch.byteOffset, batch.byteLength)
    this.#position = 0
  }

  #take(): ReadValue[] | undefined {
    if (this.#pending === undefined) {
      if (this.#position >= this.#batch.length) return undefined
      this.#decodeRow()
    }
    const number = this.#pendingNumber
    if (this.#skipEmpty || this.#emitted + 1 >= number) {
      const row = this.#pending
      this.#pending = undefined
      this.#emitted = number
      return row
    }
    this.#emitted++
    return []
  }

  #decodeRow(): void {
    const buffer = this.#batch
    const view = this.#view
    let p = this.#position
    if (buffer[p] !== TAG_ROW) throw new XlsxError('the engine returned a corrupt row batch')
    const number = view.getUint32(p + 1, true)
    const width = view.getUint32(p + 5, true)
    p += 9
    const row: ReadValue[] = []
    while (row.length < width) {
      switch (buffer[p++]) {
        case TAG_SKIP: {
          let count = view.getUint32(p, true)
          p += 4
          while (count-- > 0) row.push(null)
          break
        }
        case TAG_NUMBER:
        case TAG_DURATION:
          row.push(view.getFloat64(p, true))
          p += 8
          break
        case TAG_STRING:
        case TAG_ERROR: {
          const length = view.getUint32(p, true)
          p += 4
          row.push(this.#text(buffer, p, length))
          p += length
          break
        }
        case TAG_SHARED_NEW: {
          const index = view.getUint32(p, true)
          const length = view.getUint32(p + 4, true)
          p += 8
          const text = this.#text(buffer, p, length)
          p += length
          this.#strings[index % STRING_SLOTS] = text
          row.push(text)
          break
        }
        case TAG_SHARED:
          row.push(this.#strings[view.getUint32(p, true) % STRING_SLOTS]!)
          p += 4
          break
        case TAG_COPY:
          row.push(row[view.getUint32(p, true)]!)
          p += 4
          break
        case TAG_TRUE:
          row.push(true)
          break
        case TAG_FALSE:
          row.push(false)
          break
        case TAG_DATE: {
          const wallClock = view.getFloat64(p, true)
          p += 8
          row.push(this.#utc ? new Date(wallClock) : localDate(wallClock))
          break
        }
        default:
          throw new XlsxError('the engine returned a corrupt row batch')
      }
    }
    this.#position = p
    this.#pending = row
    this.#pendingNumber = number
  }

  #text(buffer: Uint8Array, start: number, length: number): string {
    if (length <= ASCII_FAST_PATH_LENGTH) {
      let text = ''
      for (let i = start, end = start + length; i < end; i++) {
        const code = buffer[i]!
        if (code > 127) return this.#decodeUtf8(buffer, start, start + length)
        text += String.fromCharCode(code)
      }
      return text
    }
    return this.#decodeUtf8(buffer, start, start + length)
  }
}

function headerName(value: ReadValue, column: number): string {
  if (value === null || value === '') return `column_${column}`
  return value instanceof Date ? value.toISOString() : String(value)
}

/**
 * Builds records from rows. Empty header cells are named `column_<n>`, repeated names get `_2`,
 * `_3`, … and cells beyond the header get `column_<n>` keys. Rows without values are skipped.
 */
class RecordBuilder {
  #names: string[] | undefined
  #number = 0

  constructor(private readonly headerRow: number) {
    if (!Number.isInteger(headerRow) || headerRow < 1) throw new XlsxError('headerRow is a 1-based row number')
  }

  push(row: ReadValue[]): ReadRecord | undefined {
    if (++this.#number < this.headerRow) return undefined
    const names = this.#names
    if (names === undefined) {
      this.#names = this.#header(row)
      return undefined
    }
    if (row.length === 0) return undefined
    if (row.length > names.length) this.#extend(names, row.length)
    const record: ReadRecord = {}
    for (let i = 0; i < names.length; i++) {
      const name = names[i]!
      const value = row[i] ?? null
      if (name === '__proto__') Object.defineProperty(record, name, { value, enumerable: true, writable: true, configurable: true })
      else record[name] = value
    }
    return record
  }

  #header(row: ReadValue[]): string[] {
    const names: string[] = []
    const taken = new Set<string>()
    const suffixes = new Map<string, number>()
    row.forEach((value, i) => {
      const base = headerName(value, i + 1)
      let name = base
      if (taken.has(name)) {
        let suffix = suffixes.get(base) ?? 1
        do name = `${base}_${++suffix}`
        while (taken.has(name))
        suffixes.set(base, suffix)
      }
      taken.add(name)
      names.push(name)
    })
    return names
  }

  #extend(names: string[], width: number): void {
    const taken = new Set(names)
    for (let i = names.length + 1; i <= width; i++) {
      let name = `column_${i}`
      while (taken.has(name)) name += '_'
      taken.add(name)
      names.push(name)
    }
  }
}

/** A sheet's rows as records keyed by the header row. Iterate it synchronously or with `for await`. */
export class SheetRecords implements IterableIterator<ReadRecord>, AsyncIterable<ReadRecord> {
  /** Same as `close()`, for `using`. Present where the runtime defines `Symbol.dispose`. */
  declare [Symbol.dispose]: () => void
  readonly #rows: SheetRows
  readonly #builder: RecordBuilder

  /** @internal */
  constructor(rows: SheetRows, headerRow: number) {
    this.#builder = new RecordBuilder(headerRow)
    this.#rows = rows
  }

  [Symbol.iterator](): this {
    return this
  }

  [Symbol.asyncIterator](): AsyncIterator<ReadRecord> {
    return {
      next: () => this.nextAsync(),
      return: async () => this.return(),
    }
  }

  next(): IteratorResult<ReadRecord, undefined> {
    for (let next = this.#rows.next(); !next.done; next = this.#rows.next()) {
      const record = this.#builder.push(next.value)
      if (record !== undefined) return { done: false, value: record }
    }
    return { done: true, value: undefined }
  }

  async nextAsync(): Promise<IteratorResult<ReadRecord, undefined>> {
    for (let next = await this.#rows.nextAsync(); !next.done; next = await this.#rows.nextAsync()) {
      const record = this.#builder.push(next.value)
      if (record !== undefined) return { done: false, value: record }
    }
    return { done: true, value: undefined }
  }

  return(): IteratorResult<ReadRecord, undefined> {
    this.#rows.close()
    return { done: true, value: undefined }
  }

  /** Stops reading and releases the sheet. */
  close(): void {
    this.#rows.close()
  }

  /** Reads the remaining records into an array. */
  toArray(): ReadRecord[] {
    return Array.from(this)
  }
}

const VISIBILITY = new Set(['visible', 'hidden', 'veryHidden'])
const KINDS = new Set(['worksheet', 'chartsheet', 'other'])

/**
 * An open XLSX workbook. Obtain one from `openWorkbook` or `openWorkbookSync`, read sheets with
 * {@link rows} or {@link records}, and {@link close} it when done (or use `using`).
 */
export class Workbook {
  /** Same as `close()`, for `using`. Present where the runtime defines `Symbol.dispose`. */
  declare [Symbol.dispose]: () => void
  /** The sheets in workbook order, including hidden sheets and chart sheets. */
  readonly sheets: readonly SheetInfo[]
  /** True if the workbook uses the 1904 date system. Dates are converted either way. */
  readonly date1904: boolean
  #engine: EngineWorkbook | undefined
  readonly #utc: boolean

  /** @internal */
  constructor(engine: EngineWorkbook, options: ReadOptions = {}) {
    this.#engine = engine
    this.#utc = options.dates === 'utc'
    this.sheets = Object.freeze(
      guard(() => engine.sheets()).map((s) =>
        Object.freeze({
          name: s.name,
          visible: (VISIBILITY.has(s.visible) ? s.visible : 'visible') as SheetInfo['visible'],
          kind: (KINDS.has(s.kind) ? s.kind : 'other') as SheetInfo['kind'],
        }),
      ),
    )
    this.date1904 = guard(() => engine.date1904)
  }

  /** The sheet names in workbook order. */
  get sheetNames(): string[] {
    return this.sheets.map((s) => s.name)
  }

  /**
   * The rows of a sheet (default: the first). See {@link SheetRows}. Several sheets, or the same
   * sheet twice, can be read at the same time.
   */
  rows(sheet: SheetRef = 0, options: RowsOptions = {}): SheetRows {
    const engine = this.#open()
    const index = this.#index(sheet)
    return new SheetRows(
      guard(() => engine.rows(index)),
      this.#utc,
      options.skipEmpty ?? false,
    )
  }

  /**
   * The rows below the header row as records keyed by its texts. Empty header cells are named
   * `column_<n>`, repeated names get `_2`, `_3`, …, and rows without values are skipped.
   */
  records(sheet: SheetRef = 0, options: RecordsOptions = {}): SheetRecords {
    const headerRow = options.headerRow ?? 1
    return new SheetRecords(this.rows(sheet), headerRow)
  }

  /** Releases the workbook. Rows already obtained can still be read. */
  close(): void {
    const engine = this.#engine
    this.#engine = undefined
    engine?.close()
  }

  #open(): EngineWorkbook {
    if (this.#engine === undefined) throw new XlsxError('the workbook is closed')
    return this.#engine
  }

  #index(sheet: SheetRef): number {
    const sheets = this.sheets
    const count = sheets.length
    let index: number
    if (typeof sheet === 'number') {
      if (!Number.isInteger(sheet)) throw new XlsxError(`sheet index must be an integer, not ${sheet}`)
      index = sheet < 0 ? sheet + count : sheet
      if (index < 0 || index >= count) throw new XlsxError(`no worksheet at index ${sheet}; the workbook has ${count}`)
    } else if (typeof sheet === 'string') {
      index = sheets.findIndex((s) => s.name === sheet)
      if (index < 0) {
        const lower = sheet.toLowerCase()
        index = sheets.findIndex((s) => s.name.toLowerCase() === lower)
      }
      if (index < 0) throw new XlsxError(`no worksheet ${JSON.stringify(sheet)}; the workbook has ${JSON.stringify(this.sheetNames)}`)
    } else {
      throw new XlsxError('sheet must be a name or a 0-based index')
    }
    if (sheets[index]!.kind === 'chartsheet') throw new XlsxError(`${JSON.stringify(sheets[index]!.name)} is a chart sheet and has no cells`)
    return index
  }
}

// Installed only where the runtime has Symbol.dispose; the classes declare the member for TypeScript.
const dispose = (Symbol as { dispose?: symbol }).dispose
if (dispose !== undefined) {
  for (const type of [Workbook, SheetRows, SheetRecords]) {
    Object.defineProperty(type.prototype, dispose, {
      value(this: { close(): void }) {
        this.close()
      },
      configurable: true,
      writable: true,
    })
  }
}

/** Bytes of a workbook, or something that yields them. */
export type BytesSource =
  | Uint8Array
  | ArrayBuffer
  | ArrayBufferView
  | { arrayBuffer(): Promise<ArrayBuffer> }
  | ReadableStream<Uint8Array>
  | AsyncIterable<Uint8Array>

/** @internal Views `source` as bytes without copying, or returns undefined. */
export function bytesOf(source: unknown): Uint8Array | undefined {
  if (source instanceof Uint8Array) return source
  if (source instanceof ArrayBuffer) return new Uint8Array(source)
  if (ArrayBuffer.isView(source)) return new Uint8Array(source.buffer, source.byteOffset, source.byteLength)
  return undefined
}

function concat(parts: readonly Uint8Array[], length: number): Uint8Array {
  if (parts.length === 1) return parts[0]!
  const out = new Uint8Array(length)
  let offset = 0
  for (const part of parts) {
    out.set(part, offset)
    offset += part.length
  }
  return out
}

/** @internal Reads a {@link BytesSource} into memory. */
export async function collectBytes(source: BytesSource): Promise<Uint8Array> {
  const bytes = bytesOf(source)
  if (bytes !== undefined) return bytes
  if (typeof (source as { arrayBuffer?: unknown }).arrayBuffer === 'function') {
    return new Uint8Array(await (source as { arrayBuffer(): Promise<ArrayBuffer> }).arrayBuffer())
  }
  const parts: Uint8Array[] = []
  let length = 0
  const add = (chunk: unknown): void => {
    const part = bytesOf(chunk)
    if (part === undefined) throw new XlsxError(`a workbook stream must yield bytes, not ${typeof chunk}`)
    parts.push(part)
    length += part.length
  }
  if (typeof (source as ReadableStream<Uint8Array>).getReader === 'function') {
    const reader = (source as ReadableStream<Uint8Array>).getReader()
    try {
      for (let next = await reader.read(); !next.done; next = await reader.read()) add(next.value)
    } finally {
      reader.releaseLock()
    }
    return concat(parts, length)
  }
  if (typeof (source as AsyncIterable<Uint8Array>)[Symbol.asyncIterator] === 'function') {
    for await (const chunk of source as AsyncIterable<Uint8Array>) add(chunk)
    return concat(parts, length)
  }
  throw new XlsxError('the workbook source must be bytes, a Blob, a stream or an async iterable of bytes')
}

/** @internal Opens a workbook held in memory on the current engine. */
export function openBytes(bytes: Uint8Array, options: ReadOptions): Workbook {
  const engine = currentEngine()
  const native = guard(() => engine.openWorkbook(bytes, { maxSharedStringsBytes: options.maxSharedStringsBytes }))
  return new Workbook(native, options)
}

/** @internal Yields the rows of one sheet and closes the workbook. */
export async function* iterRowsOf(
  workbook: Workbook,
  sheet: SheetRef,
  options: RowsOptions,
): AsyncGenerator<ReadValue[], void, undefined> {
  try {
    const rows = workbook.rows(sheet, options)
    try {
      yield* rows
    } finally {
      rows.close()
    }
  } finally {
    workbook.close()
  }
}

/** @internal Yields the records of one sheet and closes the workbook. */
export async function* iterRecordsOf(
  workbook: Workbook,
  sheet: SheetRef,
  options: RecordsOptions,
): AsyncGenerator<ReadRecord, void, undefined> {
  try {
    const records = workbook.records(sheet, options)
    try {
      yield* records
    } finally {
      records.close()
    }
  } finally {
    workbook.close()
  }
}
