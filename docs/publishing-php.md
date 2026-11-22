# Publishing the PHP package

Packagist needs `composer.json` at the root of a repository, so the PHP package is published from
a separate, generated repository. This monorepo stays the single source of truth. The
[`PHP split`](../.github/workflows/php-split.yml) workflow runs
[`scripts/split-php.sh`](../scripts/split-php.sh), which extracts `php/` with
`git subtree split --prefix=php` and pushes the result:

| Monorepo event | Split repository |
| --- | --- |
| Push to `main` that changes `php/` | `main` is fast-forwarded to the split of that commit |
| Push of a release tag `vX.Y.Z` (or `vX.Y.Z-beta1`, `-RC1`) | The tag is created on the split of the tagged commit |
| Manual run with a tag | Same as a tag push; used to publish an existing tag |

The split is deterministic: the same monorepo history always produces the same commits, so the
split repository only ever fast-forwards and is never force-pushed. Before pushing, the script
checks that `composer.json` is at the root, runs `composer validate --strict`, builds the
autoloader with `--strict-psr` and loads `Vensas\Hypaxel\Xlsx` from the archive Composer users
will download. `php/.gitattributes` keeps tests, benchmarks, examples and tool configuration out
of that archive; they stay in the split repository's Git history.

Reruns are safe. A tag that already exists in the split repository with the same commit is left
alone; a tag that exists with a different commit fails the run, because published releases are
never moved. Release a new version instead.

Packagist users install the package with:

```bash
composer require vensas/hypaxel
```

## One-time setup

The suggested name for the split repository is `vensas/hypaxel-php`; any name works.

1. **Create the split repository** on GitHub, public and empty (no README, licence or
   `.gitignore`). In its settings, turn off Issues, Wiki and Projects, and set the description to
   point at the monorepo, for example "Read-only split of vensas/hypaxel/php. Open issues and pull
   requests at github.com/vensas/hypaxel."
2. **Create a deploy key.** Generate a key pair that is used for nothing else:

   ```bash
   ssh-keygen -t ed25519 -N "" -C "hypaxel php split" -f hypaxel-php-split
   ```

   Add `hypaxel-php-split.pub` to the split repository under *Settings → Deploy keys* with
   **Allow write access**. A deploy key only works for that one repository.
3. **Configure the monorepo** under *Settings → Secrets and variables → Actions*:
   - Secret `PHP_SPLIT_DEPLOY_KEY`: the contents of the private key file `hypaxel-php-split`.
   - Variable `PHP_SPLIT_REPOSITORY`: the split repository, for example `vensas/hypaxel-php`.

   Then delete both key files locally. Until the variable is set, the workflow skips.
4. **Protect the split repository** under *Settings → Rules → Rulesets*:
   - A branch ruleset for `main` that blocks force pushes and deletions. Do not require pull
     requests or status checks, or the deploy key cannot push.
   - A tag ruleset for `v*` that restricts updates and deletions, so a published release cannot
     be moved or removed, even by hand.
5. **Run the workflow once** from *Actions → PHP split → Run workflow* on `main` with an empty tag.
   This creates `main` in the split repository. Check that it contains `composer.json` at the
   root and nothing outside `php/`.
6. **Register the package on Packagist.** Sign in to [packagist.org](https://packagist.org) with
   GitHub, using an account that will maintain the vendor `vensas`, choose *Submit* and enter
   `https://github.com/vensas/hypaxel-php` (the split repository, not the monorepo). The package
   name `vensas/hypaxel` comes from `composer.json`.
7. **Enable automatic updates.** If the Packagist account is connected to GitHub and has access to
   the `vensas` organization, Packagist installs its GitHub hook on the split repository itself;
   the package page then no longer warns that it is not auto-updated. If it does, follow the
   instructions on [packagist.org/about#how-to-update-packages](https://packagist.org/about#how-to-update-packages)
   to add the webhook to the split repository manually. Either way, no Packagist token is stored
   in this repository: the split repository notifies Packagist when the workflow pushes to it.

## Publishing a release

Releases follow the monorepo's usual process; the PHP package needs no extra step.

1. Move the version's entry in `CHANGELOG.md` from "unreleased" to its release date and merge it to
   `main`.
2. Tag the release commit on `main` and push the tag:

   ```bash
   git tag -a v0.4.0 -m "Hypaxel 0.4.0"
   git push origin v0.4.0
   ```

3. The workflow creates `v0.4.0` in the split repository, and Packagist lists version `0.4.0`
   within a minute or two. `main` appears on Packagist as `dev-main`.

To publish a tag that already existed before the workflow was set up, run *Actions → PHP split →
Run workflow* with that tag. The first release also needs the "not published yet" note removed
from the install section of `php/README.md`.

To check what would be published without pushing anything, run the script locally against any
remote:

```bash
SPLIT_REMOTE=git@github.com:vensas/hypaxel-php.git DRY_RUN=1 scripts/split-php.sh tag v0.4.0
```

## Rules

- Never commit to the split repository by hand. Its history is generated; a manual commit makes
  the next push fail instead of overwriting it. If that happens, reset the split repository's
  `main` to the last generated commit. The workflow does not force-push.
- Issues and pull requests belong in this repository.
- Do not add a `version` field to `php/composer.json`; Packagist takes versions from tags.
