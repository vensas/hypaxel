// Compares hypaxel's WebAssembly engine with SheetJS and ExcelJS in headless Chrome. Each case runs
// in a fresh browser; the time is the median of three runs after the library has loaded.
//
//   npm run build && node benchmarks/browser/run.mjs            # 100,000 rows
//   node benchmarks/browser/run.mjs 300000
import { arch, cpus, platform } from 'node:os'

import { chromeVersion, runPage } from '../../test/browser/harness.mjs'

const count = Number(process.argv[2] ?? 100_000)
const cases = ['hypaxel (WebAssembly)', 'SheetJS 0.20.3', 'ExcelJS 4.4.0']

console.log(`${count.toLocaleString('en-US')} rows x 10 columns to an XLSX Blob`)
console.log(`${await chromeVersion()}, ${platform()} ${arch()}, ${cpus()[0]?.model ?? 'unknown CPU'}`)
for (const name of cases) {
  const page = `benchmarks/browser/bench.html?case=${encodeURIComponent(name)}&rows=${count}`
  const report = await runPage(page, { timeoutMs: 600_000 })
  if (report.error) {
    console.log(`${name.padEnd(24)} failed: ${report.error.split('\n')[0]}`)
    continue
  }
  const median = [...report.times].sort((a, b) => a - b)[Math.floor(report.times.length / 2)]
  const megabytes = (value) => (value / 1024 / 1024).toFixed(1)
  const memory = report.wasmMemory === undefined ? '' : `  ${megabytes(report.wasmMemory)} MB WebAssembly memory`
  console.log(`${name.padEnd(24)} ${median.toFixed(0).padStart(7)} ms  ${megabytes(report.bytes).padStart(6)} MB file${memory}`)
}
