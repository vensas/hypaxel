# Benchmark results

Every table in the Performance section of an implementation's `README.md` is also published as
JSON, so that other tools (such as the product site) read the same numbers instead of copying them.

- `<implementation>/benchmarks/results/<id>.json`: one benchmark suite, usually one README table
- `benchmarks/highlights.json`: the figures in the root README's "At a glance" tables

`scripts/check_benchmarks.py` checks that every file is valid, that every value appears in the
README table it comes from, and that every README table has a file. CI runs it when a README or a
results file changes. Update the README and the JSON in the same commit, and only with measured
figures.

## Suite

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | string | Stable identifier, also the file name |
| `language` | `dotnet`, `rust`, `python`, `nodejs`, `go`, `java`, `php` | Implementation |
| `group` | string, optional | Sub-group, such as `Reading` or `Browsers (WebAssembly)` |
| `title`, `dataset`, `method` | string | What was measured and how |
| `environment` | `hardware`, `os` (optional), `runtime` | Where it was measured |
| `versions` | string[] | Compared libraries and tools, with versions |
| `metrics` | object[] | Columns: `id`, `label`, `unit` (`ms`, `s`, `MB`, `KB`, `rows/s`), `lowerIsBetter`, and `memoryKind` for memory |
| `chartMetric` | string, optional | Metric to chart |
| `rows` | object[] | `label` (the README row label without Markdown), `subject` (true for Hypaxel), `values` by metric id (`null` = not run) |
| `notes` | string[] | Context from the README text |
| `reproduce` | string, optional | Commands to reproduce the figures |
| `sourcePath` | string | Benchmark code, relative to the repository root |

`memoryKind` says what a memory figure measures. Figures of different kinds are not comparable:

| `memoryKind` | Meaning |
| --- | --- |
| `allocated` | Bytes allocated by the code under test (.NET, Go, Java) |
| `peakRss` | Peak resident memory of the whole process, including the runtime |
| `peakHeap` | Peak of the language's heap (Python `tracemalloc`, PHP `memory_get_peak_usage()`) |
| `wasmMemory` | Size of the WebAssembly memory |

Metrics whose `id` starts with `file` are file sizes and have no `memoryKind`.

## Highlights

Each entry has `language`, optional `title` and `group`, and the `value`, `label` and
`comparison` shown in the root README, plus `memory` and `memoryKind` where a memory figure is
published. Paired memory figures name both files, as in "peak 49 MB inline / 141 MB shared".

Each entry also links to the results files that back it, so a reader can go from the claim to
the table. Each link is a suite `id` or a list of them, and every suite must be for the same
language:

| Field | Meaning |
| --- | --- |
| `suite` | Required. The suites that back `value` and `label`, the main one first |
| `comparisonSuite` | Optional. Set when `comparison` comes from other suites than `suite` |
| `memorySuite` | Optional. Set when `memory` comes from other suites than `suite` |

When `comparison` comes from a different scale than `label` (for example 100k rows against a
1M-row time), it says so, as in "~19–24x faster than ClosedXML (100k rows)".
