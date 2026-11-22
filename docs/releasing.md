# Releasing

All packages share one version and are released together from one tag. Pushing `vX.Y.Z` (or
`vX.Y.Z-beta1`, `vX.Y.Z-RC1`) starts the [`Release`](../.github/workflows/release.yml) workflow.
It first checks that every manifest declares the tag's version, then publishes to each registry:

| Registry | Packages | How | Setup |
| --- | --- | --- | --- |
| Packagist | `vensas/hypaxel` | Tag in the generated `vensas/hypaxel-php` repository | [publishing-php.md](publishing-php.md) |
| nuget.org | `Hypaxel`, `Hypaxel.AspNetCore` (with symbols) | Trusted Publishing, no stored API key | [below](#nugetorg) |
| crates.io | `hypaxel`, `hypaxel-axum` | Trusted Publishing; the first version with a temporary token | [below](#cratesio) |
| npm | `hypaxel` (native addon for five targets and WebAssembly, with provenance) | Trusted Publishing; the first version with a temporary token | [below](#npm) |
| PyPI | `hypaxel` (wheels for CPython 3.10–3.14 on seven platforms, and the sdist) | Trusted Publishing, no token at all | [below](#pypi) |
| Go module proxy | `github.com/vensas/hypaxel/go` | `go/vX.Y.Z` tag in this repository | none |

Maven Central is not published yet.

## Publishing a release

1. Bump the version in every manifest if that has not happened yet (the files are listed in
   [AGENTS.md](../AGENTS.md#versioning-and-changelog)), and give the version's `CHANGELOG.md`
   entry its release date instead of "unreleased".
2. Merge to `main` and wait for the implementation workflows to pass.
3. Tag the merge commit on `main` and push the tag:

   ```bash
   git switch main && git pull --ff-only
   git tag -a v0.4.0 -m "Hypaxel 0.4.0"
   git push origin v0.4.0
   ```

4. Follow *Actions → Release*. The NuGet packages are built and tested from the tag before they are
   pushed.

Every job that publishes runs in the GitHub environment `release`; builds and tests run outside it.
Create it once under *Settings → Environments → New environment* and limit its deployment branches
and tags to the tag pattern `v*` and the branch `main` (manual runs start from `main`). Add
required reviewers there if a person should approve every release: that is the one setting that
stops an unwanted release, since anyone with write access can push a tag. Registries with trusted
publishing (nuget.org now; PyPI, npm and crates.io later) are bound to `release.yml` and this
environment.

A failed or partial release is repaired by running *Actions → Release → Run workflow* with the tag,
for all registries or for one. Every registry leaves a version it already has alone: nuget.org
skips existing packages, and the PHP split and Go tags are created only if missing. A tag that
already exists with a different commit fails the run. Published versions are never moved or
replaced; fix a broken release with a new version.

A pre-release tag (`v0.5.0-beta1`) needs no manifest change: the manifests keep `0.5.0`, and the
suffix is added from the tag where the registry supports it (NuGet and npm `0.5.0-beta1`, npm under
the `next` dist-tag instead of `latest`; Packagist and Go read the tag itself). crates.io and PyPI are skipped for pre-release tags, because Cargo and maturin
publish the version in the manifests.

## nuget.org

The `nuget` job exchanges the workflow's GitHub OIDC token for a NuGet API key that is valid for
one hour, so no NuGet key is stored in GitHub. The packages are built in a separate job without
OIDC permission; the publishing job only downloads and pushes them.

One-time setup:

1. **Create the Trusted Publishing policy.** Sign in to [nuget.org](https://www.nuget.org) with an
   account that is an administrator of the `vensas` organization, open the account menu →
   *Trusted Publishing* and add a policy:
   - Package owner: the `vensas` organization
   - Repository owner: `vensas`, repository: `hypaxel`
   - CI/CD provider: GitHub Actions
   - Workflow file: `release.yml`
   - Environment: `release`
2. **Set the user name.** In this repository under *Settings → Secrets and variables → Actions →
   Variables*, set `NUGET_USER` to the nuget.org profile name (not the email address) of the
   account that created the policy.
3. **Optional: reserve the ID prefix.** Packages under a reserved prefix (`Hypaxel.*`) can only be
   published by the organization and show a verified badge. The reservation is requested from the
   nuget.org team; see
   [ID prefix reservation](https://learn.microsoft.com/nuget/nuget-org/id-prefix-reservation).

Without `NUGET_USER` the `nuget` job fails and the other registries are still published.

## crates.io

The `crates` job publishes `hypaxel`, then `hypaxel-axum`, each only if crates.io does not have the
version yet. Tests and `cargo package --workspace` run from the tag in a separate job without OIDC
permission.

crates.io accepts the first version of a crate only with an API token; Trusted Publishing can be
configured once the crate exists. The workflow uses the `CARGO_REGISTRY_TOKEN` secret when it is
set and Trusted Publishing otherwise, so switching needs no workflow change.

First release:

1. Sign in to [crates.io](https://crates.io) with GitHub and verify the account's email address.
2. Create an API token under *Account Settings → API Tokens* with the scopes `publish-new` and
   `publish-update`, the crate pattern `hypaxel*` and a short expiry, for example 30 days.
3. Store it as the secret `CARGO_REGISTRY_TOKEN` of the `release` environment (*Settings →
   Environments → release → Environment secrets*), so only the publishing job can read it:

   ```bash
   gh secret set CARGO_REGISTRY_TOKEN --repo vensas/hypaxel --env release
   ```

After the first release, for each of `hypaxel` and `hypaxel-axum`:

1. On crates.io, open the crate → *Settings → Trusted Publishing → Add* → GitHub, with repository
   owner `vensas`, repository `hypaxel`, workflow `release.yml` and environment `release`.
2. Optionally add a second owner so the crates do not depend on one account, for example a GitHub
   team with `cargo owner --add github:vensas:<team> hypaxel`.

Then delete the secret (`gh secret delete CARGO_REGISTRY_TOKEN --repo vensas/hypaxel --env
release`) and revoke the token on crates.io.

## npm

One package carries the native addon for every target in `napi.targets` of `node/package.json`
(Linux x64 and arm64 with glibc, macOS x64 and arm64, Windows x64) plus the WebAssembly build:

1. `npm-native` builds each addon. Linux addons are built with `--use-napi-cross`, which links
   against glibc 2.17, so they load on every current distribution; macOS x64 is cross-compiled on
   an arm64 runner.
2. `npm-pack` builds the WebAssembly module and TypeScript, runs the test suite against the
   Linux x64 addon, packs the tarball and checks that every addon is in it.
3. `npm-smoke` installs that tarball with Node.js 22, the oldest supported version, on Ubuntu
   22.04 (x64 and arm64), macOS (Intel and Apple silicon) and Windows, and writes and reads a
   workbook (`node/scripts/smoke.mjs`).
4. `npm` publishes the tested tarball, unless npm already has the version.

npm sets up a trusted publisher in the settings of a package that exists, and the publisher must
complete its first publish within two days. So the first version is published with a token:

1. Sign in to [npmjs.com](https://www.npmjs.com) with an account that has two-factor
   authentication. The package stays unscoped; the npm organization `hypaxel` manages it and
   reserves the `@hypaxel/` scope for future packages. After the first publish, give a team of
   that organization read/write access, so the package does not depend on one account:
   *organization settings → Teams → Packages → + Add Existing Package*, or
   `npm access grant read-write hypaxel:<team> hypaxel`.
2. Create a granular access token under *Access Tokens → Generate New Token*: read and write for
   all packages (a token limited to existing packages or one scope cannot create `hypaxel`),
   allowed to bypass two-factor authentication, short expiry.
3. Store it as the secret `NPM_TOKEN` of the `release` environment:

   ```bash
   gh secret set NPM_TOKEN --repo vensas/hypaxel --env release
   ```

After the first release:

1. On npmjs.com, open `hypaxel` → *Settings → Trusted Publisher* → GitHub Actions, with
   organization `vensas`, repository `hypaxel`, workflow file `release.yml`, environment `release`,
   and allow `npm publish`.
2. Under *Settings → Publishing access*, choose "Require two-factor authentication and disallow
   tokens". Trusted publishing keeps working.
3. Delete the secret (`gh secret delete NPM_TOKEN --repo vensas/hypaxel --env release`) and the
   token on npmjs.com.

Packages published through trusted publishing get a provenance statement automatically; the token
release passes `--provenance` for the same result.

## PyPI

1. `pypi-wheels` builds wheels for every CPython version from `requires-python` on Linux x86_64
   and aarch64, each for manylinux (glibc) and musllinux (Alpine), macOS x86_64 and arm64, and
   Windows x64.
2. `pypi-sdist` builds the source distribution, which vendors the `hypaxel` crate from `rust/`,
   and installs it from source to prove it is complete.
3. `pypi-smoke` installs the wheels with Python 3.10 and 3.14 on Ubuntu 22.04 (x64 and arm64),
   macOS (Intel and Apple silicon), Windows and Alpine, and writes and reads a workbook
   (`python/scripts/smoke.py`).
4. `pypi` uploads the wheels and the sdist with attestations, skipping files PyPI already has.

PyPI can trust a publisher before the project exists, so no token is needed, not even for the
first release. One-time setup:

1. Sign in to [pypi.org](https://pypi.org) with an account that has two-factor authentication.
2. Under *Your account → Publishing → Add a new pending publisher* → GitHub, enter PyPI project
   name `hypaxel`, owner `vensas`, repository `hypaxel`, workflow name `release.yml` and
   environment name `release`.

A pending publisher does not reserve the name; the first upload creates the project. Afterwards
the project can be moved to a PyPI organization and given more owners under *Manage → Collaborators*.

## Go

Go modules in a subdirectory are versioned by tags with the directory as prefix, so
`github.com/vensas/hypaxel/go@v0.4.0` resolves the tag `go/v0.4.0`. The `go` job creates that tag
on the release commit with the workflow's `GITHUB_TOKEN` and asks the Go module proxy for the
version, so it is listed on [pkg.go.dev](https://pkg.go.dev/github.com/vensas/hypaxel/go) soon
after. If a ruleset ever restricts tag creation in this repository, GitHub Actions needs to be
allowed to bypass it for `go/v*`.

Users install the module with:

```bash
go get github.com/vensas/hypaxel/go@latest
```
