# AGENTS.md — Hypaxel Java

Maven project `de.vensas:hypaxel`: `src/`, and `benchmarks/` as a separate project. Shared rules are in the root `AGENTS.md`.

- An independent native engine with no runtime dependencies.
- The version lives in `pom.xml`.

## Build, Test and Lint

Run from `java/`. Java 17+.

```bash
./mvnw -B verify
```
