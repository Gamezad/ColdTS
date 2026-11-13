#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output=$(mktemp)
trap 'rm -f "$output"' EXIT

check_version() {
    local tag=$1 name=$2 code=$3
    : > "$output"
    GITHUB_OUTPUT=$output bash "$script_dir/release-version.sh" "$tag"
    grep -Fxq "version_name=$name" "$output"
    grep -Fxq "version_code=$code" "$output"
    grep -Fxq "asset_name=ColdTs-Client-$tag.apk" "$output"
}

check_version v1.0.1 1.0.1 1000001
check_version v1.2.3 1.2.3 1002003
check_version v2.0.0 2.0.0 2000000
check_version v2099.999.999 2099.999.999 2099999999

for invalid in v0.0.0 v1.2 v1.02.3 v1.2.3-beta v1.1000.0 v2100.0.0 v999999999999999999999.0.0 'v1.2.3/invalid'; do
    : > "$output"
    if GITHUB_OUTPUT=$output bash "$script_dir/release-version.sh" "$invalid" >/dev/null 2>&1; then
        echo "Unexpectedly accepted tag: $invalid" >&2
        exit 1
    fi
    [[ ! -s $output ]]
done

echo 'Release version tests passed.'
