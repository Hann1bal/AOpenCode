#!/usr/bin/env bash
#
# prepare-assets.sh — fetch the bundled backend into app/src/main/assets/runtime/
#
# Downloads:
#   1. opencode (linux-arm64-musl) from the npm registry
#   2. musl loader + libc from Alpine Linux (aarch64)
#   3. libstdc++ from Alpine Linux (aarch64)
#   4. ripgrep from Alpine Linux community (aarch64, musl)
#
# Pinned versions + checksums are recorded in assets.lock for reproducibility.
# Run this before `gradle assembleRelease` (CI does it automatically).
#
set -euo pipefail

OPENCODE_VERSION="${OPENCODE_VERSION:-2.2.5}"
ALPINE_VERSION="${ALPINE_VERSION:-v3.20}"
ALPINE_MIRROR="${ALPINE_MIRROR:-https://dl-cdn.alpinelinux.org/alpine}"

OUT_DIR="app/src/main/assets/runtime"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> Preparing backend assets into $OUT_DIR"
echo "    opencode=$OPENCODE_VERSION  alpine=$ALPINE_VERSION"
mkdir -p "$OUT_DIR"

# Downloads an APKINDEX for one repo into $TMP/<name>/ and prints the value of a
# `P:`/`V:` stanza's derived apk filename. This index format has no `f:` field.
index_fetch() {
    # $1 = repo (main|community), $2 = local label
    curl -fsSL "${ALPINE_MIRROR}/${ALPINE_VERSION}/$1/aarch64/APKINDEX.tar.gz" \
        -o "$TMP/APKINDEX-$2.tar.gz"
    mkdir -p "$TMP/idx-$2"
    tar xzf "$TMP/APKINDEX-$2.tar.gz" -C "$TMP/idx-$2"
}

index_find() {
    # $1 = label, $2 = package name
    awk -v pkg="$2" '
        $0 == "P:" pkg { found = 1; next }
        found && /^V:/ { ver = substr($0, 3) }
        found && /^$/ { print pkg "-" ver ".apk"; exit }
    ' "$TMP/idx-$1/APKINDEX"
}

# --- 1. opencode musl binary -------------------------------------------------
echo "==> [1/5] opencode linux-arm64-musl"
if [ -n "${OPENCODE_BINARY_URL:-}" ]; then
    # Fork build: fetch the linux-arm64-musl binary produced by the opencode
    # fork's fork-build workflow (includes the UpstreamSync module).
    echo "    source: OPENCODE_BINARY_URL"
    curl -fsSL "${OPENCODE_BINARY_URL}" -o "$OUT_DIR/opencode"
else
    echo "    source: npm @opencode/cli-linux-arm64-musl@${OPENCODE_VERSION}"
    ( cd "$TMP" && npm pack "@opencode/cli-linux-arm64-musl@${OPENCODE_VERSION}" >/dev/null )
    tar xzf "$TMP"/opencode-cli-linux-arm64-musl-*.tgz -C "$TMP"
    cp "$TMP/package/bin/opencode" "$OUT_DIR/opencode"
fi
chmod 755 "$OUT_DIR/opencode"

# --- 2. Alpine package index -------------------------------------------------
echo "==> [2/5] Alpine package index (main)"
index_fetch main main

MUSL_APK="$(index_find main musl)"
STDCPP_APK="$(index_find main libstdc++ || true)"

if [ -z "$MUSL_APK" ]; then
    echo "ERROR: could not find musl package in Alpine index" >&2
    exit 1
fi
if [ -z "$STDCPP_APK" ]; then
    echo "ERROR: could not find libstdc++ package in Alpine index" >&2
    exit 1
fi
echo "    musl=$MUSL_APK  libstdc++=$STDCPP_APK"

# --- 3. musl loader + libc ---------------------------------------------------
echo "==> [3/5] musl loader + libc"
curl -fsSL "${ALPINE_MIRROR}/${ALPINE_VERSION}/main/aarch64/${MUSL_APK}" -o "$TMP/musl.apk"
mkdir -p "$TMP/musl"
tar xzf "$TMP/musl.apk" -C "$TMP/musl"
cp "$TMP/musl/lib/ld-musl-aarch64.so.1" "$OUT_DIR/ld-musl-aarch64.so.1"
cp "$TMP/musl/lib/libc.musl-aarch64.so.1" "$OUT_DIR/libc.musl-aarch64.so.1"
chmod 755 "$OUT_DIR/ld-musl-aarch64.so.1" "$OUT_DIR/libc.musl-aarch64.so.1"

# --- 4. libstdc++ + libgcc ---------------------------------------------------
echo "==> [4/5] libstdc++ + libgcc"
curl -fsSL "${ALPINE_MIRROR}/${ALPINE_VERSION}/main/aarch64/${STDCPP_APK}" -o "$TMP/stdcpp.apk"
mkdir -p "$TMP/stdcpp"
tar xzf "$TMP/stdcpp.apk" -C "$TMP/stdcpp"
STDCPP_REAL="$(find "$TMP/stdcpp" -name 'libstdc++.so.6.*' | head -1)"
if [ -z "$STDCPP_REAL" ]; then
    echo "ERROR: libstdc++.so.6.* not found inside apk" >&2
    exit 1
fi
cp "$STDCPP_REAL" "$OUT_DIR/libstdc++.so.6"
chmod 644 "$OUT_DIR/libstdc++.so.6"

# libstdc++ needs libgcc_s.so.1 (Alpine package: libgcc)
GCC_APK="$(index_find main libgcc)"
if [ -z "$GCC_APK" ]; then
    echo "ERROR: could not find libgcc package in Alpine index" >&2
    exit 1
fi
curl -fsSL "${ALPINE_MIRROR}/${ALPINE_VERSION}/main/aarch64/${GCC_APK}" -o "$TMP/gcc.apk"
mkdir -p "$TMP/gcc"
tar xzf "$TMP/gcc.apk" -C "$TMP/gcc"
GCC_REAL="$(find "$TMP/gcc" -name 'libgcc_s.so.1' | head -1)"
if [ -z "$GCC_REAL" ]; then
    echo "ERROR: libgcc_s.so.1 not found inside apk" >&2
    exit 1
fi
cp "$GCC_REAL" "$OUT_DIR/libgcc_s.so.1"
chmod 644 "$OUT_DIR/libgcc_s.so.1"

# --- 5. ripgrep ------------------------------------------------------------
# The agent's glob and grep tools shell out to `rg`. Upstream ripgrep publishes no
# aarch64 musl build — only aarch64 *glibc*, which cannot run in our musl
# runtime — and its auto-download path is unreachable anyway until DNS works. So
# take Alpine's community package: built against the same musl we already ship.
echo "==> [5/5] ripgrep (Alpine community, musl)"
index_fetch community community
RG_APK="$(index_find community ripgrep)"
if [ -z "$RG_APK" ]; then
    echo "ERROR: could not find ripgrep in Alpine community index" >&2
    exit 1
fi
echo "    ripgrep=$RG_APK"
curl -fsSL "${ALPINE_MIRROR}/${ALPINE_VERSION}/community/aarch64/${RG_APK}" -o "$TMP/ripgrep.apk"
mkdir -p "$TMP/ripgrep"
tar xzf "$TMP/ripgrep.apk" -C "$TMP/ripgrep"
RG_REAL="$(find "$TMP/ripgrep" -type f -name 'rg' | head -1)"
if [ -z "$RG_REAL" ]; then
    echo "ERROR: rg binary not found inside ripgrep apk" >&2
    exit 1
fi
cp "$RG_REAL" "$OUT_DIR/rg.musl"
chmod 755 "$OUT_DIR/rg.musl"

# The agent execs `rg` directly, but the Alpine binary's PT_INTERP is
# /lib/ld-musl-aarch64.so.1, a path that does not exist on Android. This shim
# runs it through the loader we already bundle, from the same directory.
cat > "$OUT_DIR/rg" <<'SHIM'
#!/system/bin/sh
d=$(dirname "$0")
exec "$d/ld-musl-aarch64.so.1" --library-path "$d" "$d/rg.musl" "$@"
SHIM
chmod 755 "$OUT_DIR/rg"

# --- lock file ---------------------------------------------------------------
echo "==> Writing assets.lock"
{
    echo "# Generated by scripts/prepare-assets.sh — do not edit by hand"
    echo "opencode_version=${OPENCODE_VERSION}"
    echo "alpine_version=${ALPINE_VERSION}"
    echo "musl_apk=${MUSL_APK}"
    echo "stdcpp_apk=${STDCPP_APK}"
    echo "ripgrep_apk=${RG_APK}"
    echo "sha256:opencode=$(sha256sum "$OUT_DIR/opencode" | cut -d' ' -f1)"
    echo "sha256:ld-musl=$(sha256sum "$OUT_DIR/ld-musl-aarch64.so.1" | cut -d' ' -f1)"
    echo "sha256:libc.musl=$(sha256sum "$OUT_DIR/libc.musl-aarch64.so.1" | cut -d' ' -f1)"
    echo "sha256:libstdc++=$(sha256sum "$OUT_DIR/libstdc++.so.6" | cut -d' ' -f1)"
    echo "sha256:libgcc_s=$(sha256sum "$OUT_DIR/libgcc_s.so.1" | cut -d' ' -f1)"
    echo "sha256:rg.musl=$(sha256sum "$OUT_DIR/rg.musl" | cut -d' ' -f1)"
} > "$OUT_DIR/assets.lock"

echo "==> Done. Assets:"
ls -la "$OUT_DIR"
