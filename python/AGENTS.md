# AGENTS.md — Hypaxel Python

PyO3 + maturin package: `src/` (Rust bindings), `hypaxel/` (Python API), `tests/`, `benchmarks/`. Shared rules are in the root `AGENTS.md`.

- A thin, idiomatic layer over `rust/hypaxel`. Put format logic in the Rust core, not in the bindings.
- The package version comes from `python/Cargo.toml`.

## Build, Test and Lint

Run from `python/`. CPython 3.10+. CI also runs on changes to `rust/hypaxel`.

```bash
uv venv && uv pip install maturin pytest pytest-asyncio pandas polars numpy openpyxl xlsxwriter fastapi httpx pydantic
.venv/bin/maturin develop --release
.venv/bin/python -m pytest
```
