#!/usr/bin/env bash
# Exercise publication decisions without contacting GitHub or publishing a release.
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/bin/gh" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$GH_MOCK_LOG"
case "$2" in
    view)
        case "$GH_MOCK_MODE" in
            missing) echo 'release not found' >&2; exit 1 ;;
            error) echo 'HTTP 403: access denied' >&2; exit 1 ;;
            duplicate) echo 'ColdTs-Client-v1.2.3.apk' ;;
            existing) echo 'another-asset.txt' ;;
            *) exit 2 ;;
        esac ;;
    create|upload) ;;
    *) exit 2 ;;
esac
MOCK
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" GH_MOCK_LOG="$tmp/calls"
apk="$tmp/ColdTs-Client-v1.2.3.apk"
touch "$apk"

run_publish() {
    GH_MOCK_MODE=$1 GITHUB_EVENT_NAME=$2 bash "$script_dir/publish-release.sh" v1.2.3 "$apk"
}

: > "$GH_MOCK_LOG"
run_publish missing push
grep -Fq "release create v1.2.3 $apk --verify-tag" "$GH_MOCK_LOG"

: > "$GH_MOCK_LOG"
run_publish existing push
grep -Fxq "release upload v1.2.3 $apk" "$GH_MOCK_LOG"

: > "$GH_MOCK_LOG"
run_publish duplicate release
[[ $(wc -l < "$GH_MOCK_LOG") -eq 1 ]]

: > "$GH_MOCK_LOG"
if run_publish error push >/dev/null 2>&1; then echo 'Expected a failed release lookup.' >&2; exit 1; fi
[[ $(wc -l < "$GH_MOCK_LOG") -eq 1 ]]

: > "$GH_MOCK_LOG"
if run_publish missing release >/dev/null 2>&1; then echo 'Expected a missing published release to fail.' >&2; exit 1; fi
[[ $(wc -l < "$GH_MOCK_LOG") -eq 1 ]]

: > "$GH_MOCK_LOG"
if GH_MOCK_MODE=missing GITHUB_EVENT_NAME=push bash "$script_dir/publish-release.sh" v1.2.3 "$tmp/missing.apk" >/dev/null 2>&1; then
    echo 'Expected a missing APK to fail.' >&2; exit 1
fi
[[ ! -s $GH_MOCK_LOG ]]

echo 'Release publication tests passed.'
