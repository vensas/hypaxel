#!/usr/bin/env bash
# Checks a release tag against the version every package declares and prints it.
#
#   scripts/release-version.sh v0.4.0        prints version=0.4.0, prefix=0.4.0, suffix=
#   scripts/release-version.sh v0.5.0-beta1  prints version=0.5.0-beta1, prefix=0.5.0, suffix=beta1
#
# The manifests are read from the tagged commit, which must carry MAJOR.MINOR.PATCH; a pre-release
# suffix lives only in the tag.
set -euo pipefail

TAG_PATTERN='^v([0-9]+\.[0-9]+\.[0-9]+)(-((alpha|beta|RC)\.?[0-9]+))?$'

die() {
    echo "error: $*" >&2
    exit 1
}

[[ $# -eq 1 ]] || die "usage: $0 <tag>"
[[ $1 =~ $TAG_PATTERN ]] || die "$1 is not a release tag (vMAJOR.MINOR.PATCH[-beta1])"
prefix=${BASH_REMATCH[1]}
suffix=${BASH_REMATCH[3]}

tag=$1
git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null || die "tag $tag does not exist"

at_tag() {
    git show "refs/tags/$tag:$1" 2>/dev/null || die "$1 is missing at $tag"
}

cargo_version() {
    at_tag "$1" | sed -n 's/^version = "\(.*\)"$/\1/p' | head -n 1
}

declare -A declared=(
    [rust/Cargo.toml]=$(cargo_version rust/Cargo.toml)
    [python/Cargo.toml]=$(cargo_version python/Cargo.toml)
    [node/Cargo.toml]=$(cargo_version node/Cargo.toml)
    [node/engine/Cargo.toml]=$(cargo_version node/engine/Cargo.toml)
    [node/wasm/Cargo.toml]=$(cargo_version node/wasm/Cargo.toml)
    [node/package.json]=$(at_tag node/package.json | sed -n 's/^  "version": "\(.*\)",$/\1/p' | head -n 1)
    [dotnet/Directory.Build.props]=$(at_tag dotnet/Directory.Build.props | sed -n 's:.*<VersionPrefix>\(.*\)</VersionPrefix>.*:\1:p')
    [java/pom.xml]=$(at_tag java/pom.xml | sed -n 's:^  <version>\(.*\)</version>$:\1:p' | head -n 1)
)

mismatch=0
for file in "${!declared[@]}"; do
    if [[ ${declared[$file]} != "$prefix" ]]; then
        echo "error: $file declares '${declared[$file]}', the tag $tag needs $prefix" >&2
        mismatch=1
    fi
done
[[ $mismatch -eq 0 ]] || exit 1

echo "version=$prefix${suffix:+-$suffix}"
echo "prefix=$prefix"
echo "suffix=$suffix"
