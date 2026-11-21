#!/usr/bin/env bash
# Publishes php/ to the generated repository that Packagist reads.
#
#   scripts/split-php.sh branch [<rev>]   push the split of <rev> (default HEAD) to $SPLIT_BRANCH
#   scripts/split-php.sh tag <tag>        push the split of monorepo tag <tag> as the same tag
#
# Environment: SPLIT_REMOTE (required, URL of the split repository), SPLIT_BRANCH (default main),
# DRY_RUN=1 to verify without pushing.
#
# git subtree split without --rejoin is deterministic, so every run recreates the same commits and
# pushes are fast-forwards. Nothing is force-pushed, and an existing tag is never moved.
set -euo pipefail

PREFIX=php
SPLIT_BRANCH=${SPLIT_BRANCH:-main}
TAG_PATTERN='^v[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|RC)\.?[0-9]+)?$'

die() {
    echo "error: $*" >&2
    exit 1
}

usage() {
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

split_of() {
    local commit
    commit=$(git rev-parse --verify --quiet "$1^{commit}") || die "unknown revision: $1"
    git cat-file -e "$commit:$PREFIX/composer.json" 2>/dev/null \
        || die "$1 has no $PREFIX/composer.json"
    git subtree split --prefix="$PREFIX" "$commit" 2>/dev/null
}

verify() {
    local sha=$1 dir files
    files=$(git ls-tree -r --name-only "$sha")
    grep -qx composer.json <<<"$files" || die "composer.json is not at the root of the split"
    grep -qx LICENSE <<<"$files" || die "LICENSE is missing from the split"
    grep -qx NOTICE <<<"$files" || die "NOTICE is missing from the split"
    if grep -qE '^(vendor/|composer\.lock$)' <<<"$files"; then
        die "the split contains vendor/ or composer.lock"
    fi

    dir=$(mktemp -d)
    # shellcheck disable=SC2064
    trap "rm -rf '$dir'" RETURN
    # git archive applies export-ignore, so this is the tree Composer users download.
    git archive "$sha" | tar -x -C "$dir"
    echo "Files in the Composer dist archive:"
    (cd "$dir" && find . -type f | sed 's|^\./|  |' | sort)
    export COMPOSER_ROOT_VERSION=dev-$SPLIT_BRANCH
    composer validate --strict --no-check-lock --working-dir="$dir"
    composer dump-autoload --no-dev --optimize --strict-psr --working-dir="$dir" --quiet
    # shellcheck disable=SC2016
    php -r 'require $argv[1]; exit(class_exists(Vensas\Hypaxel\Xlsx::class) ? 0 : 1);' \
        "$dir/vendor/autoload.php" || die "Vensas\\Hypaxel\\Xlsx does not autoload from the split"
}

remote_ref() {
    # Prints the commit a remote ref points to (peeled for annotated tags), or nothing.
    local refs
    refs=$(git ls-remote "$SPLIT_REMOTE" "$1" "$1^{}") || die "cannot read $SPLIT_REMOTE"
    awk -v peeled="$1^{}" '$2 == peeled { p = $1 } { d = $1 } END { print (p != "" ? p : d) }' \
        <<<"$refs"
}

push() {
    if [[ ${DRY_RUN:-} == 1 ]]; then
        echo "dry run: git push $SPLIT_REMOTE $1"
    else
        git push "$SPLIT_REMOTE" "$1"
    fi
}

sync_branch() {
    local rev=${1:-HEAD} sha current
    sha=$(split_of "$rev")
    echo "Split of $rev: $sha"
    verify "$sha"
    current=$(remote_ref "refs/heads/$SPLIT_BRANCH")
    if [[ $current == "$sha" ]]; then
        echo "$SPLIT_BRANCH is up to date."
        return
    fi
    [[ -z $current ]] && echo "Creating $SPLIT_BRANCH in the split repository."
    push "$sha:refs/heads/$SPLIT_BRANCH" || die "push rejected. The split history must only \
fast-forward; if the split repository was edited by hand, restore it instead of force-pushing."
}

publish_tag() {
    local tag=$1 sha current
    [[ $tag =~ $TAG_PATTERN ]] || die "$tag is not a release tag (vMAJOR.MINOR.PATCH[-beta1])"
    git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null || die "tag $tag does not exist"
    sha=$(split_of "refs/tags/$tag")
    echo "Split of $tag: $sha"
    verify "$sha"
    current=$(remote_ref "refs/tags/$tag")
    if [[ $current == "$sha" ]]; then
        echo "$tag is already published at $sha."
        return
    fi
    [[ -n $current ]] && die "$tag already exists in the split repository at $current, not $sha. \
Published releases are never moved; release a new version instead."
    push "$sha:refs/tags/$tag"
}

[[ -n ${SPLIT_REMOTE:-} ]] || die "SPLIT_REMOTE is not set"
cd "$(git rev-parse --show-toplevel)"

case ${1:-} in
    branch) sync_branch "${2:-}" ;;
    tag) [[ $# -eq 2 ]] || usage; publish_tag "$2" ;;
    *) usage ;;
esac
