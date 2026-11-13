#!/usr/bin/env bash
# Attach an APK to an existing release, or create one for an existing tag.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo 'Usage: publish-release.sh TAG APK' >&2
    exit 1
fi
tag=$1
apk=$2
asset_name=$(basename -- "$apk")
[[ -f $apk ]] || { echo "APK not found: $apk" >&2; exit 1; }
[[ $asset_name == "ColdTs-Client-$tag.apk" ]] || { echo 'APK filename does not match the tag.' >&2; exit 1; }

error_file=$(mktemp)
trap 'rm -f "$error_file"' EXIT
if assets=$(gh release view "$tag" --json assets --jq '.assets[].name' 2> "$error_file"); then
    if grep -Fxq -- "$asset_name" <<< "$assets"; then
        # A tag is immutable: do not silently replace an already published APK.
        echo "Release already contains $asset_name; leaving it unchanged."
    else
        gh release upload "$tag" "$apk"
        echo 'APK attached to the existing release. An existing draft remains a draft.'
    fi
elif [[ ${GITHUB_EVENT_NAME:-} != release ]] && grep -Eqi 'release not found|HTTP 404' "$error_file"; then
    gh release create "$tag" "$apk" --verify-tag --title "ColdTs Client $tag" --generate-notes
else
    cat "$error_file" >&2
    echo "Could not inspect release $tag; refusing to create or modify a release." >&2
    exit 1
fi
