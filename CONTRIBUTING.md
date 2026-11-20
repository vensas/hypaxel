# Contributing to Hypaxel

Thank you for helping. Bug reports, read-back failures with other XLSX tools, benchmarks and pull
requests are all welcome.

## Before you start

- For a bug, open an issue with a minimal example and, if possible, the file that fails to open or
  read.
- For a larger change or a new feature, open an issue first. Hypaxel has seven implementations
  that share one design, so a feature usually needs to fit all of them.

## Pull requests

- Work in the directory of the implementation you change and run its checks. The commands are
  listed in [AGENTS.md](AGENTS.md#build-test-and-lint) and match the CI workflows.
- Add tests. A new output feature needs a read-back test with an independent reader.
- Record user-visible changes in [CHANGELOG.md](CHANGELOG.md) under the unreleased version.
- Use [conventional commits](https://www.conventionalcommits.org/) with the implementation as the
  scope, for example `fix(go): ...` or `feat(python): ...`.
- New dependencies, including test and benchmark dependencies, need a license compatible with
  Apache 2.0.

## Contributor License Agreement

Before we can merge your first pull request, you need to sign the
[Contributor License Agreement](CLA.md). A bot asks you to do so on the pull request; signing is a
single comment and covers all your future contributions.

The agreement is a license, not a transfer: you keep the copyright in your work. It lets
vensas GmbH distribute your contribution under other licenses as well, for example in commercial
editions. Hypaxel itself stays under the Apache License 2.0, and published versions never change
their license.

## License

Contributions are distributed under the [Apache License, Version 2.0](LICENSE).
