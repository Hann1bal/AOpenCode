#!/usr/bin/env bash
# Verify the locally built release APK: size, signing, and that the 240 MB
# backend binary is NOT bundled (the whole point of the de-bundle).
set -uo pipefail
. "$HOME/.bashrc.d/aopencode.sh"
cd "$HOME/aopencode" || exit 1

APK="$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || { echo "no release APK found"; exit 1; }

BYTES=$(stat -c%s "$APK")
echo "apk:  $APK"
echo "size: $((BYTES / 1048576)) MB ($BYTES bytes)"

echo "--- signing ---"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$APK" 2>&1 | head -4

echo "--- bundled runtime contents ---"
unzip -l "$APK" | grep -E 'assets/runtime|assets/env' | awk '{printf "  %-46s %s\n", $4, $1}'

echo "--- bare opencode binary present? ---"
if unzip -l "$APK" | grep -q 'assets/runtime/opencode$'; then
    echo "  BAD: the 240 MB binary is bundled again"
else
    echo "  good: not bundled, downloaded at runtime"
fi

echo "--- abi ---"
unzip -l "$APK" | grep -oE 'lib/[a-z0-9_-]+/' | sort -u | sed 's/^/  /' || echo "  (no lib/ entries)"
