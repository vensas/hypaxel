# AGENTS.md — Hypaxel Go

Module `github.com/vensas/hypaxel/go`, `hypaxelhttp/` (`net/http` helpers), and `tests/` (a nested module with read-back tests against excelize and benchmarks). Shared rules are in the root `AGENTS.md`.

- An independent native engine. The library module has no dependencies; the workflow fails if `go list -m all` lists anything besides the module itself. Test-only dependencies go in `tests/`.
- Versioned by `go/vX.Y.Z` tags, not a manifest.

## Build, Test and Lint

Run from `go/`. Go 1.26+.

```bash
gofmt -l . && go vet ./...
go test -race -cover ./...
(cd tests && go vet ./... && go test ./...)   # read-back tests against excelize
```
