import { createWriteStream } from 'node:fs'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import type { ReadableStream } from 'node:stream/web'

import { NativeWriter, columnName as nativeColumnName } from '../binding'

/** MIME type of an `.xlsx` file, for the `Content-Type` response header. */
export const CONTENT_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
export const MAX_ROWS = 1_048_576
export const MAX_COLUMNS = 16_384

/** Raised when a workbook cannot be written. */
export class XlsxError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'XlsxError'
  }
}

/** A formula such as `new Formula('SUM(A1:A9)')` (leading `=` optional). Excel computes it on open. */
export class Formula {
  constructor(readonly formula: string) {}
}

/** A cell format created by {@link Writer.addStyle}. Only valid for the writer that created it. */
export class Style {
  /** @internal */
  constructor(
    /** @internal */ readonly id: number,
    /** @internal */ readonly owner: object,
  ) {}
}

/** A value with its own style, e.g. `new Styled(1234.5, money)`. */
export class Styled {
  constructor(
    readonly value: PlainValue,
    readonly style: Style,
  ) {}
}

export type PlainValue = string | number | boolean | bigint | Date | Formula | null | undefined
export type CellValue = PlainValue | Styled
export type Row = Iterable<CellValue>
export type RecordLike = Readonly<Record<string, unknown>>

export type BorderStyle =
  | 'none'
  | 'thin'
  | 'medium'
  | 'dashed'
  | 'dotted'
  | 'thick'
  | 'double'
  | 'hair'
  | 'mediumDashed'
  | 'dashDot'
  | 'mediumDashDot'
  | 'dashDotDot'
  | 'mediumDashDotDot'
  | 'slantDashDot'

export interface StyleOptions {
  bold?: boolean
  italic?: boolean
  underline?: boolean
  strikethrough?: boolean
  fontName?: string
  fontSize?: number
  /** `'#RRGGBB'` or `'#AARRGGBB'`. */
  fontColor?: string
  /** Solid fill color, `'#RRGGBB'` or `'#AARRGGBB'`. */
  fill?: string
  /** Line style for all four borders. */
  border?: BorderStyle
  borderColor?: string
  borderBottom?: BorderStyle
  align?: 'general' | 'left' | 'center' | 'right' | 'fill' | 'justify' | 'centerContinuous' | 'distributed'
  valign?: 'bottom' | 'top' | 'center' | 'justify' | 'distributed'
  wrap?: boolean
  indent?: number
  rotation?: number
  /** Excel number format, e.g. `'#,##0.00'` or `'yyyy-mm-dd'`. */
  numFormat?: string
}

export interface WriterOptions {
  /** Deflate level 0-9. Default 1, which is the fastest level that still compresses well. */
  compressionLevel?: number
  /** Store strings in a shared table instead of inline. Smaller files for repeated text, more memory. */
  sharedStrings?: boolean
  /** Compressed bytes buffered before they become available to {@link Writer.drain}. Default 65536. */
  flushThreshold?: number
  /** Use the 1904 date system. */
  date1904?: boolean
  /**
   * Write a reference such as `B7` on every cell. The default omits references that follow from
   * the cell's position, which is valid and smaller, but some readers (ExcelJS) require them.
   */
  cellReferences?: boolean
  /**
   * Excel has no time zones, so a `Date` is written as a wall-clock time: `'local'` (default) uses
   * the process time zone, `'utc'` uses UTC.
   */
  dates?: 'local' | 'utc'
}

export interface WorksheetOptions {
  freezeRows?: number
  freezeColumns?: number
  /** Column widths in characters, from column A. */
  columnWidths?: readonly number[]
  hidden?: boolean
  showGridLines?: boolean
  tabColor?: string
}

export interface RowOptions {
  /** Row height in points. */
  height?: number
  style?: Style
}

const TAG_END_ROW = 0
const TAG_BLANK = 1
const TAG_NUMBER = 2
const TAG_STRING = 3
const TAG_TRUE = 4
const TAG_FALSE = 5
const TAG_DATE = 6
const TAG_FORMULA = 7
const TAG_STYLE = 8
const TAG_ROW_STYLE = 9
const TAG_SKIP = 10

const ENCODER_CAPACITY = 256 * 1024
const ENCODER_FLUSH_AT = 128 * 1024
const ASCII_FAST_PATH_LENGTH = 32
const MS_PER_MINUTE = 60_000

function guard<T>(operation: () => T): T {
  try {
    return operation()
  } catch (error) {
    throw error instanceof XlsxError ? error : new XlsxError(error instanceof Error ? error.message : String(error))
  }
}

function describe(value: unknown): string {
  if (Array.isArray(value)) return 'an array'
  if (typeof value === 'object' && value !== null) return `an object (${value.constructor?.name ?? 'null prototype'})`
  return `a ${typeof value}`
}

/**
 * Encodes cells into a compact binary batch so that many rows reach native code in one call.
 * Crossing the JavaScript/native boundary per cell would dominate the cost of writing.
 */
class RowEncoder {
  private buffer = Buffer.allocUnsafe(ENCODER_CAPACITY)
  private position = 0

  constructor(
    private readonly native: NativeWriter,
    private readonly owner: object,
    private readonly utc: boolean,
  ) {}

  flush(): void {
    if (this.position === 0) return
    const length = this.position
    this.position = 0
    guard(() => this.native.writeBatch(this.buffer.subarray(0, length)))
  }

  row(values: Row, style: number): void {
    const mark = this.position
    try {
      if (style !== 0) this.tagged(TAG_ROW_STYLE, style)
      if (Array.isArray(values)) {
        for (let i = 0; i < values.length; i++) this.cell(values[i], 0)
      } else if (typeof values === 'string') {
        throw new TypeError('a row must be an iterable of values, not a string')
      } else {
        for (const value of values) this.cell(value, 0)
      }
    } catch (error) {
      this.position = mark
      throw error
    }
    this.endRow()
  }

  styledRow(values: Row, styles: readonly number[]): void {
    const mark = this.position
    try {
      let i = 0
      for (const value of values) this.cell(value, styles[i++] ?? 0)
    } catch (error) {
      this.position = mark
      throw error
    }
    this.endRow()
  }

  record(record: RecordLike, keys: readonly string[], styles: readonly number[]): void {
    const mark = this.position
    try {
      for (let i = 0; i < keys.length; i++) this.cell(record[keys[i]!] as CellValue, styles[i]!)
    } catch (error) {
      this.position = mark
      throw error
    }
    this.endRow()
  }

  cell(value: CellValue, style: number): void {
    if (typeof value === 'object' && value instanceof Styled) {
      style = this.styleId(value.style)
      value = value.value
    }
    if (style !== 0) this.tagged(TAG_STYLE, style)
    switch (typeof value) {
      case 'string':
        this.text(TAG_STRING, value)
        return
      case 'number':
        this.float(TAG_NUMBER, value)
        return
      case 'boolean':
        this.tag(value ? TAG_TRUE : TAG_FALSE)
        return
      case 'undefined':
        this.tag(TAG_BLANK)
        return
      case 'bigint':
        this.float(TAG_NUMBER, Number(value))
        return
      case 'object':
        if (value === null) {
          this.tag(TAG_BLANK)
        } else if (value instanceof Date) {
          const instant = value.getTime()
          this.float(TAG_DATE, this.utc ? instant : instant - value.getTimezoneOffset() * MS_PER_MINUTE)
        } else if (value instanceof Formula) {
          this.text(TAG_FORMULA, value.formula)
        } else {
          throw new TypeError(`cannot write ${describe(value)} into a single cell`)
        }
        return
      default:
        throw new TypeError(`cannot write ${describe(value)} into a single cell`)
    }
  }

  skip(count: number): void {
    this.tagged(TAG_SKIP, count)
  }

  endRow(): void {
    this.tag(TAG_END_ROW)
    if (this.position >= ENCODER_FLUSH_AT) this.flush()
  }

  styleId(style: Style | undefined): number {
    if (style === undefined) return 0
    if (style.owner !== this.owner) throw new XlsxError('the style does not belong to this writer')
    return style.id
  }

  private reserve(bytes: number): void {
    if (this.position + bytes <= this.buffer.length) return
    const grown = Buffer.allocUnsafe(Math.max(this.buffer.length * 2, this.position + bytes))
    this.buffer.copy(grown, 0, 0, this.position)
    this.buffer = grown
  }

  private tag(tag: number): void {
    this.reserve(1)
    this.buffer[this.position++] = tag
  }

  private tagged(tag: number, value: number): void {
    this.reserve(5)
    this.buffer[this.position] = tag
    this.buffer.writeUInt32LE(value, this.position + 1)
    this.position += 5
  }

  private float(tag: number, value: number): void {
    this.reserve(9)
    this.buffer[this.position] = tag
    this.buffer.writeDoubleLE(value, this.position + 1)
    this.position += 9
  }

  private text(tag: number, value: string): void {
    const length = value.length
    this.reserve(5 + length * 3)
    const buffer = this.buffer
    const start = this.position + 5
    let written = 0
    if (length <= ASCII_FAST_PATH_LENGTH) {
      for (; written < length; written++) {
        const code = value.charCodeAt(written)
        if (code > 127) break
        buffer[start + written] = code
      }
    }
    if (written !== length) written = buffer.write(value, start, 'utf8')
    buffer[this.position] = tag
    buffer.writeUInt32LE(written, this.position + 1)
    this.position = start + written
  }
}

interface WriterState {
  readonly native: NativeWriter
  readonly encoder: RowEncoder
  sheets: number
}

const states = new WeakMap<Writer, WriterState>()

/**
 * Forward-only XLSX writer that keeps its output in memory.
 *
 * Take the compressed bytes in chunks with {@link drain} while writing, and the rest with
 * {@link finish}. For most uses the helpers {@link stream}, {@link toBuffer} and {@link writeFile}
 * are simpler.
 */
export class Writer {
  readonly #state: WriterState

  constructor(options: WriterOptions = {}) {
    const native = guard(
      () =>
        new NativeWriter({
          compressionLevel: options.compressionLevel,
          sharedStrings: options.sharedStrings,
          flushThreshold: options.flushThreshold,
          date1904: options.date1904,
          cellReferences: options.cellReferences,
        }),
    )
    this.#state = { native, encoder: new RowEncoder(native, this, options.dates === 'utc'), sheets: 0 }
    states.set(this, this.#state)
  }

  /** Registers a cell format. Identical formats share one style. */
  addStyle(options: StyleOptions): Style {
    return new Style(
      guard(() => this.#state.native.addStyle(options)),
      this,
    )
  }

  /** Adds a worksheet and makes it the current one; the previous worksheet is completed. */
  addWorksheet(name: string, options: WorksheetOptions = {}): Worksheet {
    const state = this.#state
    state.encoder.flush()
    guard(() =>
      state.native.addWorksheet(name, {
        freezeRows: options.freezeRows,
        freezeColumns: options.freezeColumns,
        columnWidths: options.columnWidths === undefined ? undefined : [...options.columnWidths],
        hidden: options.hidden,
        showGridLines: options.showGridLines,
        tabColor: options.tabColor,
      }),
    )
    return new Worksheet(state, ++state.sheets, name)
  }

  /** Compressed bytes that {@link drain} would return now. */
  get bufferedBytes(): number {
    const state = this.#state
    state.encoder.flush()
    return guard(() => state.native.buffered())
  }

  /** Returns and removes the compressed bytes produced so far. */
  drain(): Buffer {
    const state = this.#state
    state.encoder.flush()
    return guard(() => state.native.drain())
  }

  /** Completes the workbook and returns the remaining bytes. */
  finish(): Buffer {
    const state = this.#state
    if (!state.native.finished) state.encoder.flush()
    return guard(() => state.native.finish())
  }

  /** True once {@link finish} has been called. */
  get finished(): boolean {
    return this.#state.native.finished
  }
}

/**
 * The worksheet being written. Obtained from {@link Writer.addWorksheet}; it can no longer be
 * written once another worksheet is added.
 */
export class Worksheet {
  readonly #state: WriterState
  readonly #index: number

  /** @internal */
  constructor(
    state: WriterState,
    index: number,
    readonly name: string,
  ) {
    this.#state = state
    this.#index = index
  }

  /** 1-based number of the last written row (0 before the first row). */
  get row(): number {
    const state = this.#current()
    state.encoder.flush()
    return guard(() => state.native.row())
  }

  /** Writes one row. `style` applies to every cell without its own. */
  writeRow(values: Row, style?: Style): void {
    const { encoder } = this.#current()
    encoder.row(values, encoder.styleId(style))
  }

  /** Writes many rows. Returns the number of rows written. */
  writeRows(rows: Iterable<Row>, style?: Style): number {
    const { encoder } = this.#current()
    const id = encoder.styleId(style)
    let count = 0
    for (const row of rows) {
      encoder.row(row, id)
      count++
    }
    return count
  }

  /**
   * Writes one row per object, taking the properties named by `keys` in order. Missing properties
   * become empty cells. `styles` optionally gives one style per key.
   */
  writeRecords(records: Iterable<RecordLike>, keys: readonly string[], styles?: readonly (Style | undefined)[]): number {
    const { encoder } = this.#current()
    const ids = keys.map((_, i) => encoder.styleId(styles?.[i]))
    let count = 0
    for (const record of records) {
      encoder.record(record, keys, ids)
      count++
    }
    return count
  }

  /** Writes a single value as the next cell of the current row. */
  write(value: CellValue, style?: Style): void {
    const { encoder } = this.#current()
    encoder.cell(value, encoder.styleId(style))
  }

  /** Ends the current row (or leaves an empty row if none is open). */
  endRow(): void {
    this.#current().encoder.endRow()
  }

  /** Starts the next row explicitly, to set its height or a row style. */
  beginRow(options: RowOptions = {}): void {
    const state = this.#current()
    const style = state.encoder.styleId(options.style)
    state.encoder.flush()
    guard(() => state.native.beginRow(options.height, style))
  }

  /** Leaves `count` cells of the current row empty. */
  skipCells(count = 1): void {
    this.#current().encoder.skip(count)
  }

  /** Leaves `count` empty rows. */
  skipRows(count = 1): void {
    const state = this.#current()
    state.encoder.flush()
    guard(() => state.native.skipRows(count))
  }

  /** Merges a range (1-based, inclusive). */
  mergeCells(firstRow: number, firstColumn: number, lastRow: number, lastColumn: number): void {
    const state = this.#current()
    state.encoder.flush()
    guard(() => state.native.mergeCells(firstRow, firstColumn, lastRow, lastColumn))
  }

  /** Adds filter drop-downs to a range whose first row is the header (1-based, inclusive). */
  autofilter(firstRow: number, firstColumn: number, lastRow: number, lastColumn: number): void {
    const state = this.#current()
    state.encoder.flush()
    guard(() => state.native.autofilter(firstRow, firstColumn, lastRow, lastColumn))
  }

  #current(): WriterState {
    const state = this.#state
    if (state.native.finished) throw new XlsxError('the workbook is already finished')
    if (state.sheets !== this.#index) {
      throw new XlsxError(`worksheet ${JSON.stringify(this.name)} is complete; worksheets are written one at a time`)
    }
    return state
  }
}

/** Returns the column letters for a 1-based column index (1 → "A", 27 → "AA"). */
export function columnName(column: number): string {
  return guard(() => nativeColumnName(column))
}

export interface ColumnSpec {
  /** Property to read from each record. Ignored when the source yields arrays. */
  key: string
  /** Header text. Defaults to `key`. */
  header?: string
  /** Column width in characters. Defaults to an estimate from the header. */
  width?: number
  /** Excel number format for the column, e.g. `'#,##0.00'`. */
  numFormat?: string
  /** Style for the column's cells. `numFormat` is merged into it. */
  style?: StyleOptions
}

export interface ExportOptions extends WriterOptions {
  /** Worksheet name. Default `'Sheet1'`. */
  sheetName?: string
  /**
   * Columns, in order. For records they select the properties to write and default to the keys
   * of the first record; a string is shorthand for `{ key }`. For arrays they describe the
   * columns by position and supply the header.
   */
  columns?: readonly (string | ColumnSpec)[]
  /** Write a styled, frozen header row when the columns are known. Default true. */
  header?: boolean
  /** Add an auto filter to the header row. Default true. */
  autofilter?: boolean
  /** Rows written between two output chunks. Default 2000. */
  batchRows?: number
}

export type Source = Iterable<Row | RecordLike> | AsyncIterable<Row | RecordLike>

const DEFAULT_BATCH_ROWS = 2000
const HEADER_STYLE: StyleOptions = { bold: true, fill: '#D9D9D9', borderBottom: 'thin' }

function estimateWidth(header: string): number {
  return Math.min(Math.max(header.length + 3, 12), 60)
}

/** Builds one worksheet from a source whose shape is known only once the first item arrives. */
class Session {
  readonly writer: Writer
  readonly batchRows: number
  private readonly encoder: RowEncoder
  private sheet: Worksheet | undefined
  private headerCount = 0
  private write: ((item: Row | RecordLike) => void) | undefined

  constructor(private readonly options: ExportOptions) {
    this.writer = new Writer(options)
    this.encoder = states.get(this.writer)!.encoder
    this.batchRows = Math.max(1, options.batchRows ?? DEFAULT_BATCH_ROWS)
  }

  add(item: Row | RecordLike): void {
    if (this.write === undefined) this.start(item)
    this.write!(item)
  }

  finish(): Buffer {
    if (this.sheet === undefined) this.start(undefined)
    const sheet = this.sheet!
    if (this.headerCount > 0 && (this.options.autofilter ?? true)) {
      sheet.autofilter(1, 1, Math.max(sheet.row, 1), this.headerCount)
    }
    return this.writer.finish()
  }

  private start(first: Row | RecordLike | undefined): void {
    const { options, writer, encoder } = this
    const asRecords = first !== undefined && typeof (first as Row)[Symbol.iterator] !== 'function'
    const specs: ColumnSpec[] | undefined =
      options.columns?.map((column) => (typeof column === 'string' ? { key: column } : column)) ??
      (asRecords ? Object.keys(first as RecordLike).map((key) => ({ key })) : undefined)

    const headers = specs?.map((spec) => spec.header ?? spec.key) ?? []
    const withHeader = (options.header ?? true) && headers.length > 0
    this.headerCount = withHeader ? headers.length : 0
    this.sheet = writer.addWorksheet(options.sheetName ?? 'Sheet1', {
      freezeRows: withHeader ? 1 : 0,
      columnWidths: specs?.map((spec, i) => spec.width ?? estimateWidth(headers[i]!)),
    })
    if (withHeader) this.sheet.writeRow(headers, writer.addStyle(HEADER_STYLE))

    const styles = (specs ?? []).map((spec) =>
      spec.style !== undefined || spec.numFormat !== undefined
        ? encoder.styleId(writer.addStyle({ ...spec.style, ...(spec.numFormat !== undefined && { numFormat: spec.numFormat }) }))
        : 0,
    )
    if (asRecords) {
      const keys = specs!.map((spec) => spec.key)
      this.write = (item) => encoder.record(item as RecordLike, keys, styles)
    } else if (styles.some((style) => style !== 0)) {
      this.write = (item) => encoder.styledRow(item as Row, styles)
    } else {
      this.write = (item) => encoder.row(item as Row, 0)
    }
  }
}

function isAsyncIterable(source: Source): source is AsyncIterable<Row | RecordLike> {
  return typeof (source as AsyncIterable<unknown>)[Symbol.asyncIterator] === 'function'
}

async function* chunks(source: Source, options: ExportOptions): AsyncGenerator<Buffer, void, undefined> {
  const session = new Session(options)
  const { writer, batchRows } = session
  let pending = 0
  if (isAsyncIterable(source)) {
    for await (const item of source) {
      session.add(item)
      if (++pending >= batchRows) {
        pending = 0
        const chunk = writer.drain()
        if (chunk.length > 0) yield chunk
      }
    }
  } else {
    for (const item of source) {
      session.add(item)
      if (++pending >= batchRows) {
        pending = 0
        const chunk = writer.drain()
        if (chunk.length > 0) yield chunk
      }
    }
  }
  yield session.finish()
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
  for await (const chunk of chunks(source, options)) parts.push(chunk)
  return Buffer.concat(parts)
}

/** Builds a whole XLSX file in memory from a synchronous source. */
export function toBufferSync(source: Iterable<Row | RecordLike>, options: ExportOptions = {}): Buffer {
  const session = new Session(options)
  for (const item of source) session.add(item)
  return session.finish()
}

/** Writes an XLSX file to `path` without holding it in memory. */
export async function writeFile(path: string, source: Source, options: ExportOptions = {}): Promise<void> {
  await pipeline(stream(source, options), createWriteStream(path))
}
