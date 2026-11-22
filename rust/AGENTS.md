# AGENTS.md — Hypaxel Rust

Cargo workspace: `hypaxel/` (core crate) and `hypaxel-axum/` (axum integration). Shared rules are in the root `AGENTS.md`.

- `hypaxel` is a native engine and also the core behind the Python and Node.js packages. A change here runs the Python and Node.js workflows too; check them before merging.
- Integrations (`chrono`, `serde`, `tokio`) stay behind optional features; the crate must build with `--no-default-features`. Keep required dependencies minimal.

## Build, Test and Lint

Run from `rust/`. MSRV 1.85. These mirror `.github/workflows/rust.yml`.

```bash
cargo fmt --all --check
cargo clippy --workspace --all-features --all-targets
cargo test --workspace --all-features
cargo test -p hypaxel --no-default-features
cargo build -p hypaxel --no-default-features --features tokio
```
