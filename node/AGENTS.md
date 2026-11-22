# AGENTS.md — Hypaxel Node.js

npm package: `engine/` (shared crate), `src/` (napi-rs), `wasm/` (WebAssembly), `ts/` (TypeScript API), `test/`, `benchmarks/`. Shared rules are in the root `AGENTS.md`.

- A thin, idiomatic layer over `rust/hypaxel`. Put format logic in the Rust core, not in the bindings.
- The native build and the WebAssembly build share `engine/` and must produce byte-identical files.
- A version bump touches `Cargo.toml`, `engine/Cargo.toml`, `wasm/Cargo.toml` and `package.json`.

## Build, Test and Lint

Run from `node/`. Node.js 22+, with the `wasm32-unknown-unknown` target and `wasm-bindgen` installed. CI also runs on changes to `rust/hypaxel`.

```bash
npm ci
npm run build          # native, wasm and TypeScript
npm test
npm run test:types     # type-checks the published .d.ts files as a consumer would
npm run test:browser
cargo fmt --all --check
cargo clippy --workspace --all-targets
cargo clippy -p hypaxel-wasm --target wasm32-unknown-unknown
```
