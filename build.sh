#!/usr/bin/env bash
# =============================================================================
#  Screen Time Locker — fully automated, standalone APK build
# =============================================================================
#  Turns this source tree straight into a signed, installable APK using only
#  the Android SDK command-line tools. No Gradle, no Android Studio.
#
#  Pipeline:
#      aapt2 compile -> aapt2 link -> javac -> kotlinc -> d8 -> zip(dex)
#          -> zipalign -> apksigner
#
#  SELF-PROVISIONING
#  -----------------
#  This script never stops just because a tool is missing. For every required
#  tool it tries, in order:
#      1. your environment / a tool already on PATH
#      2. an installed Android SDK (ANDROID_SDK_ROOT, /usr/lib/android-sdk,
#         ~/Android/Sdk, /opt/android-sdk, Termux $PREFIX/opt/android-sdk ...)
#      3. the host package manager  (Termux `pkg`, apt, dnf/yum, pacman,
#         zypper, apk)
#      4. a portable, self-contained download into a local cache
#         (Temurin JDK, Kotlin compiler, Android build-tools, platform android.jar)
#  Anything that cannot be provided is reported clearly instead of crashing
#  halfway through the build.
#
#  Everything it downloads is cached under
#      $TIMELOCK_TOOLCHAIN_DIR  (default ~/.cache/screentime-locker)
#  so subsequent builds are offline and instant.
#
#  ENVIRONMENTS
#  ------------
#  Tested logic targets any Linux userland: plain Ubuntu/Debian, Fedora/RHEL,
#  Arch, openSUSE, Alpine and Android/Termux. Termux is handled natively: the
#  script detects $PREFIX, installs android tools with `pkg`, and uses a pure
#  Python zipalign fallback (Termux has no zipalign package).
#
#  SIGNING
#  -------
#  By default the script uses the project's RELEASE keystore if present at
#  /root/keystore/timelock-release.jks (alias "timelock", password read from
#  /root/keystore/keystore.pass). If that is not available it falls back to the
#  bundled public TEST key (keystore/timelock-test.jks, alias "timelock-test",
#  pass "testkey123"), generating it on demand, and prints a loud warning.
#  Never ship a build signed with the TEST key.
#
#  Override with:
#      TIMELOCK_KEYSTORE_DIR  dir holding the release keystore
#      SIGNING_STORE          path to your .jks / .keystore
#      SIGNING_PASS           keystore + key password
#      SIGNING_ALIAS          key alias inside the keystore
#
#  To force the public test build explicitly:
#      SIGNING_STORE=$PWD/keystore/timelock-test.jks \
#      SIGNING_ALIAS=timelock-test SIGNING_PASS=testkey123 bash build.sh
#
#  USAGE
#  -----
#      bash build.sh                 # build, provisioning anything missing
#      AUTO_INSTALL=0 bash build.sh  # never touch the package manager / network
#      bash build.sh --help
#
#  Optional environment overrides:
#      ANDROID_SDK_ROOT / ANDROID_HOME  Android SDK location (auto-detected)
#      BUILD_TOOLS                      build-tools version dir (e.g. 36.0.0)
#      PLATFORM_VERSION                 platforms dir (e.g. android-34)
#      KOTLIN_STDLIB                    path to kotlin-stdlib.jar
#      KOTLIN_VERSION                   Kotlin compiler version to fetch
#      JAVA_HOME                        JDK to use
#      AAPT2_BIN D8_BIN ZIPALIGN_BIN APKSIGNER_BIN ANDROID_JAR
#                                       pin individual tools explicitly
#      TIMELOCK_TOOLCHAIN_DIR           download cache location
#      AUTO_INSTALL                     0 to disable all auto-provisioning
#      OUT_NAME                         output apk file name
#      VERSION_NAME / VERSION_CODE      per-variant version override
# =============================================================================
set -euo pipefail

# ----- presentation ----------------------------------------------------------
if [ -t 1 ]; then
  C_CYAN=$'\033[1;36m'; C_RED=$'\033[1;31m'; C_YEL=$'\033[1;33m'; C_OFF=$'\033[0m'
else
  C_CYAN=""; C_RED=""; C_YEL=""; C_OFF=""
fi
say()  { printf '%s==> %s%s\n' "$C_CYAN" "$*" "$C_OFF"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '%swarn: %s%s\n' "$C_YEL" "$*" "$C_OFF" >&2; }
die()  { printf '%sERROR: %s%s\n' "$C_RED" "$*" "$C_OFF" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

usage() {
  sed -n '2,90p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}
case "${1:-}" in
  -h|--help|help) usage ;;
esac

# ----- locate ourselves ------------------------------------------------------
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build"
WORK="$ROOT/.build"
MIN_API=26
TARGET_API=34
OUT_NAME="${OUT_NAME:-timelock-locker.apk}"
AUTO_INSTALL="${AUTO_INSTALL:-1}"

# Optional per-variant version override. The manifest carries a default
# versionCode/versionName; when either is supplied here aapt2 rewrites them at
# link time (--replace-version), so one source tree can ship a release build and
# a public/test build with different version identities.
VERSION_NAME="${VERSION_NAME:-}"
VERSION_CODE="${VERSION_CODE:-}"
VER_OVERRIDE=()
if [ -n "$VERSION_NAME" ] || [ -n "$VERSION_CODE" ]; then
  VER_OVERRIDE+=(--replace-version)
  [ -n "$VERSION_CODE" ] && VER_OVERRIDE+=(--version-code "$VERSION_CODE")
  [ -n "$VERSION_NAME" ] && VER_OVERRIDE+=(--version-name "$VERSION_NAME")
fi

# ----- machine / arch --------------------------------------------------------
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64)   ARCH_X64=1 ;;
  *)              ARCH_X64=0 ;;
esac
IS_TERMUX=0
[ -n "${PREFIX:-}" ] && [ -x "${PREFIX:-}/bin/pkg" ] && IS_TERMUX=1

# ----- toolchain cache -------------------------------------------------------
TOOLCHAIN_DIR="${TIMELOCK_TOOLCHAIN_DIR:-}"
if [ -z "$TOOLCHAIN_DIR" ]; then
  if [ -n "${XDG_CACHE_HOME:-}" ]; then
    TOOLCHAIN_DIR="$XDG_CACHE_HOME/screentime-locker"
  elif [ -n "${HOME:-}" ]; then
    TOOLCHAIN_DIR="$HOME/.cache/screentime-locker"
  else
    TOOLCHAIN_DIR="$ROOT/.toolchain"
  fi
fi
mkdir -p "$TOOLCHAIN_DIR" 2>/dev/null || { TOOLCHAIN_DIR="$ROOT/.toolchain"; mkdir -p "$TOOLCHAIN_DIR"; }

# =============================================================================
#  Generic helpers: download / extract / zip
# =============================================================================

# fetch <url> <dest> — cached, tries curl -> wget -> python3. Returns 0 on ok.
fetch() {
  local url="$1" dest="$2" tmp="$2.part"
  if [ -s "$dest" ]; then info "cached   : $dest"; return 0; fi
  mkdir -p "$(dirname "$dest")"
  rm -f "$tmp"
  info "download : $url"
  if have curl; then
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$tmp" "$url" >/dev/null 2>&1 || { rm -f "$tmp"; return 1; }
  elif have wget; then
    wget -q --tries=3 --timeout=30 -O "$tmp" "$url" >/dev/null 2>&1 || { rm -f "$tmp"; return 1; }
  elif have python3; then
    python3 - "$url" "$tmp" <<'PY' || { rm -f "$tmp"; return 1; }
import sys, urllib.request
urllib.request.urlretrieve(sys.argv[1], sys.argv[2])
PY
  else
    warn "no downloader available (need curl, wget or python3)"
    return 1
  fi
  mv -f "$tmp" "$dest"
  return 0
}

# extract_zip <zip> <destdir> — unzip -> python3 -> jar.
extract_zip() {
  local z="$1" d="$2"
  mkdir -p "$d"
  if have unzip; then
    unzip -q -o "$z" -d "$d" && return 0
  fi
  if have python3; then
    python3 - "$z" "$d" <<'PY' && return 0
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as zf:
    zf.extractall(sys.argv[2])
PY
  fi
  if have jar; then
    ( cd "$d" && jar xf "$z" ) && return 0
  fi
  warn "cannot extract $z (need unzip, python3 or jar)"
  return 1
}

# extract_tar <tarball> <destdir> — tar, else python3 tarfile.
extract_tar() {
  local t="$1" d="$2"
  mkdir -p "$d"
  if have tar; then
    tar -xf "$t" -C "$d" && return 0
  fi
  if have python3; then
    python3 - "$t" "$d" <<'PY' && return 0
import sys, tarfile
with tarfile.open(sys.argv[1]) as tf:
    tf.extractall(sys.argv[2])
PY
  fi
  warn "cannot extract $t (need tar or python3)"
  return 1
}

# zip_add <archive> <file> — add file at archive root. zip -> python3 -> jar.
zip_add() {
  local a="$1" f="$2" dir base
  dir="$(cd "$(dirname "$f")" && pwd)"; base="$(basename "$f")"
  a="$(cd "$(dirname "$a")" && pwd)/$(basename "$a")"
  if have zip; then
    ( cd "$dir" && zip -j -X -q "$a" "$base" ) && return 0
  fi
  if have python3; then
    python3 - "$a" "$f" <<'PY' && return 0
import sys, os, zipfile
arc, src = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(arc, 'a', zipfile.ZIP_DEFLATED) as zf:
    zf.write(src, arcname=os.path.basename(src))
PY
  fi
  if have jar; then
    ( cd "$dir" && jar uf "$a" "$base" ) && return 0
  fi
  warn "cannot add $base to $a (need zip, python3 or jar)"
  return 1
}

# =============================================================================
#  Package manager
# =============================================================================
PM="none"
if [ "$IS_TERMUX" = 1 ]; then
  PM=termux
elif have apt-get; then
  PM=apt
elif have dnf; then
  PM=dnf
elif have yum; then
  PM=yum
elif have pacman && [ -x /usr/bin/pacman ]; then   # avoid /usr/games/pacman
  PM=pacman
elif have zypper; then
  PM=zypper
elif have apk; then
  PM=apk
fi

if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
elif have sudo; then
  SUDO="sudo -n"
else
  SUDO="__NO_ROOT__"
fi

run_pm() {
  # shellcheck disable=SC2086
  case "$PM" in
    termux) pkg install -y "$@" ;;
    apt)    DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y --no-install-recommends "$@" ;;
    dnf)    $SUDO dnf install -y "$@" ;;
    yum)    $SUDO yum install -y "$@" ;;
    pacman) $SUDO pacman -S --noconfirm --needed "$@" ;;
    zypper) $SUDO zypper --non-interactive install "$@" ;;
    apk)    $SUDO apk add "$@" ;;
    *)      return 1 ;;
  esac
}

pm_install() {
  [ "$AUTO_INSTALL" = 1 ] || return 1
  [ "$PM" != none ] || return 1
  if [ "$SUDO" = "__NO_ROOT__" ] && [ "$PM" != termux ]; then
    warn "no root / passwordless sudo — skipping $PM install of: $*"
    return 1
  fi
  if run_pm "$@" >/dev/null 2>&1; then return 0; fi
  # One retry after refreshing metadata (common on fresh Debian/Ubuntu images).
  # shellcheck disable=SC2086
  case "$PM" in
    apt)    DEBIAN_FRONTEND=noninteractive $SUDO apt-get update >/dev/null 2>&1 || true ;;
    dnf)    $SUDO dnf makecache >/dev/null 2>&1 || true ;;
    zypper) $SUDO zypper --non-interactive refresh >/dev/null 2>&1 || true ;;
    apk)    $SUDO apk update >/dev/null 2>&1 || true ;;
  esac
  run_pm "$@" >/dev/null 2>&1
}

try_pkgs() {
  local p
  for p in "$@"; do
    if pm_install "$p"; then info "installed package: $p"; return 0; fi
  done
  return 1
}

# =============================================================================
#  Java (javac + keytool)
# =============================================================================
ensure_java() {
  if have javac && have keytool; then
    if [ -z "${JAVA_HOME:-}" ] && have java; then
      local jb; jb="$(command -v javac)"
      JAVA_HOME="$(cd "$(dirname "$jb")/.." && pwd)" && export JAVA_HOME
    fi
    return 0
  fi
  say "provisioning a JDK (javac/keytool not found)"

  case "$PM" in
    termux) try_pkgs openjdk-21 openjdk-17 openjdk-25 || true ;;
    apt)    try_pkgs openjdk-17-jdk-headless default-jdk-headless openjdk-21-jdk-headless || true ;;
    dnf|yum) try_pkgs java-17-openjdk-devel java-21-openjdk-devel || true ;;
    pacman) try_pkgs jdk17-openjdk jdk-openjdk || true ;;
    zypper) try_pkgs java-17-openjdk-devel java-21-openjdk-devel || true ;;
    apk)    try_pkgs openjdk17 openjdk21 || true ;;
  esac
  hash -r 2>/dev/null || true
  if have javac && have keytool; then return 0; fi

  # Portable Temurin JDK (glibc/x64-ARM Linux). Not usable under Termux/bionic.
  local adarch=""
  case "$ARCH" in
    x86_64|amd64) adarch=x64 ;;
    aarch64|arm64) adarch=aarch64 ;;
    armv7l|armv8l|arm) adarch=arm ;;
    ppc64le) adarch=ppc64le ;;
    s390x) adarch=s390x ;;
    riscv64) adarch=riscv64 ;;
  esac
  if [ -n "$adarch" ] && [ "$IS_TERMUX" = 0 ]; then
    local tarball="$TOOLCHAIN_DIR/jdk17-$adarch.tar.gz"
    local url="https://api.adoptium.net/v3/binary/latest/17/ga/linux/${adarch}/jdk/hotspot/normal/eclipse"
    if fetch "$url" "$tarball"; then
      rm -rf "$TOOLCHAIN_DIR/jdk17-$adarch"
      mkdir -p "$TOOLCHAIN_DIR/jdk17-$adarch"
      if extract_tar "$tarball" "$TOOLCHAIN_DIR/jdk17-$adarch"; then
        local jc
        jc="$(find "$TOOLCHAIN_DIR/jdk17-$adarch" -maxdepth 4 -type f -name javac 2>/dev/null | head -1)"
        if [ -n "$jc" ]; then
          JAVA_HOME="$(cd "$(dirname "$jc")/.." && pwd)"
          export JAVA_HOME
          export PATH="$JAVA_HOME/bin:$PATH"
          info "using downloaded JDK: $JAVA_HOME"
        fi
      fi
    fi
  fi

  have javac && have keytool || die "could not obtain a JDK. Install one manually (e.g. openjdk-17-jdk) or set JAVA_HOME."
}

# =============================================================================
#  Kotlin compiler + stdlib
# =============================================================================
KOTLIN_VERSION="${KOTLIN_VERSION:-2.0.21}"
KOTLINC="${KOTLINC:-}"
KOTLIN_STDLIB="${KOTLIN_STDLIB:-}"

kotlin_stdlib_for() { # given a kotlinc path, echo the stdlib jar if it exists
  local kc="$1" d cand real
  # Follow symlinks: distro packages symlink /usr/bin/kotlinc into a tree that
  # carries lib/kotlin-stdlib.jar (e.g. /usr/share/kotlin/kotlinc).
  real="$(readlink -f "$kc" 2>/dev/null || echo "$kc")"
  local roots=()
  roots+=( "$(cd "$(dirname "$real")/.." && pwd)" )
  if [ "$real" != "$kc" ]; then
    roots+=( "$(cd "$(dirname "$kc")/.." && pwd)" )
  fi
  roots+=( /usr/share/kotlin/kotlinc /usr/local/share/kotlin/kotlinc /opt/kotlin/kotlinc )
  for d in "${roots[@]}"; do
    for cand in "$d/lib/kotlin-stdlib.jar" "$d/lib/kotlin-stdlib-jdk8.jar"; do
      [ -f "$cand" ] && { echo "$cand"; return 0; }
    done
  done
  # last resort: anything under the cache download dir
  cand="$(find "$TOOLCHAIN_DIR" -maxdepth 5 -type f -name 'kotlin-stdlib.jar' 2>/dev/null | head -1)"
  [ -n "$cand" ] && { echo "$cand"; return 0; }
  return 1
}

ensure_kotlin() {
  if [ -n "$KOTLINC" ] && [ -x "$KOTLINC" ]; then
    [ -z "$KOTLIN_STDLIB" ] && KOTLIN_STDLIB="$(kotlin_stdlib_for "$KOTLINC" || true)"
    return 0
  fi
  KOTLINC="$(command -v kotlinc || true)"
  if [ -z "$KOTLINC" ]; then
    say "provisioning the Kotlin compiler"
    case "$PM" in
      termux) try_pkgs kotlin || true ;;
      apt)    try_pkgs kotlin || true ;;
      dnf|yum) try_pkgs kotlin || true ;;
      pacman) try_pkgs kotlin || true ;;
      zypper) try_pkgs kotlin || true ;;
      apk)    try_pkgs kotlin || true ;;
    esac
    hash -r 2>/dev/null || true
    KOTLINC="$(command -v kotlinc || true)"
  fi
  if [ -z "$KOTLINC" ]; then
    local zip="$TOOLCHAIN_DIR/kotlin-compiler-$KOTLIN_VERSION.zip"
    local url="https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"
    if fetch "$url" "$zip"; then
      rm -rf "$TOOLCHAIN_DIR/kotlin-$KOTLIN_VERSION"
      mkdir -p "$TOOLCHAIN_DIR/kotlin-$KOTLIN_VERSION"
      if extract_zip "$zip" "$TOOLCHAIN_DIR/kotlin-$KOTLIN_VERSION"; then
        local kc
        kc="$(find "$TOOLCHAIN_DIR/kotlin-$KOTLIN_VERSION" -maxdepth 3 -type f -name kotlinc 2>/dev/null | head -1)"
        [ -n "$kc" ] && { chmod +x "$kc" 2>/dev/null || true; KOTLINC="$kc"; }
      fi
    fi
  fi
  [ -n "$KOTLINC" ] || die "could not obtain kotlinc. Install Kotlin or set KOTLINC."
  [ -z "$KOTLIN_STDLIB" ] && KOTLIN_STDLIB="$(kotlin_stdlib_for "$KOTLINC" || true)"
  [ -n "$KOTLIN_STDLIB" ] && [ -f "$KOTLIN_STDLIB" ] || die "kotlin-stdlib.jar not found (set KOTLIN_STDLIB)."
}

# =============================================================================
#  Android SDK: build-tools (aapt2/d8/zipalign/apksigner) + platform android.jar
# =============================================================================
AAPT2="${AAPT2_BIN:-}"
D8="${D8_BIN:-}"
ZIPALIGN="${ZIPALIGN_BIN:-}"
APKSIGNER="${APKSIGNER_BIN:-}"
AJD="${ANDROID_JAR:-}"
BT=""

detect_sdk() {
  local c
  [ -n "${SDK:-}" ] && return 0
  for c in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
           /usr/lib/android-sdk /opt/android-sdk /usr/local/lib/android/sdk \
           "${PREFIX:-}/opt/android-sdk" "$HOME/Android/Sdk" \
           "$HOME/Library/Android/sdk" "$HOME/android-sdk"; do
    [ -n "$c" ] && [ -d "$c" ] && { SDK="$c"; return 0; }
  done
  return 1
}
SDK=""

pick_build_tools() {
  [ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || return 1
  if [ -n "${BUILD_TOOLS:-}" ] && [ -f "$SDK/build-tools/$BUILD_TOOLS/aapt2" ]; then
    BT="$SDK/build-tools/$BUILD_TOOLS"; return 0
  fi
  BT="$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 -type d -name '[0-9]*' 2>/dev/null \
        | while read -r d; do [ -x "$d/aapt2" ] && echo "$d"; done | sort -V | tail -1)"
  [ -n "$BT" ] && [ -x "$BT/aapt2" ]
}

pick_platform() {
  [ -n "$SDK" ] && [ -d "$SDK/platforms" ] || return 1
  local p=""
  if [ -n "${PLATFORM_VERSION:-}" ] && [ -f "$SDK/platforms/$PLATFORM_VERSION/android.jar" ]; then
    AJD="$SDK/platforms/$PLATFORM_VERSION/android.jar"; return 0
  fi
  p="$(find "$SDK/platforms" -maxdepth 1 -mindepth 1 -type d 2>/dev/null \
       | grep -E '/android-[0-9]+$' | sort -V | tail -1)"
  [ -n "$p" ] && [ -f "$p/android.jar" ] && { AJD="$p/android.jar"; return 0; }
  return 1
}

# Download the upstream Android build-tools zip (linux x86_64 only) if needed.
provision_build_tools() {
  [ "$ARCH_X64" = 1 ] || return 1
  local v url base dest
  for v in "${BUILD_TOOLS:-}" 36.0.0 35.0.0 34.0.0; do
    [ -n "$v" ] || continue
    case "$v" in
      36.0.0) url="https://dl.google.com/android/repository/build-tools_r36_linux.zip" ;;
      35.0.0) url="https://dl.google.com/android/repository/build-tools_r35_linux.zip" ;;
      34.0.0) url="https://dl.google.com/android/repository/build-tools_r34-linux.zip" ;;
      *)      continue ;;
    esac
    base="$(basename "$url")"
    if fetch "$url" "$TOOLCHAIN_DIR/$base"; then
      rm -rf "$TOOLCHAIN_DIR/build-tools"
      mkdir -p "$TOOLCHAIN_DIR/build-tools"
      if extract_zip "$TOOLCHAIN_DIR/$base" "$TOOLCHAIN_DIR/build-tools"; then
        local a2
        a2="$(find "$TOOLCHAIN_DIR/build-tools" -maxdepth 2 -type f -name aapt2 2>/dev/null | head -1)"
        if [ -n "$a2" ]; then
          BT="$(dirname "$a2")"; chmod +x "$BT"/* 2>/dev/null || true
          info "using downloaded build-tools: $BT"; return 0
        fi
      fi
    fi
  done
  return 1
}

# Download the platform package to obtain android.jar (arch-independent jar).
provision_platform() {
  local url base
  for url in "https://dl.google.com/android/repository/platform-36_r02.zip" \
             "https://dl.google.com/android/repository/platform-35_r02.zip" \
             "https://dl.google.com/android/repository/platform-34-ext7_r03.zip"; do
    base="$(basename "$url")"
    if fetch "$url" "$TOOLCHAIN_DIR/$base"; then
      rm -rf "$TOOLCHAIN_DIR/platform"
      mkdir -p "$TOOLCHAIN_DIR/platform"
      if extract_zip "$TOOLCHAIN_DIR/$base" "$TOOLCHAIN_DIR/platform"; then
        local j
        j="$(find "$TOOLCHAIN_DIR/platform" -maxdepth 3 -type f -name android.jar 2>/dev/null | head -1)"
        [ -n "$j" ] && { AJD="$j"; info "using downloaded android.jar: $AJD"; return 0; }
      fi
    fi
  done
  return 1
}

install_android_tools_via_pm() {
  # Termux provides aapt2/apksigner/d8 as native packages; other distros expose
  # the SDK pieces under a few different names. Best effort, all failures soft.
  try_pkgs aapt2 android-sdk-build-tools aapt android-sdk-helper || true
  try_pkgs d8 android-sdk-build-tools android-sdk || true
  try_pkgs apksigner android-sdk-build-tools || true
  try_pkgs zipalign android-sdk-build-tools || true
  hash -r 2>/dev/null || true
}

resolve_android() {
  # 1. an SDK already installed on the box
  if detect_sdk; then
    pick_build_tools || true
    pick_platform || true
  fi

  # 2. fill remaining gaps from PATH
  [ -z "$AAPT2" ]     && AAPT2="$(command -v aapt2 || true)"
  [ -z "$D8" ]        && D8="$(command -v d8 || true)"
  [ -z "$ZIPALIGN" ]  && ZIPALIGN="$(command -v zipalign || true)"
  [ -z "$APKSIGNER" ] && APKSIGNER="$(command -v apksigner || true)"

  # 3. under an SDK, prefer its build-tools binaries for version consistency
  if [ -n "$BT" ]; then
    [ -x "$BT/aapt2" ]     && AAPT2="$BT/aapt2"
    [ -f "$BT/d8" ]        && D8="$BT/d8"
    [ -x "$BT/zipalign" ]  && ZIPALIGN="$BT/zipalign"
    [ -f "$BT/apksigner" ] && APKSIGNER="$BT/apksigner"
  fi

  # 4. install via the package manager
  if [ -z "$AAPT2" ] || [ -z "$D8" ] || [ -z "$APKSIGNER" ] || [ -z "$ZIPALIGN" ] || [ -z "$AJD" ]; then
    if [ "$AUTO_INSTALL" = 1 ] && [ "$PM" != none ]; then
      say "provisioning Android build tools"
      install_android_tools_via_pm
      [ -z "$AAPT2" ]     && AAPT2="$(command -v aapt2 || true)"
      [ -z "$D8" ]        && D8="$(command -v d8 || true)"
      [ -z "$ZIPALIGN" ]  && ZIPALIGN="$(command -v zipalign || true)"
      [ -z "$APKSIGNER" ] && APKSIGNER="$(command -v apksigner || true)"
    fi
  fi

  # 5. portable downloads (build-tools zip + platform zip)
  if { [ -z "$AAPT2" ] || [ -z "$D8" ] || [ -z "$APKSIGNER" ] || [ -z "$ZIPALIGN" ]; } \
     && [ "$AUTO_INSTALL" = 1 ]; then
    if provision_build_tools; then
      [ -z "$AAPT2" ]     && AAPT2="$BT/aapt2"
      [ -z "$D8" ]        && D8="$BT/d8"
      [ -z "$ZIPALIGN" ]  && ZIPALIGN="$BT/zipalign"
      [ -z "$APKSIGNER" ] && APKSIGNER="$BT/apksigner"
    fi
  fi
  if [ -z "$AJD" ] && [ "$AUTO_INSTALL" = 1 ]; then
    provision_platform || true
  fi

  # 6. last resort for zipalign: a pure-Python aligner (used on Termux etc.)
  if [ -z "$ZIPALIGN" ] && have python3; then
    ZIPALIGN="$TOOLCHAIN_DIR/zipalign.py"
    if [ ! -s "$ZIPALIGN" ]; then
      cat > "$ZIPALIGN" <<'PY'
#!/usr/bin/env python3
"""Pure-Python zipalign fallback for hosts without the Android zipalign.

Rewrites a zip so every entry's data starts on a 4-byte boundary, using a
valid (ignored) extra field for padding. Understands the zipalign flags we use
(`-f -p 4 in out`); alignment value is taken from the numeric argument.
"""
import struct, sys, zlib, zipfile

def raw_deflate(data):
    """ZIP entries store RAW deflate streams (negative wbits); zlib.compress()
    would emit a zlib-wrapped stream that every unzip/APK parser rejects."""
    co = zlib.compressobj(9, zlib.DEFLATED, -15)
    return co.compress(data) + co.flush()

def dos_time(dt):
    y, mo, d, h, mi, s = dt
    if y < 1980: y, mo, d = 1980, 1, 1
    return ((h << 11) | (mi << 5) | (s // 2)), (((y - 1980) << 9) | (mo << 5) | d)

def main(argv):
    args = [a for a in argv[1:]]
    align = 4
    pos = []
    i = 0
    while i < len(args):
        a = args[i]
        if a in ('-f', '-p', '-v', '-c', '-P'):
            i += 1; continue
        if a.isdigit():
            align = int(a); i += 1; continue
        pos.append(a); i += 1
    if len(pos) < 2:
        sys.stderr.write("usage: zipalign.py [-f] [-p] <align> <in> <out>\n"); return 2
    src, dst = pos[-2], pos[-1]

    zin = zipfile.ZipFile(src, 'r')
    central = []
    with open(dst, 'wb') as out:
        for zi in zin.infolist():
            name = zi.filename.encode('utf-8')
            data = zin.read(zi.filename)
            if zi.compress_type == zipfile.ZIP_STORED:
                body, method = data, 0
            else:
                body, method = raw_deflate(data), 8
            extra = zi.extra or b''
            mtime, mdate = dos_time(zi.date_time)
            need = (align - ((out.tell() + 30 + len(name) + len(extra)) % align)) % align
            if need:
                if need < 4:
                    need += align if align > 4 else 4
                extra = extra + struct.pack('<HH', 0, need - 4) + b'\x00' * (need - 4)
            flags = zi.flag_bits & ~0x08
            off = out.tell()
            crc = zlib.crc32(data) & 0xffffffff
            out.write(struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, flags, method,
                                  mtime, mdate, crc, len(body), len(data),
                                  len(name), len(extra)))
            out.write(name); out.write(extra); out.write(body)
            central.append((name, extra, flags, method, mtime, mdate, crc,
                            len(body), len(data), off, zi.external_attr))
        cd_off = out.tell()
        for (name, extra, flags, method, mtime, mdate, crc, csz, usz, off, eattr) in central:
            out.write(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 0x031e, 20,
                                  flags, method, mtime, mdate, crc, csz, usz,
                                  len(name), len(extra), 0, 0, 0, eattr, off))
            out.write(name); out.write(extra)
        cd_size = out.tell() - cd_off
        out.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(central),
                              len(central), cd_size, cd_off, 0))
    zin.close()
    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv))
PY
      chmod +x "$ZIPALIGN" 2>/dev/null || true
    fi
  fi

  [ -n "$AAPT2" ]     || die "aapt2 not found and could not be provisioned (set AAPT2_BIN or ANDROID_SDK_ROOT)."
  [ -n "$D8" ]        || die "d8 not found and could not be provisioned (set D8_BIN or ANDROID_SDK_ROOT)."
  [ -n "$APKSIGNER" ] || die "apksigner not found and could not be provisioned (set APKSIGNER_BIN or ANDROID_SDK_ROOT)."
  [ -n "$AJD" ]       || die "android.jar not found and could not be provisioned (set ANDROID_JAR or ANDROID_SDK_ROOT)."
  [ -n "$ZIPALIGN" ]  || warn "zipalign unavailable — the APK will not be 4-byte aligned (still installable)."
}

# =============================================================================
#  Signing key
# =============================================================================
ensure_keystore() {
  local keydir
  keydir="${TIMELOCK_KEYSTORE_DIR:-}"
  if [ -z "$keydir" ]; then
    if [ -f /root/keystore/timelock-release.jks ]; then
      keydir=/root/keystore
    else
      keydir="$ROOT/keystore"
    fi
  fi
  KEYSTORE_DIR="$keydir"

  SIGNING_STORE="${SIGNING_STORE:-$KEYSTORE_DIR/timelock-release.jks}"
  SIGNING_ALIAS="${SIGNING_ALIAS:-timelock}"

  SIGNING_PASS_FILE=""
  if [ -n "${SIGNING_PASS:-}" ]; then
    :  # explicit password wins over any on-disk password file
  elif [ -f "$KEYSTORE_DIR/keystore.pass" ]; then
    SIGNING_PASS_FILE="$KEYSTORE_DIR/keystore.pass"
    SIGNING_PASS="$(tr -d '\r\n' < "$SIGNING_PASS_FILE")"
  else
    SIGNING_PASS="testkey123"
  fi

  if [ ! -f "$SIGNING_STORE" ]; then
    if [ "$SIGNING_STORE" = "$ROOT/keystore/timelock-test.jks" ]; then
      say "no TEST keystore found — generating the bundled TEST key"
      mkdir -p "$(dirname "$SIGNING_STORE")"
      keytool -genkeypair -keystore "$SIGNING_STORE" -alias "timelock-test" \
        -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12 \
        -storepass "testkey123" -keypass "testkey123" \
        -dname "CN=Screen Time Locker TEST KEY (DO NOT SHIP), OU=Open Source Test, O=Community, C=US" >/dev/null
      SIGNING_ALIAS="timelock-test"; SIGNING_PASS="testkey123"
    else
      # No release keystore: fall back to the public test key so the build can
      # still complete everywhere. Loud, because this must never be shipped.
      warn "release keystore not found at $SIGNING_STORE"
      warn "falling back to the PUBLIC TEST key — DO NOT SHIP this APK"
      SIGNING_STORE="$ROOT/keystore/timelock-test.jks"
      SIGNING_ALIAS="timelock-test"
      SIGNING_PASS="testkey123"
      SIGNING_PASS_FILE=""
      if [ ! -f "$SIGNING_STORE" ]; then
        mkdir -p "$(dirname "$SIGNING_STORE")"
        keytool -genkeypair -keystore "$SIGNING_STORE" -alias "timelock-test" \
          -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12 \
          -storepass "testkey123" -keypass "testkey123" \
          -dname "CN=Screen Time Locker TEST KEY (DO NOT SHIP), OU=Open Source Test, O=Community, C=US" >/dev/null
      fi
    fi
  fi

  # apksigner reads a `file:` password source line-by-line and consumes one line
  # per request, so the same file cannot serve both --ks-pass and --key-pass
  # (the second read hits EOF). Use the file for the keystore password and the
  # already-extracted literal for the key password; fall back to a literal for
  # both when no password file is in play.
  if [ -n "$SIGNING_PASS_FILE" ]; then
    KS_PASS_ARG="file:$SIGNING_PASS_FILE"
    KEY_PASS_ARG="pass:$SIGNING_PASS"
  else
    KS_PASS_ARG="pass:$SIGNING_PASS"
    KEY_PASS_ARG="pass:$SIGNING_PASS"
  fi
}

# =============================================================================
#  Run the provisioning pipeline
# =============================================================================
say "Screen Time Locker — automated standalone build"
info "host         : ${ARCH}$( [ "$IS_TERMUX" = 1 ] && printf ' (Termux)' ) | pkg-manager: $PM | auto-install: $AUTO_INSTALL"
info "toolchain dir: $TOOLCHAIN_DIR"

ensure_java
ensure_kotlin
resolve_android
ensure_keystore

# ----- bundled third-party classes (Shizuku client + provider) ---------------
LIBDIR="$ROOT/libs"
LIBCP="$(find "$LIBDIR" -name '*.jar' 2>/dev/null | sort | tr '\n' ':')$KOTLIN_STDLIB:"
LIBINPUTS="$(find "$LIBDIR" -name '*.jar' 2>/dev/null | sort | tr '\n' ' ') $KOTLIN_STDLIB"

echo
info "javac        : $(command -v javac)"
info "kotlinc      : $KOTLINC"
info "aapt2        : $AAPT2"
info "d8           : $D8"
[ -n "$ZIPALIGN" ] && info "zipalign     : $ZIPALIGN"
info "apksigner    : $APKSIGNER"
info "android.jar  : $AJD"
info "keystore     : $SIGNING_STORE"
info "output       : $OUT/$OUT_NAME"
echo

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/gen" "$OUT"

# ---- [1/7] resources --------------------------------------------------------
say "[1/7] aapt2 compile resources"
"$AAPT2" compile --dir "$ROOT/res" -o "$WORK/res.zip"

# ---- [2/7] link (base.apk + R.java) -----------------------------------------
say "[2/7] aapt2 link"
"$AAPT2" link \
  -o "$WORK/base.apk" \
  -I "$AJD" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$WORK/gen" \
  --min-sdk-version "$MIN_API" \
  --target-sdk-version "$TARGET_API" \
  --auto-add-overlay \
  ${VER_OVERRIDE[@]+"${VER_OVERRIDE[@]}"} \
  "$WORK/res.zip"

# ---- [3/7] Java -------------------------------------------------------------
# The Kotlin tree SUPERSEDES its Java twin OnboardingActivity.java (same
# fully-qualified name), so the Java copy is excluded here and compiled by
# kotlinc in step 4 instead.
say "[3/7] javac (java/ tree)"
"$(command -v javac)" --release 8 -Xlint:-options -nowarn \
  -classpath "$AJD:$LIBCP" \
  -d "$WORK/classes" \
  $(find "$ROOT/java" "$WORK/gen" -name '*.java' ! -name 'OnboardingActivity.java')

# ---- [4/7] Kotlin -----------------------------------------------------------
say "[4/7] kotlinc (kotlin/ tree)"
"$KOTLINC" -nowarn -jvm-target 1.8 \
  -classpath "$AJD:$WORK/classes:$LIBCP" \
  -d "$WORK/classes" \
  $(find "$ROOT/kotlin" -name '*.kt')

# ---- [5/7] dex --------------------------------------------------------------
say "[5/7] d8 (dex java + kotlin + bundled libs)"
# shellcheck disable=SC2086
"$D8" --release --min-api "$MIN_API" --lib "$AJD" \
  --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class') \
  $LIBINPUTS
zip_add "$WORK/base.apk" "$WORK/dex/classes.dex" \
  || die "failed to add classes.dex to the APK"

# ---- [6/7] align ------------------------------------------------------------
if [ -n "$ZIPALIGN" ]; then
  say "[6/7] zipalign"
  "$ZIPALIGN" -f -p 4 "$WORK/base.apk" "$WORK/aligned.apk"
else
  say "[6/7] zipalign (skipped — not available on this host)"
  cp "$WORK/base.apk" "$WORK/aligned.apk"
fi

# ---- [7/7] sign -------------------------------------------------------------
say "[7/7] apksigner"
"$APKSIGNER" sign \
  --ks "$SIGNING_STORE" \
  --ks-key-alias "$SIGNING_ALIAS" \
  --ks-pass "$KS_PASS_ARG" \
  --key-pass "$KEY_PASS_ARG" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUT/$OUT_NAME" \
  "$WORK/aligned.apk"

rm -rf "$WORK"

say "BUILD OK"
echo "    apk      : $OUT/$OUT_NAME"
echo "    size     : $(stat -c %s "$OUT/$OUT_NAME" 2>/dev/null || stat -f %z "$OUT/$OUT_NAME") bytes"
echo
"$AAPT2" dump badging "$OUT/$OUT_NAME" | grep -E "^package:|application-label:" || true
"$APKSIGNER" verify --print-certs "$OUT/$OUT_NAME" | head -3 || true
echo
echo "Install with:  adb install -r \"$OUT/$OUT_NAME\""
