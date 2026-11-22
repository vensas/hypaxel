# Releasing

All packages share one version and are released together from one tag. Pushing `vX.Y.Z` (or
`vX.Y.Z-beta1`, `vX.Y.Z-RC1`) starts the [`Release`](../.github/workflows/release.yml) workflow.
It first checks that every manifest declares the tag's version, then publishes to each registry:

| Registry | Packages | How | Setup |
| --- | --- | --- | --- |
| Packagist | `vensas/hypaxel` | Tag in the generated `vensas/hypaxel-php` repository | [publishing-php.md](publishing-php.md) |
| nuget.org | `Hypaxel`, `Hypaxel.AspNetCore` (with symbols) | Trusted Publishing, no stored API key | [below](#nugetorg) |
| crates.io | `hypaxel`, `hypaxel-axum` | Trusted Publishing; the first version with a temporary token | [below](#cratesio) |
| Go module proxy | `github.com/vensas/hypaxel/go` | `go/vX.Y.Z` tag in this repository | none |

PyPI, npm and Maven Central are not published yet.

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
suffix is added from the tag where the registry supports it (NuGet `0.5.0-beta1`, Packagist and Go
read the tag itself). crates.io is skipped for pre-release tags, because Cargo publishes the version
in the manifests.

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
