#!/usr/bin/env bash
# A release must be installable, non-debuggable, and carry its tag's version.
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo 'Usage: verify-release-apk.sh APK VERSION_NAME VERSION_CODE' >&2
    exit 1
fi
apk=$1
version_name=$2
version_code=$3
[[ -f $apk ]] || { echo "APK not found: $apk" >&2; exit 1; }
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n $sdk && -d $sdk/build-tools ]] || { echo 'Android SDK build-tools not found.' >&2; exit 1; }

tools=
while IFS= read -r candidate; do
    if [[ -x $candidate/apksigner && -x $candidate/aapt ]]; then
        tools=$candidate
        break
    fi
done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -Vr)
[[ -n $tools ]] || { echo 'Android apksigner and aapt were not found.' >&2; exit 1; }

"$tools/apksigner" verify --verbose --print-certs "$apk"
badging=$("$tools/aapt" dump badging "$apk")
[[ $badging == *"package: name='com.gamezad.coldts'"* ]] || { echo 'Unexpected package ID.' >&2; exit 1; }
[[ $badging == *"versionName='$version_name'"* ]] || { echo 'APK versionName does not match the release tag.' >&2; exit 1; }
[[ $badging == *"versionCode='$version_code'"* ]] || { echo 'APK versionCode does not match the release tag.' >&2; exit 1; }
[[ $badging != *application-debuggable* ]] || { echo 'A debuggable APK cannot be published as a release.' >&2; exit 1; }
echo "Verified signed release APK: $apk"
