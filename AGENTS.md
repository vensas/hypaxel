# AGENTS.md — Hypaxel

## Project Overview

**Project:** Hypaxel (repository `vensas/hypaxel`)  
**What it is:** High-performance, constant-memory XLSX writers and streaming readers for web services  
**Licence:** Apache License 2.0, copyright vensas GmbH  
**Version:** one version number for every package (currently 0.3.0, unreleased)  
**Status:** no package is published to a registry yet

Hypaxel writes `.xlsx` files as a forward-only stream: each cell is serialized to UTF-8 XML as it is written, compressed, and flushed through a small buffer. Memory stays flat regardless of row count. Readers stream rows the same way, with memory bounded by the shared string table plus one row.

`README.md` is the source of truth for the feature list, benchmark figures and roadmap. `CHANGELOG.md` records what each version added.

### Core Principles

1. Adoption must be frictionless.
2. The implementations must be genuinely useful and production-ready.
3. Performance, memory efficiency, correctness, and compatibility are core differentiators.
4. Every implementation follows one shared design and the same semantics.
5. Publish reproducible performance benchmarks.

---

# Repository Layout

| Directory | Contents | Engine |
| --- | --- | --- |
| `rust/` | Cargo workspace: `hypaxel/` (core crate), `hypaxel-axum/` | native |
| `python/` | PyO3 + maturin package: `src/` (bindings), `hypaxel/` (Python API), `tests/`, `benchmarks/` | Rust core |
| `node/` | npm package: `engine/` (shared crate), `src/` (napi-rs), `wasm/` (WebAssembly), `ts/` (TypeScript API), `test/`, `benchmarks/` | Rust core |
| `dotnet/` | Solution: `src/Hypaxel`, `src/Hypaxel.AspNetCore`, `tests/`, `samples/`, `benchmarks/` | native |
| `go/` | Module `github.com/vensas/hypaxel/go`, `hypaxelhttp/`, and `tests/` (nested module with read-back tests and benchmarks) | native |
| `java/` | Maven project `de.vensas:hypaxel`: `src/`, `benchmarks/` (separate project) | native |
| `php/` | Composer package `vensas/hypaxel`: `src/`, `tests/`, `benchmarks/`, `examples/` | native |
| `.github/workflows/` | One workflow per implementation, triggered only by changes to its own code | |
| `docs/assets/` | README banner and the script that generates it | |

## What Exists

- **Writing:** all seven implementations, plus browsers through the Node.js package on WebAssembly.
- **Reading:** .NET, Rust, Python, Node.js (and browsers, on WebAssembly), Go and Java. PHP does not read yet.
- **Web integrations:** ASP.NET Core, axum, FastAPI/Starlette, `net/http`, and stream helpers for Node.js, Java and PHP.

Do not assume a roadmap item exists. Check the code and `CHANGELOG.md` first.

## Architecture Rules

- .NET, Rust, Go, Java and PHP are independent native engines with no runtime dependencies. Keep them that way; the Go workflow fails if the library module gains a dependency.
- Python and Node.js are thin, idiomatic layers over `rust/hypaxel`. Put format logic in the Rust core, not in the bindings. A change to `rust/hypaxel` can break both, and their workflows run on it.
- The native Node.js build and the WebAssembly build share `node/engine` and must produce byte-identical files.
- A feature added to one implementation should have the same semantics in the others. Shared features are listed once in `README.md`; note in the changelog which implementations a change covers.
- Writers are forward-only and constant-memory by default. Anything that buffers (such as auto-fit) must stay bounded and be opt-in.
- Readers treat input as untrusted: reject DTDs, oversized tokens and ZIP entries that do not match their declared size or CRC, and keep the configurable limits.

---

# Build, Test and Lint

Run commands from the implementation's directory. These mirror the CI workflows.

**Rust** (`rust/`, MSRV 1.85)

```bash
cargo fmt --all --check
cargo clippy --workspace --all-features --all-targets
cargo test --workspace --all-features
cargo test -p hypaxel --no-default-features
```

**Python** (`python/`, CPython 3.10+)

```bash
uv venv && uv pip install maturin pytest pytest-asyncio pandas polars numpy openpyxl xlsxwriter fastapi httpx pydantic
.venv/bin/maturin develop --release
.venv/bin/python -m pytest
```

**Node.js** (`node/`, Node.js 22+, needs the `wasm32-unknown-unknown` target and `wasm-bindgen`)

```bash
npm ci
npm run build          # native, wasm and TypeScript
npm test
npm run test:browser
cargo fmt --all --check && cargo clippy --workspace --all-targets
```

**.NET** (`dotnet/`, targets .NET 8, 9, 10, 11)

```bash
dotnet build -c Release
dotnet test -c Release
```

**Go** (`go/`, Go 1.26+)

```bash
gofmt -l . && go vet ./...
go test -race -cover ./...
(cd tests && go vet ./... && go test ./...)   # read-back tests against excelize
```

**Java** (`java/`, Java 17+)

```bash
./mvnw -B verify
```

**PHP** (`php/`, PHP 8.2+)

```bash
composer install
composer check         # php-cs-fixer, phpstan, phpunit
```

CI enforces at least 80% line coverage for PHP. Aim for the same in every implementation.

## Versioning and Changelog

- All packages share one version. A bump touches `rust/Cargo.toml` (workspace), `python/Cargo.toml`, `node/Cargo.toml`, `node/engine/Cargo.toml`, `node/wasm/Cargo.toml`, `node/package.json`, `dotnet/Directory.Build.props` and `java/pom.xml`. Python takes its version from its `Cargo.toml`; Go and PHP are versioned by tag.
- Record every user-visible change in `CHANGELOG.md` under the unreleased version, naming the affected implementations and public API.
- Use conventional commits with the implementation as scope, for example `feat(node): ...`.
- Update the implementation's `README.md` when its public API or benchmark figures change, and the root `README.md` when the cross-language tables change.

---

# Documentation and Benchmarks

## Agent-Friendly Documentation

Each implementation's `README.md` should let a coding agent determine:

1. What problem the library solves and when to use it
2. How to install it
3. A minimal working example and the common web integration
4. The public API and its errors
5. How to measure performance on its own workload

## Benchmark Rules

- Benchmark before optimizing.
- Measure peak memory, not just execution time.
- Compare against credible existing libraries in the same ecosystem.
- Record hardware, library versions and methodology next to the figures, and keep the benchmark reproducible from the `benchmarks/` directory.
- Timings from different implementations are measured separately. Compare a library with its own competitors, not across languages.
- Do not publish figures that were not measured. Re-measure after a change that affects them.
- Preserve correctness when introducing optimizations.

## Correctness Rules

- Written files are checked against the Office Open XML schema validator and read back by independent readers (Open XML SDK, calamine, openpyxl, excelize, LibreOffice). Add a read-back test for new output features.
- Readers must accept files from Excel, LibreOffice and other producers, including Strict Open XML, and report legacy `.xls`, encrypted and damaged files with a clear error.
- Test realistic and adversarial inputs.

---

# Instructions for Coding Agents

## General Working Rules

1. Distinguish what is implemented from what is planned. The roadmap in `README.md` marks the difference.
2. Do not invent benchmark results or customer demand.
3. Prioritize correctness, security, and measurable performance.
4. Design simple, stable, idiomatic APIs for each language.
5. Prefer minimal dependencies and reproducible builds.
6. Avoid unnecessary infrastructure and premature abstraction.
7. Work in the implementation's own directory and run its checks before committing.

## Before Implementing a New Feature

Evaluate:

- How existing libraries in each ecosystem handle it
- Required format compatibility
- Whether it can stream in bounded memory
- Which implementations it applies to, and whether it belongs in the Rust core
- Implementation and maintenance costs across seven codebases

## Licensing Rules

- Everything in this repository is Apache 2.0. Packages that ship from a subdirectory carry their own copy of `LICENSE` and `NOTICE`; keep them in sync with the root files.
- Do not add proprietary code, artificial limits, intentionally slow paths, usage tracking or mandatory cloud dependencies.
- Check the licence of any new dependency, including test and benchmark dependencies, for Apache 2.0 compatibility.
- Outside contributors sign the [Contributor License Agreement](CLA.md) before their first pull request is merged; the CLA workflow asks for it. Do not merge code from authors who have not signed, and do not copy code from other projects into a contribution.
- Flag legal or licensing uncertainty for review.
