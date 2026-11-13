#!/usr/bin/env bash
# Derive a monotonic Android versionCode and versionName from a stable tag.
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo 'Usage: release-version.sh vMAJOR.MINOR.PATCH' >&2
    exit 1
fi

tag=$1
pattern='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
if [[ ! $tag =~ $pattern ]]; then
    echo "Release tags must have the form vMAJOR.MINOR.PATCH (for example v1.0.2)." >&2
    exit 1
fi

major=${BASH_REMATCH[1]}
minor=${BASH_REMATCH[2]}
patch=${BASH_REMATCH[3]}
# Check lengths before arithmetic, so arbitrarily long input cannot overflow.
if (( ${#major} > 4 || ${#minor} > 3 || ${#patch} > 3 )) || (( major > 2099 )); then
    echo 'Version is outside the supported Android versionCode range.' >&2
    exit 1
fi
version_code=$(( major * 1000000 + minor * 1000 + patch ))
if (( version_code == 0 )); then
    echo 'The Android versionCode must be positive.' >&2
    exit 1
fi

: "${GITHUB_OUTPUT:?GITHUB_OUTPUT must identify the workflow output file}"
printf 'version_name=%s\nversion_code=%s\nasset_name=ColdTs-Client-%s.apk\n' \
    "${tag#v}" "$version_code" "$tag" >> "$GITHUB_OUTPUT"
