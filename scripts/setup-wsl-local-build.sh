#!/usr/bin/env bash
#
# setup-wsl-local-build.sh — one-time toolchain install for building AOpenCode
# inside WSL2.
#
# Why WSL: prepare-assets.sh is bash and repacks an Alpine aarch64 userland,
# which is miserable on plain Windows. WSL2 already has bash, curl, tar and
# python3+cryptography, so only the JVM/Gradle/SDK half is missing.
#
# Versions are not arbitrary:
#   Gradle 8.11.1  — AGP 8.7.3 rejects the runner's preinstalled Gradle 9.x
#                    at configuration time (see build-apk.yml).
#   JDK 17         — what CI uses (temurin 17) and what AGP 8.7 targets.
#   platform-35    — compileSdk in app/build.gradle.kts.
#
# Everything lands under $HOME (~/android-sdk, ~/tools/gradle-8.11.1). Only
# the JDK and unzip go through apt. Nothing is installed into system Gradle or
# SDK locations, so removing this is `rm -rf ~/android-sdk ~/tools`.
#
# Usage:
#   SUDO_PASSWORD=... bash scripts/setup-wsl-local-build.sh
#
set -uo pipefail

GRADLE_VERSION="8.11.1"
SDK_ROOT="$HOME/android-sdk"
TOOLS_ROOT="$HOME/tools"
GRADLE_HOME="$TOOLS_ROOT/gradle-$GRADLE_VERSION"
CMDLINE_TOOLS_ZIP="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# --- 0. Preflight: is there actually a network? -----------------------------
# Without this the script hangs for minutes inside apt, printing nothing but
# "Temporary failure resolving", which reads like a slow download rather than
# the dead path it is. A VPN with a split-tunnel driver is the usual cause: it
# proxies the Windows host's own traffic, so Windows looks fine, but drops the
# forwarded packets WSL's NAT produces, so WSL has no route out at all.
log "preflight: outbound connectivity from WSL"
preflight_fail() {
    printf '\033[31mERROR: WSL has no outbound network.\033[0m\n' >&2
    printf '  tested: TCP 1.1.1.1:443 and DNS for dl.google.com\n' >&2
    printf '  Windows itself being online does not rule this out.\n' >&2
    printf '  Most likely a VPN split-tunnel driver (Amnezia, Radmin, OpenVPN)\n' >&2
    printf '  is the lowest-metric default route and drops forwarded traffic.\n' >&2
    printf '  Disconnect the VPN, or raise its interface metric, then rerun.\n' >&2
    exit 1
}
timeout 12 bash -c '</dev/tcp/1.1.1.1/443' 2>/dev/null || preflight_fail
getent hosts dl.google.com >/dev/null 2>&1 || preflight_fail
echo "    TCP and DNS both reachable"

sudo_run() {
    [ -n "${SUDO_PASSWORD:-}" ] || die "SUDO_PASSWORD is not set"
    printf '%s\n' "$SUDO_PASSWORD" | sudo -S -p '' "$@"
}

# --- 1. JDK 17 + unzip -----------------------------------------------------
log "apt: openjdk-17-jdk-headless, unzip"
sudo_run apt-get update -qq || die "apt-get update failed"
sudo_run apt-get install -y -qq openjdk-17-jdk-headless unzip || die "apt-get install failed"

JAVA_BIN="$(command -v java)" || die "java not on PATH after install"
"$JAVA_BIN" -version 2>&1 | head -1

# --- 2. Android SDK command-line tools -------------------------------------
log "Android SDK cmdline-tools -> $SDK_ROOT"
mkdir -p "$SDK_ROOT/cmdline-tools"
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    TMP="$(mktemp -d)"
    curl -fL "$CMDLINE_TOOLS_ZIP" -o "$TMP/cmdline.zip" || die "cmdline-tools download failed"
    unzip -q "$TMP/cmdline.zip" -d "$TMP/x" || die "cmdline-tools unzip failed"
    # sdkmanager must end up at <sdk>/cmdline-tools/latest/bin/sdkmanager. Locate
    # the extracted dir by name rather than assuming the archive layout: an
    # earlier version extracted into $TMP/x and then moved from $TMP, which is a
    # path that never existed.
    SRC_DIR="$(find "$TMP/x" -maxdepth 3 -type d -name cmdline-tools | head -1)"
    [ -n "$SRC_DIR" ] || die "no cmdline-tools directory found inside the archive"
    mkdir -p "$SDK_ROOT/cmdline-tools"
    mv "$SRC_DIR" "$SDK_ROOT/cmdline-tools/latest" || die "could not move cmdline-tools into place"
    rm -rf "$TMP"
fi
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKMANAGER" ] || die "sdkmanager missing at $SDKMANAGER"

log "SDK packages: platform-35, build-tools 35.0.0, platform-tools"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "platform-tools" || die "sdkmanager install failed"

# --- 3. Gradle 8.11.1 -----------------------------------------------------
log "Gradle $GRADLE_VERSION -> $GRADLE_HOME"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    mkdir -p "$TOOLS_ROOT"
    TMP="$(mktemp -d)"
    curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
        -o "$TMP/gradle.zip" || die "gradle download failed"
    unzip -q "$TMP/gradle.zip" -d "$TOOLS_ROOT" || die "gradle unzip failed"
    rm -rf "$TMP"
fi
[ -x "$GRADLE_HOME/bin/gradle" ] || die "gradle missing at $GRADLE_HOME"

# --- 4. Persist the environment -------------------------------------------
log "writing env to ~/.bashrc.d/aopencode.sh"
mkdir -p "$HOME/.bashrc.d"
cat > "$HOME/.bashrc.d/aopencode.sh" <<EOF
# AOpenCode local build toolchain (generated by scripts/setup-wsl-local-build.sh)
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export GRADLE_USER_HOME="\$HOME/.gradle"
export PATH="$GRADLE_HOME/bin:\$ANDROID_HOME/platform-tools:\$PATH"
EOF
grep -q 'bashrc.d/aopencode.sh' "$HOME/.bashrc" 2>/dev/null || \
    printf '\n[ -f "$HOME/.bashrc.d/aopencode.sh" ] && . "$HOME/.bashrc.d/aopencode.sh"\n' >> "$HOME/.bashrc"

# --- 5. Verify -----------------------------------------------------------
# Source the env file directly. `bash -lc` is a login shell and does not read
# ~/.bashrc, which made an earlier version of this check report every tool as
# missing even though the install had succeeded.
log "verification"
. "$HOME/.bashrc.d/aopencode.sh"
echo "java:        $(java -version 2>&1 | head -1)"
echo "gradle:      $(gradle --version 2>/dev/null | awk '/^Gradle /{print $2; exit}')"
echo "SDK:         $ANDROID_HOME"
echo "platforms:   $(ls "$ANDROID_HOME/platforms" 2>/dev/null | tr '\n' ' ')"
echo "build-tools: $(ls "$ANDROID_HOME/build-tools" 2>/dev/null | tr '\n' ' ')"
echo "adb:         $(adb version 2>&1 | head -1)"
log "done"
