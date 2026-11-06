#!/usr/bin/env bash
#
# prepare-assets.sh — fetch the bundled backend into app/src/main/assets/runtime/
#
# Downloads:
#   1. a runtime manifest from the opencode fork (the backend binary itself is
#      NOT bundled — the app downloads it at runtime, see RuntimeInstaller.kt)
#   2. musl loader + libc from Alpine Linux (aarch64)
#   3. libstdc++ from Alpine Linux (aarch64)
#   4. ripgrep from Alpine Linux community (aarch64, musl)
#   5. pcre2 from Alpine Linux main (ripgrep's own shared-library dependency)
#   6. a full Alpine userland (git, bash, python3, curl, ssh, jq, coreutils)
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

# --- 1. opencode backend: not bundled --------------------------------------
# The 240 MB binary is no longer packed into the APK. The app downloads it on
# first launch from the fork's rolling release and keys it on the sha256, so an
# app update costs ~83 MB instead of ~324 MB and the runtime is only re-fetched
# when its bytes actually change. See RuntimeInstaller.kt.
#
# All this step does now is prove the manifest the app will read is really
# there: an APK that installs and can never start its backend is worse than one
# that fails to build.
echo "==> [1/7] opencode backend (fetched at runtime, not bundled)"
RUNTIME_MANIFEST="${RUNTIME_MANIFEST:-https://github.com/Hann1bal/opencode-mesh/releases/download/fork-sync-dev/runtime.json}"
if ! curl -fsSL "$RUNTIME_MANIFEST" -o "$TMP/runtime.json"; then
    echo "ERROR: could not fetch the runtime manifest:" >&2
    echo "       $RUNTIME_MANIFEST" >&2
    echo "       The app would install but never be able to start its backend." >&2
    exit 1
fi
RUNTIME_SHA="$(sed -n 's/.*"sha256": "\([^"]*\)".*/\1/p' "$TMP/runtime.json")"
RUNTIME_SIZE="$(sed -n 's/.*"size": \([0-9]*\).*/\1/p' "$TMP/runtime.json")"
if [ -z "$RUNTIME_SHA" ] || [ -z "$RUNTIME_SIZE" ]; then
    echo "ERROR: the runtime manifest did not parse:" >&2
    cat "$TMP/runtime.json" >&2
    exit 1
fi
echo "    sha256=$RUNTIME_SHA"
echo "    size=$RUNTIME_SIZE"
# Guard against a stale checkout that still has the old bundled copy: shipping
# it by accident would silently restore the 324 MB APK.
if [ -f "$OUT_DIR/opencode" ]; then
    echo "ERROR: $OUT_DIR/opencode exists — the backend must not be bundled." >&2
    exit 1
fi

# --- 2. Alpine package index -------------------------------------------------
echo "==> [2/7] Alpine package index (main)"
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
echo "==> [3/7] musl loader + libc"
curl -fsSL "${ALPINE_MIRROR}/${ALPINE_VERSION}/main/aarch64/${MUSL_APK}" -o "$TMP/musl.apk"
mkdir -p "$TMP/musl"
tar xzf "$TMP/musl.apk" -C "$TMP/musl"
cp "$TMP/musl/lib/ld-musl-aarch64.so.1" "$OUT_DIR/ld-musl-aarch64.so.1"
cp "$TMP/musl/lib/libc.musl-aarch64.so.1" "$OUT_DIR/libc.musl-aarch64.so.1"
chmod 755 "$OUT_DIR/ld-musl-aarch64.so.1" "$OUT_DIR/libc.musl-aarch64.so.1"

# --- 4. libstdc++ + libgcc ---------------------------------------------------
echo "==> [4/7] libstdc++ + libgcc"
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
echo "==> [5/7] ripgrep (Alpine community, musl)"
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

# --- 6. pcre2 --------------------------------------------------------------
# Alpine's rg is linked against libpcre2-8.so.0, which is not part of the ripgrep
# package and not part of musl. Without it the loader fails before rg runs:
#   Error loading shared library libpcre2-8.so.0: No such file or directory
# The main-repo package needs only musl itself, which we already ship.
echo "==> [6/7] pcre2 (ripgrep dependency, Alpine main)"
PCRE2_APK="$(index_find main pcre2)"
if [ -z "$PCRE2_APK" ]; then
    echo "ERROR: could not find pcre2 in Alpine main index" >&2
    exit 1
fi
echo "    pcre2=$PCRE2_APK"
curl -fsSL "${ALPINE_MIRROR}/${ALPINE_VERSION}/main/aarch64/${PCRE2_APK}" -o "$TMP/pcre2.apk"
mkdir -p "$TMP/pcre2"
tar xzf "$TMP/pcre2.apk" -C "$TMP/pcre2"
PCRE2_REAL="$(find "$TMP/pcre2" -type f -name 'libpcre2-8.so.*.*' | head -1)"
if [ -z "$PCRE2_REAL" ]; then
    echo "ERROR: libpcre2-8.so.* not found inside pcre2 apk" >&2
    exit 1
fi
# rg asks for the soname exactly; the loader resolves that name, not the versioned
# file, so the copy has to carry the soname.
cp "$PCRE2_REAL" "$OUT_DIR/libpcre2-8.so.0"
chmod 644 "$OUT_DIR/libpcre2-8.so.0"

# --- 7. Alpine userland ----------------------------------------------------
# Without this the agent on the device has no git, no bash and no python, and
# every project it touches is non-VCS. build-rootfs.py resolves the seed list
# against the Alpine indexes, merges the packages into one tree and stores it as
# a zip with a sidecar symlink manifest; RootfsExtractor turns that into a
# working environment at install time.
echo "==> [7/7] Alpine userland (git, bash, python3, curl, ssh, jq, coreutils)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
python3 "${SCRIPT_DIR}/build-rootfs.py" \
    --out "${OUT_DIR}/env.zip" \
    --alpine "${ALPINE_VERSION}" \
    --mirror "${ALPINE_MIRROR}"

# --- lock file ---------------------------------------------------------------
echo "==> Writing assets.lock"
{
    echo "# Generated by scripts/prepare-assets.sh — do not edit by hand"
    # The backend binary is not bundled. It is downloaded at runtime and pinned
    # by the sha256 recorded here, so this lock still describes exactly what the
    # installed app will end up running.
    echo "runtime_manifest=${RUNTIME_MANIFEST}"
    echo "runtime_sha256=${RUNTIME_SHA}"
    echo "runtime_size=${RUNTIME_SIZE}"
    echo "alpine_version=${ALPINE_VERSION}"
    echo "musl_apk=${MUSL_APK}"
    echo "stdcpp_apk=${STDCPP_APK}"
    echo "ripgrep_apk=${RG_APK}"
    echo "pcre2_apk=${PCRE2_APK}"
    echo "sha256:ld-musl=$(sha256sum "$OUT_DIR/ld-musl-aarch64.so.1" | cut -d' ' -f1)"
    echo "sha256:libc.musl=$(sha256sum "$OUT_DIR/libc.musl-aarch64.so.1" | cut -d' ' -f1)"
    echo "sha256:libstdc++=$(sha256sum "$OUT_DIR/libstdc++.so.6" | cut -d' ' -f1)"
    echo "sha256:libgcc_s=$(sha256sum "$OUT_DIR/libgcc_s.so.1" | cut -d' ' -f1)"
    echo "sha256:rg.musl=$(sha256sum "$OUT_DIR/rg.musl" | cut -d' ' -f1)"
    echo "sha256:libpcre2-8=$(sha256sum "$OUT_DIR/libpcre2-8.so.0" | cut -d' ' -f1)"
    echo "sha256:env.zip=$(sha256sum "$OUT_DIR/env.zip" | cut -d' ' -f1)"
} > "$OUT_DIR/assets.lock"

echo "==> Done. Assets:"
ls -la "$OUT_DIR"
