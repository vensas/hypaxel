# AGENTS.md — Hypaxel .NET

Solution: `src/Hypaxel`, `src/Hypaxel.AspNetCore`, `tests/`, `samples/`, `benchmarks/`. Shared rules are in the root `AGENTS.md`.

- An independent native engine with no runtime dependencies.
- The version lives in `Directory.Build.props`.

## Build, Test and Lint

Run from `dotnet/`. Targets .NET 8, 9, 10 and 11.

```bash
dotnet format --verify-no-changes
dotnet build -c Release
dotnet test -c Release
```

`dotnet format` follows `.editorconfig`; run `dotnet format` without the flag to fix what it reports.
