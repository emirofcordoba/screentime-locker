#!/bin/sh
# =============================================================================
#  Screen Time Locker — fully automated, standalone APK build
# =============================================================================
#  Turns this source tree straight into a signed, installable APK using only
#  the Android SDK command-line tools. No Gradle, no Android Studio.
#
#  Pipeline:
#      aapt2 compile -> aapt2 link -> javac -> kotlinc -> strip(class attrs)
#          -> d8 -> zip(dex) -> zipalign -> apksigner
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
#  D8 / DEX HARDENING
#  ------------------
#  javac annotates the synthetic parameters of enum and inner-class
#  constructors with a `MethodParameters` attribute (JVMS 4.7.24). R8/D8 builds
#  older than ~4.x -- notably the ones shipped by Termux and Debian -- abort
#  while parsing it:
#      NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
#  A dex file carries no parameter names, so build.sh strips every
#  MethodParameters attribute from the compiled classes just before dexing.
#  That is lossless and lets *any* d8 version succeed. Independently, when no
#  usable d8 can be found anywhere, the script downloads a self-contained,
#  architecture-independent R8 jar and runs its D8 entry point through the JDK
#  (works on x86_64, aarch64 and Termux alike).
#
#  Three layers guarantee dexing never stalls the build:
#      1. MethodParameters is stripped from every compiled class before dexing;
#      2. a d8 whose R8 major version is < 4 is auto-upgraded to a downloaded
#         modern R8 (the strip is impossible without python, so this covers
#         hosts that have no python and cannot install one);
#      3. whatever d8 is in use, if the dex step fails the script retries the
#         exact same inputs with the portable R8 jar -- so a broken, ancient or
#         otherwise unusable system d8 can never stop the build.
#
#  ENVIRONMENTS
#  ------------
#  Tested logic targets any Linux userland: plain Ubuntu/Debian, Fedora/RHEL,
#  Arch, openSUSE, Alpine, Parrot and Android/Termux. It runs identically as
#  `./build.sh`, `sh build.sh` or `bash build.sh`: the file starts under POSIX
#  /bin/sh and re-execs itself under bash (installing bash first if needed), so
#  it works even on a bare BusyBox/dash/mksh/ash userland that ships no bash at
#  all. Everything is fetched with only default commands (curl or wget, tar,
#  unzip, a package manager) -- no GNU-only tooling is assumed. Termux is handled
#  natively: the script detects $PREFIX, installs android tools with `pkg`, and
#  uses a pure Python zipalign fallback (Termux has no zipalign package).
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
#      R8_VERSION                       R8/D8 jar version to fetch (def. 8.2.42)
#      PYTHON                           python interpreter to use for fallbacks
#      AUTO_INSTALL                     0 to disable all auto-provisioning
#      OUT_NAME                         output apk file name
#      VERSION_NAME / VERSION_CODE      per-variant version override
# =============================================================================

# ---------------------------------------------------------------------------
#  BASH BOOTSTRAP (POSIX sh)
# ---------------------------------------------------------------------------
#  The build itself is written for bash, but this file is deliberately launched
#  through /bin/sh so it starts anywhere -- even a bare Alpine, BusyBox, dash,
#  Termux, Parrot or Android/mksh userland that ships no bash at all. When the
#  interpreter running us is not bash we re-exec the whole script under bash,
#  installing bash first with whichever package manager is present. Running any
#  of `./build.sh`, `sh build.sh` or `bash build.sh` therefore behaves the same
#  everywhere. Set AUTO_INSTALL=0 to forbid even this bootstrap install.
if [ -z "${BASH_VERSION:-}" ]; then
  _tl_self="$0"
  # Resolve $0 through PATH so `build.sh` (found on PATH, not ./build.sh) still
  # re-execs the real file rather than a same-named entry in the current dir.
  if [ ! -f "$_tl_self" ] && command -v "$_tl_self" >/dev/null 2>&1; then
    _tl_self="$(command -v "$_tl_self")"
  fi
  _tl_have_bash() { command -v bash >/dev/null 2>&1; }
  _tl_sudo=""
  if [ "$(id -u 2>/dev/null || echo 1)" != 0 ] && command -v sudo >/dev/null 2>&1; then
    _tl_sudo="sudo -n"
  fi
  if ! _tl_have_bash && [ "${AUTO_INSTALL:-1}" != 0 ]; then
    if [ -n "${PREFIX:-}" ] && command -v pkg >/dev/null 2>&1; then
      pkg install -y bash >/dev/null 2>&1 || true
    elif command -v apk >/dev/null 2>&1; then
      $_tl_sudo apk add --no-cache bash >/dev/null 2>&1 || true
    elif command -v apt-get >/dev/null 2>&1; then
      DEBIAN_FRONTEND=noninteractive $_tl_sudo apt-get install -y bash >/dev/null 2>&1 || true
    elif command -v dnf >/dev/null 2>&1; then
      $_tl_sudo dnf install -y bash >/dev/null 2>&1 || true
    elif command -v yum >/dev/null 2>&1; then
      $_tl_sudo yum install -y bash >/dev/null 2>&1 || true
    elif command -v pacman >/dev/null 2>&1; then
      $_tl_sudo pacman -S --noconfirm --needed bash >/dev/null 2>&1 || true
    elif command -v zypper >/dev/null 2>&1; then
      $_tl_sudo zypper --non-interactive install bash >/dev/null 2>&1 || true
    fi
    hash -r 2>/dev/null || true
  fi
  if _tl_have_bash && [ -f "$_tl_self" ]; then
    exec bash "$_tl_self" "$@"
  fi
  printf '%s\n' \
    "ERROR: build.sh needs bash, and it could not be found or installed." \
    "Install bash and re-run, for example:" \
    "    Alpine : apk add bash" \
    "    Termux : pkg install bash" \
    "    Debian : apt-get install bash" \
    "or invoke it explicitly:  bash build.sh" >&2
  exit 1
fi

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
have_python() { { [ -n "${PYTHON:-}" ] && [ -x "${PYTHON:-}" ]; } && return 0; have python3 || have python; }
# Version-sort, tolerating minimal `sort` builds (some BusyBox/dash userlands)
# that do not implement -V. Used to pick the newest build-tools / platform.
sortv() { sort -V 2>/dev/null || sort; }
# Run Python with whichever interpreter is actually present (python3 or python),
# honouring an explicit $PYTHON. Returns 127 when there is no Python at all.
pyrun() {
  if [ -n "${PYTHON:-}" ] && [ -x "${PYTHON:-}" ]; then "$PYTHON" "$@"
  elif have python3; then python3 "$@"
  elif have python; then python "$@"
  else return 127; fi
}
# Resolve the java launcher, preferring the JDK we (may have) provisioned.
java_bin() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"; return 0; fi
  command -v java
}

usage() {
  # print the whole leading comment block (everything above the first code line)
  awk 'NR==1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
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

# fetch <url> <dest> — cached. Tries curl, then wget, then python. If every
# available downloader fails (e.g. a BusyBox wget built without TLS, or none is
# present at all) it provisions curl through the package manager and retries
# once. Returns 0 only once a non-empty file has been produced.
fetch() {
  local url="$1" dest="$2" tmp="$2.part"
  if [ -s "$dest" ]; then info "cached   : $dest"; return 0; fi
  mkdir -p "$(dirname "$dest")"
  rm -f "$tmp"
  info "download : $url"
  if fetch_once "$url" "$tmp"; then mv -f "$tmp" "$dest"; return 0; fi
  rm -f "$tmp"
  if [ "$AUTO_INSTALL" = 1 ] && [ "$PM" != none ] && ! have curl; then
    info "no working downloader — provisioning curl"
    ensure_curl || true
    if fetch_once "$url" "$tmp"; then mv -f "$tmp" "$dest"; return 0; fi
    rm -f "$tmp"
  fi
  warn "download failed: $url"
  return 1
}

# fetch_once <url> <out> — a single download attempt across every tool present.
fetch_once() {
  local url="$1" out="$2"
  rm -f "$out"
  if have curl; then
    if curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$out" "$url" >/dev/null 2>&1 && [ -s "$out" ]; then return 0; fi
    rm -f "$out"
  fi
  if have wget; then
    if wget -q --tries=3 --timeout=30 -O "$out" "$url" >/dev/null 2>&1 && [ -s "$out" ]; then return 0; fi
    rm -f "$out"
  fi
  if have_python; then
    if pyrun - "$url" "$out" <<'PY' >/dev/null 2>&1 && [ -s "$out" ]; then return 0; fi
import sys
try:
    from urllib.request import urlretrieve
except ImportError:                      # Python 2
    from urllib import urlretrieve
urlretrieve(sys.argv[1], sys.argv[2])
PY
    rm -f "$out"
  fi
  return 1
}

# extract_zip <zip> <destdir> — unzip -> python3 -> jar.
extract_zip() {
  local z="$1" d="$2"
  mkdir -p "$d"
  if have unzip; then
    unzip -q -o "$z" -d "$d" && return 0
  fi
  if have_python; then
    pyrun - "$z" "$d" <<'PY' && return 0
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
  if have_python; then
    pyrun - "$t" "$d" <<'PY' && return 0
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
  if have_python; then
    pyrun - "$a" "$f" <<'PY' && return 0
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
#  Python (portability fallbacks + class post-processing)
# =============================================================================
PYTHON="${PYTHON:-}"
[ -z "$PYTHON" ] && PYTHON="$(command -v python3 || command -v python || true)"

ensure_python() {
  [ -n "$PYTHON" ] && return 0
  case "$PM" in
    termux)  try_pkgs python || true ;;
    apt)     try_pkgs python3 python-minimal || true ;;
    dnf|yum) try_pkgs python3 || true ;;
    pacman)  try_pkgs python || true ;;
    zypper)  try_pkgs python3 || true ;;
    apk)     try_pkgs python3 || true ;;
  esac
  hash -r 2>/dev/null || true
  PYTHON="$(command -v python3 || command -v python || true)"
  [ -n "$PYTHON" ]
}

# strip_method_parameters <classes-dir-or-file>...
# Remove the MethodParameters attribute (JVMS 4.7.24) from compiled classes.
# javac writes it for the synthetic/mandated parameters of enum constructors and
# of inner / anonymous class constructors. Old R8/D8 builds (<= 3.3.x, e.g. the
# Termux and Debian packages) crash while reading that attribute with
#     NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
# Dex keeps no parameter names, so dropping the attribute is lossless and lets
# even an ancient system d8 produce a correct classes.dex.
strip_method_parameters() {
  [ "$#" -gt 0 ] || return 0
  # Preferred: the Python stripper (fast). Only when Python is genuinely absent
  # or fails do we fall back to the JDK-based stripper below, so a host with no
  # Python (a minimal container, a stripped Termux, ...) still never stalls on an
  # ancient d8.
  if [ -z "$PYTHON" ]; then ensure_python || true; fi
  if [ -n "$PYTHON" ]; then
    if "$PYTHON" - "$@" <<'PY'
import os
import struct
import sys

FIXED_CP_TAGS = {7, 8, 16, 19, 20}          # Class, String, MethodType, Module, Package
HANDLE_TAGS = {15}                          # MethodHandle
FIVE_BYTE_TAGS = {3, 4, 9, 10, 11, 12, 17, 18}
LONG_TAGS = {5, 6}                          # Long, Double (two cp slots)


def parse_cp(buf, off):
    count = struct.unpack_from('>H', buf, off)[0]
    off += 2
    entries = [None] * count
    i = 1
    while i < count:
        tag = buf[off]
        if tag == 1:
            ln = struct.unpack_from('>H', buf, off + 1)[0]
            entries[i] = buf[off + 3:off + 3 + ln].decode('utf-8', 'replace')
            off += 3 + ln
        elif tag in FIXED_CP_TAGS:
            off += 3
        elif tag in HANDLE_TAGS:
            off += 4
        elif tag in FIVE_BYTE_TAGS:
            off += 5
        elif tag in LONG_TAGS:
            off += 9
            i += 1
        else:
            raise ValueError('bad constant pool tag %d' % tag)
        i += 1
    return entries, off


def emit_attrs(buf, off, drop, out):
    n = struct.unpack_from('>H', buf, off)[0]
    off += 2
    kept = bytearray()
    kept_n = 0
    for _ in range(n):
        nb, ln = struct.unpack_from('>HI', buf, off)
        total = 6 + ln
        if nb != drop:
            kept += buf[off:off + total]
            kept_n += 1
        off += total
    out += struct.pack('>H', kept_n)
    out += kept
    return off, kept_n, n


def emit_members(buf, off, drop, out):
    count = struct.unpack_from('>H', buf, off)[0]
    out += buf[off:off + 2]
    off += 2
    removed = 0
    for _ in range(count):
        header = buf[off:off + 6]                 # access, name, descriptor
        off += 6
        body = bytearray()
        off, kept_n, orig_n = emit_attrs(buf, off, drop, body)
        removed += orig_n - kept_n
        out += header
        out += body
    return off, removed


def process(path):
    with open(path, 'rb') as fh:
        buf = fh.read()
    if buf[:4] != b'\xca\xfe\xba\xbe':
        return 0
    entries, off = parse_cp(buf, 8)
    if 'MethodParameters' not in entries:
        return 0
    drop = entries.index('MethodParameters')
    out = bytearray(buf[:off])                # magic + versions + constant pool
    out += buf[off:off + 6]                   # access, this_class, super_class
    off += 6
    icount = struct.unpack_from('>H', buf, off)[0]
    out += buf[off:off + 2 + icount * 2]
    off += 2 + icount * 2
    removed = 0
    for _ in range(2):                        # fields, then methods
        off, r = emit_members(buf, off, drop, out)
        removed += r
    class_attrs = bytearray()
    off, _kept, _orig = emit_attrs(buf, off, drop, class_attrs)
    out += class_attrs
    if off != len(buf):
        raise ValueError('trailing bytes in %s' % path)
    if removed:
        with open(path, 'wb') as fh:
            fh.write(out)
    return removed


def main(argv):
    total = 0
    for root in argv[1:]:
        if os.path.isfile(root) and root.endswith('.class'):
            total += process(root)
            continue
        for dirpath, _dirs, files in os.walk(root):
            for name in files:
                if name.endswith('.class'):
                    p = os.path.join(dirpath, name)
                    try:
                        total += process(p)
                    except Exception as exc:  # noqa: BLE001
                        sys.stderr.write('warn: %s: %s\n' % (p, exc))
    print('MethodParameters attributes stripped: %d' % total)
    return 0


sys.exit(main(sys.argv))
PY
    then
      return 0
    fi
    warn "python MethodParameters strip failed — falling back to the JDK-based stripper"
  fi
  strip_method_parameters_java "$@"
}

# JDK-based MethodParameters stripper — no Python required. Compiles a tiny,
# self-contained Java tool into the toolchain cache on first use and runs it.
# javac/java are guaranteed by ensure_java, so this closes the one hole that
# could still let a 3.x d8 abort the build on a Python-less host.
strip_method_parameters_java() {
  [ "$#" -gt 0 ] || return 0
  local jc jv cache
  jc="$(command -v javac || true)"
  jv="$(java_bin || true)"
  if [ -z "$jc" ] || [ -z "$jv" ]; then
    warn "no JDK available — cannot strip MethodParameters; a 3.x d8 may fail"
    return 0
  fi
  cache="$TOOLCHAIN_DIR/attrstrip"
  mkdir -p "$cache" 2>/dev/null || return 0
  if [ ! -s "$cache/TimelockStrip.class" ]; then
    cat > "$cache/TimelockStrip.java" <<'JAVA'
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public final class TimelockStrip {
    private static int u1(byte[] b, int o) { return b[o] & 0xff; }
    private static int u2(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static long u4(byte[] b, int o) {
        return ((long)(b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
             | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static final class Cp { String[] entries; int end; }

    private static Cp parseCp(byte[] b, int off) {
        int count = u2(b, off); off += 2;
        Cp cp = new Cp();
        cp.entries = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = u1(b, off);
            switch (tag) {
                case 1: {
                    int len = u2(b, off + 1);
                    cp.entries[i] = new String(b, off + 3, len, StandardCharsets.UTF_8);
                    off += 3 + len;
                    break;
                }
                case 7: case 8: case 16: case 19: case 20: off += 3; break;
                case 15: off += 4; break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: off += 5; break;
                case 5: case 6: off += 9; i++; break;
                default: throw new IllegalArgumentException("bad constant pool tag " + tag);
            }
        }
        cp.end = off;
        return cp;
    }

    private static int emitAttrs(byte[] b, int off, int drop, ByteArrayOutputStream out, int[] removed) {
        int n = u2(b, off); off += 2;
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        int keptN = 0;
        for (int k = 0; k < n; k++) {
            int nb = u2(b, off);
            long ln = u4(b, off + 2);
            int total = 6 + (int) ln;
            if (nb == drop) {
                removed[0]++;
            } else {
                kept.write(b, off, total);
                keptN++;
            }
            off += total;
        }
        out.write((keptN >>> 8) & 0xff);
        out.write(keptN & 0xff);
        byte[] kb = kept.toByteArray();
        out.write(kb, 0, kb.length);
        return off;
    }

    private static int emitMembers(byte[] b, int off, int drop, ByteArrayOutputStream out, int[] removed) {
        int count = u2(b, off);
        out.write(b, off, 2); off += 2;
        for (int k = 0; k < count; k++) {
            out.write(b, off, 6); off += 6;
            off = emitAttrs(b, off, drop, out, removed);
        }
        return off;
    }

    private static int process(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        if (b.length < 8 || (b[0] & 0xff) != 0xca || (b[1] & 0xff) != 0xfe
                || (b[2] & 0xff) != 0xba || (b[3] & 0xff) != 0xbe) return 0;
        Cp cp = parseCp(b, 8);
        int drop = -1;
        for (int i = 1; i < cp.entries.length; i++) {
            if ("MethodParameters".equals(cp.entries[i])) { drop = i; break; }
        }
        if (drop < 0) return 0;
        int off = cp.end;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(b, 0, off);
        out.write(b, off, 6); off += 6;
        int icount = u2(b, off);
        out.write(b, off, 2 + icount * 2); off += 2 + icount * 2;
        int[] removed = new int[1];
        off = emitMembers(b, off, drop, out, removed);
        off = emitMembers(b, off, drop, out, removed);
        off = emitAttrs(b, off, drop, out, removed);
        if (off != b.length) throw new IllegalArgumentException("trailing bytes in " + p);
        if (removed[0] > 0) Files.write(p, out.toByteArray());
        return removed[0];
    }

    private static void walk(Path root, List<Path> out) {
        if (Files.isRegularFile(root) && root.toString().endsWith(".class")) { out.add(root); return; }
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path c : ds) {
                if (Files.isDirectory(c)) walk(c, out);
                else if (c.toString().endsWith(".class")) out.add(c);
            }
        } catch (IOException e) { }
    }

    public static void main(String[] args) throws Exception {
        long total = 0;
        for (String a : args) {
            List<Path> files = new ArrayList<>();
            walk(Paths.get(a), files);
            for (Path p : files) {
                try { total += process(p); }
                catch (Exception e) { System.err.println("warn: " + p + ": " + e); }
            }
        }
        System.out.println("MethodParameters attributes stripped: " + total);
    }
}
JAVA
    "$jc" -d "$cache" "$cache/TimelockStrip.java" >/dev/null 2>&1 || true
  fi
  if [ -s "$cache/TimelockStrip.class" ]; then
    "$jv" -cp "$cache" TimelockStrip "$@"
  else
    warn "could not compile the JDK-based stripper — a 3.x d8 may fail"
  fi
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

# Provision curl plus the CA bundle it needs for HTTPS (Alpine, minimal
# containers and stripped userlands frequently ship neither).
ensure_curl() {
  have curl && return 0
  say "provisioning curl"
  pm_install curl ca-certificates || pm_install curl || true
  hash -r 2>/dev/null || true
  have curl
}

# Make sure at least one downloader exists before anything tries to fetch.
ensure_downloader() {
  if have curl || have wget || have_python; then return 0; fi
  say "provisioning a downloader (curl/wget)"
  ensure_curl || true
  have curl && return 0
  pm_install wget ca-certificates || pm_install wget || true
  hash -r 2>/dev/null || true
  if have curl || have wget || have_python; then return 0; fi
  warn "no downloader and the package manager could not provide one — offline provisioning is limited"
  return 0
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

# Remember which tools the caller pinned explicitly: an explicit *_BIN must
# always win over anything later auto-detected (SDK build-tools, PATH, ...).
PIN_AAPT2=0; PIN_D8=0; PIN_ZIPALIGN=0; PIN_APKSIGNER=0; PIN_AJD=0
[ -n "${AAPT2_BIN:-}" ]     && PIN_AAPT2=1
[ -n "${D8_BIN:-}" ]        && PIN_D8=1
[ -n "${ZIPALIGN_BIN:-}" ]  && PIN_ZIPALIGN=1
[ -n "${APKSIGNER_BIN:-}" ] && PIN_APKSIGNER=1
[ -n "${ANDROID_JAR:-}" ]   && PIN_AJD=1

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
        | while read -r d; do [ -x "$d/aapt2" ] && echo "$d"; done | sortv | tail -1)"
  [ -n "$BT" ] && [ -x "$BT/aapt2" ]
}

pick_platform() {
  [ -n "$SDK" ] && [ -d "$SDK/platforms" ] || return 1
  local p=""
  if [ -n "${PLATFORM_VERSION:-}" ] && [ -f "$SDK/platforms/$PLATFORM_VERSION/android.jar" ]; then
    AJD="$SDK/platforms/$PLATFORM_VERSION/android.jar"; return 0
  fi
  p="$(find "$SDK/platforms" -maxdepth 1 -mindepth 1 -type d 2>/dev/null \
       | grep -E '/android-[0-9]+$' | sortv | tail -1)"
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

# Portable, architecture-independent d8: download a self-contained R8 jar and
# expose its D8 entry point as a launcher. Needed when no build-tools/SDK d8 is
# available at all (e.g. Termux on aarch64). Pure Java -> runs wherever a JDK is.
R8_VERSION="${R8_VERSION:-8.2.42}"
provision_r8() {
  [ "$AUTO_INSTALL" = 1 ] || return 1
  java_bin >/dev/null 2>&1 || return 1
  local jar="$TOOLCHAIN_DIR/r8-$R8_VERSION.jar"
  local url="https://maven.google.com/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"
  fetch "$url" "$jar" || return 1
  local w="$TOOLCHAIN_DIR/d8-$R8_VERSION"
  {
    printf '#!/bin/sh\n'
    printf 'exec "%s" -cp "%s" com.android.tools.r8.D8 "$@"\n' "$(java_bin)" "$jar"
  } > "$w"
  chmod +x "$w" 2>/dev/null || true
  D8="$w"
  info "using downloaded R8/D8 $R8_VERSION ($jar)"
  return 0
}

# Echo the R8/D8 MAJOR version of a d8 binary (3 for 3.3.28, 8 for 8.2.42).
# Prints nothing (returns non-zero) when it cannot be determined, in which case
# the caller treats the d8 as "good enough". R8 builds with major < 4 are the
# ones that abort on classes carrying parameter-name info (e.g. the Termux and
# Debian packages), so they are worth replacing when a modern R8 is obtainable.
d8_major() {
  local line v
  line="$("$1" --version 2>&1 | head -n1 || true)"
  v="$(printf '%s\n' "$line" | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -n1 || true)"
  [ -n "$v" ] || return 1
  printf '%s' "${v%%.*}"
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

  # 3. under an SDK, prefer its build-tools binaries for version consistency,
  #    but never clobber a tool the caller pinned explicitly via *_BIN.
  if [ -n "$BT" ]; then
    if [ "$PIN_AAPT2" != 1 ] && [ -x "$BT/aapt2" ]; then AAPT2="$BT/aapt2"; fi
    if [ "$PIN_D8" != 1 ] && [ -f "$BT/d8" ]; then D8="$BT/d8"; fi
    if [ "$PIN_ZIPALIGN" != 1 ] && [ -x "$BT/zipalign" ]; then ZIPALIGN="$BT/zipalign"; fi
    if [ "$PIN_APKSIGNER" != 1 ] && [ -f "$BT/apksigner" ]; then APKSIGNER="$BT/apksigner"; fi
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

  # 5b. universal d8 fallback / upgrade: a portable R8 jar (any arch, needs
  #     only a JDK). Used when there is no d8 at all, and also to replace a
  #     genuinely ancient R8 (< 4.x) that would mis-dex our classes. If the
  #     download is unavailable we keep the existing d8: the MethodParameters
  #     strip in step 5/7 makes even a 3.x d8 work.
  if [ -z "$D8" ]; then
    have java || ensure_java
    provision_r8 || true
  elif [ "$AUTO_INSTALL" = 1 ] && [ "$PIN_D8" != 1 ]; then
    D8_MAJOR="$(d8_major "$D8" || true)"
    if [ -n "$D8_MAJOR" ] && [ "$D8_MAJOR" -lt 4 ] 2>/dev/null; then
      have java || ensure_java
      info "system d8 looks ancient (R8 $D8_MAJOR.x) — preferring a portable R8/D8 build"
      provision_r8 \
        || warn "could not fetch a modern d8 — continuing with the ancient system d8 (MethodParameters will be stripped)"
    fi
  fi

  # 6. last resort for zipalign: a pure-Python aligner (used on Termux etc.)
  if [ -z "$ZIPALIGN" ] && [ -n "$PYTHON" ]; then
    ZIPALIGN="$TOOLCHAIN_DIR/zipalign.py"
    if [ ! -s "$ZIPALIGN" ]; then
      # shebang points at whichever interpreter we actually resolved (python3 or
      # plain python), so this works on hosts that only ship the latter.
      {
        printf '#!%s\n' "$PYTHON"
        cat <<'PY'
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
      } > "$ZIPALIGN"
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

ensure_downloader
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
# Drop the MethodParameters attribute javac writes for enum / inner-class
# constructors; this is what stops ancient Termux/Debian d8 builds (R8 <= 3.3.x)
# from aborting with a NullPointerException. A no-op for modern d8. If the
# resolved d8 still fails for any reason, the exact same inputs are re-dexed with
# the portable R8 jar, so a broken, ancient or otherwise unusable system d8 can
# never stop the build.
dex_into() {
  local out="$1" inputs
  inputs="$(find "$WORK/classes" -name '*.class')"
  # shellcheck disable=SC2086
  if "$D8" --release --min-api "$MIN_API" --lib "$AJD" --output "$out" $inputs $LIBINPUTS; then
    return 0
  fi
  warn "d8 ($D8) failed — retrying with a portable R8/D8 build"
  rm -rf "$out"; mkdir -p "$out"
  have java || ensure_java
  if provision_r8; then
    # shellcheck disable=SC2086
    if "$D8" --release --min-api "$MIN_API" --lib "$AJD" --output "$out" $inputs $LIBINPUTS; then
      return 0
    fi
  else
    warn "no modern R8/D8 could be obtained (offline?) — set D8_BIN to a working d8"
  fi
  return 1
}

say "[5/7] d8 (dex java + kotlin + bundled libs)"
strip_method_parameters "$WORK/classes"
dex_into "$WORK/dex" \
  || die "dexing failed: d8 could not compile the classes, even with the portable R8 fallback"
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
