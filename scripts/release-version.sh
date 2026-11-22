#!/usr/bin/env bash
# Checks a release tag against the version every package declares and prints it.
#
#   scripts/release-version.sh v0.4.0        prints version=0.4.0, prefix=0.4.0, suffix=
#   scripts/release-version.sh v0.5.0-beta1  prints version=0.5.0-beta1, prefix=0.5.0, suffix=beta1
#   scripts/release-version.sh --head        checks that the manifests at HEAD agree and prints
#                                            their version (for dry runs without a tag)
#
# The manifests are read from the tagged commit, which must carry MAJOR.MINOR.PATCH; a pre-release
# suffix lives only in the tag.
set -euo pipefail

TAG_PATTERN='^v([0-9]+\.[0-9]+\.[0-9]+)(-((alpha|beta|RC)\.?[0-9]+))?$'

die() {
    echo "error: $*" >&2
    exit 1
}

[[ $# -eq 1 ]] || die "usage: $0 <tag> | --head"

# Prints the first value a sed script extracts from a manifest at the release commit. The file is
# read completely before sed sees it, so nothing in the pipeline exits early.
manifest_value() {
    local content
    content=$(git show "$rev:$1" 2>/dev/null) || die "$1 is missing at $label"
    sed -n "$2" <<<"$content"
}

CARGO='/^version = "/{s/^version = "\(.*\)"$/\1/p;q;}'

if [[ $1 == --head ]]; then
    rev=HEAD
    label=HEAD
    prefix=$(manifest_value rust/Cargo.toml "$CARGO")
    suffix=
else
    [[ $1 =~ $TAG_PATTERN ]] || die "$1 is not a release tag (vMAJOR.MINOR.PATCH[-beta1])"
    prefix=${BASH_REMATCH[1]}
    suffix=${BASH_REMATCH[3]}
    rev=refs/tags/$1
    label=$1
    git rev-parse --verify --quiet "$rev" >/dev/null || die "tag $1 does not exist"
fi

declare -A declared=(
    [rust/Cargo.toml]=$(manifest_value rust/Cargo.toml "$CARGO")
    [python/Cargo.toml]=$(manifest_value python/Cargo.toml "$CARGO")
    [node/Cargo.toml]=$(manifest_value node/Cargo.toml "$CARGO")
    [node/engine/Cargo.toml]=$(manifest_value node/engine/Cargo.toml "$CARGO")
    [node/wasm/Cargo.toml]=$(manifest_value node/wasm/Cargo.toml "$CARGO")
    [node/package.json]=$(manifest_value node/package.json '/^  "version": "/{s/^  "version": "\(.*\)",$/\1/p;q;}')
    [dotnet/Directory.Build.props]=$(manifest_value dotnet/Directory.Build.props 's:.*<VersionPrefix>\(.*\)</VersionPrefix>.*:\1:p')
    [java/pom.xml]=$(manifest_value java/pom.xml '/^  <version>/{s:^  <version>\(.*\)</version>$:\1:p;q;}')
)

mismatch=0
for file in "${!declared[@]}"; do
    if [[ ${declared[$file]} != "$prefix" ]]; then
        echo "error: $file declares '${declared[$file]}', $label needs $prefix" >&2
        mismatch=1
    fi
done
[[ $mismatch -eq 0 ]] || exit 1

echo "version=$prefix${suffix:+-$suffix}"
echo "prefix=$prefix"
echo "suffix=$suffix"
