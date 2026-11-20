import { createWriteStream } from 'node:fs'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import type { ReadableStream } from 'node:stream/web'

import { NativeWriter, columnName as nativeColumnName } from '../binding'
import {
  type ExportOptions,
  type RecordLike,
  type Row,
  type Source,
  Writer as CoreWriter,
  buildSync,
  chunks,
  setEngine,
} from './core'

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

setEngine({
  createWriter: (options) => new NativeWriter(options),
  columnName: nativeColumnName,
  alloc: (size) => Buffer.allocUnsafe(size),
  writeUtf8: (buffer, offset, value) => (buffer as Buffer).write(value, offset, 'utf8'),
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
