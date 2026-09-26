#!/usr/bin/env bash
# =============================================================================
#  Screen Time Locker — standalone APK build (no Gradle, no Android Studio)
# =============================================================================
#  Turns this source tree straight into a signed, installable APK using only
#  the Android SDK command-line tools. No Gradle, no network access.
#
#  Pipeline:
#      aapt2 compile -> aapt2 link -> javac -> kotlinc -> d8 -> zip(dex)
#          -> zipalign -> apksigner
#
#  Signing
#  -------
#  By default this script signs with the project's RELEASE keystore at
#  /root/keystore/timelock-release.jks (alias "timelock", password read from
#  /root/keystore/keystore.pass). Override the destination directory with
#  TIMELOCK_KEYSTORE_DIR, or the store / password / alias directly with
#  SIGNING_STORE / SIGNING_PASS / SIGNING_ALIAS.
#
#  To reproduce the public test build instead, point SIGNING_STORE at the
#  bundled TEST key and set its alias/password:
#      SIGNING_STORE=$PWD/keystore/timelock-test.jks \
#      SIGNING_ALIAS=timelock-test SIGNING_PASS=testkey123 bash build.sh
#  That test key is publicly known and must NEVER be used to ship a release.
#
#  Usage:  bash build.sh
#
#  Optional environment overrides:
#      ANDROID_SDK_ROOT   Android SDK location (auto-detected otherwise)
#      BUILD_TOOLS        build-tools version dir (e.g. 36.0.0)
#      KOTLIN_STDLIB      path to kotlin-stdlib.jar (auto-detected otherwise)
#      TIMELOCK_KEYSTORE_DIR  dir holding the release keystore (default /root/keystore)
#      SIGNING_STORE      path to your .jks / .keystore
#      SIGNING_PASS       keystore + key password (overrides keystore.pass)
#      SIGNING_ALIAS      key alias inside the keystore
#      OUT_NAME           output apk file name (default timelock-locker.apk)
# =============================================================================
set -euo pipefail

# ----- locate ourselves ------------------------------------------------------
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build"
WORK="$ROOT/.build"
MIN_API=26
TARGET_API=34
OUT_NAME="${OUT_NAME:-timelock-locker.apk}"
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

say() { printf '\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ----- locate the Android SDK ------------------------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  for cand in /usr/lib/android-sdk "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -d "$cand" ] && SDK="$cand" && break
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || die "Android SDK not found. Set ANDROID_SDK_ROOT."

# ----- pick build-tools (highest numeric version that has aapt2) -------------
BTDIR="$SDK/build-tools"
if [ -n "${BUILD_TOOLS:-}" ]; then
  BT="$BTDIR/$BUILD_TOOLS"
else
  BT="$(find "$BTDIR" -maxdepth 1 -mindepth 1 -type d -name '[0-9]*' 2>/dev/null \
        | while read -r d; do [ -x "$d/aapt2" ] && echo "$d"; done | sort -V | tail -1)"
fi
[ -x "$BT/aapt2" ] || die "aapt2 not found under $BTDIR (set BUILD_TOOLS)."

# ----- pick a platform/android.jar (prefer a stable android-NN) --------------
PLATFORM=""
if [ -f "$SDK/platforms/android-36/android.jar" ]; then
  PLATFORM="$SDK/platforms/android-36"
else
  PLATFORM="$(find "$SDK/platforms" -maxdepth 1 -mindepth 1 -type d 2>/dev/null \
        | grep -E '/android-[0-9]+$' | sort -V | tail -1)"
fi
AJD="$PLATFORM/android.jar"
[ -f "$AJD" ] || die "android.jar not found under $SDK/platforms."

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
JAVAC="$(command -v javac || true)"
ZIP="$(command -v zip || true)"
KOTLINC="$(command -v kotlinc || true)"
[ -n "$JAVAC" ]   || die "javac not found (install a JDK)."
[ -n "$ZIP" ]     || die "zip not found."
[ -n "$KOTLINC" ] || die "kotlinc not found (install Kotlin)."

# ----- kotlin stdlib ---------------------------------------------------------
KOTLIN_STDLIB="${KOTLIN_STDLIB:-}"
if [ -z "$KOTLIN_STDLIB" ]; then
  for cand in /usr/share/kotlin/kotlinc/lib/kotlin-stdlib.jar \
              "$(dirname "$(dirname "$(command -v kotlinc || echo /usr/bin/kotlinc)")")/lib/kotlin-stdlib.jar"; do
    [ -f "$cand" ] && KOTLIN_STDLIB="$cand" && break
  done
fi
[ -n "$KOTLIN_STDLIB" ] && [ -f "$KOTLIN_STDLIB" ] || die "kotlin-stdlib.jar not found. Set KOTLIN_STDLIB."

# ----- signing key -----------------------------------------------------------
# Default: the maintainer RELEASE keystore at /root/keystore (RSA-4096,
# SHA384withRSA, alias "timelock"). Its password is read from the root-only
# keystore.pass next to it. Override any of SIGNING_STORE / SIGNING_PASS /
# SIGNING_ALIAS for your own key, or point SIGNING_STORE at the bundled TEST key
# ($ROOT/keystore/timelock-test.jks, alias timelock-test, pass testkey123) to
# reproduce the public test build.
KEYSTORE_DIR="${TIMELOCK_KEYSTORE_DIR:-/root/keystore}"
SIGNING_STORE="${SIGNING_STORE:-$KEYSTORE_DIR/timelock-release.jks}"
SIGNING_ALIAS="${SIGNING_ALIAS:-timelock}"
SIGNING_PASS_FILE=""
if [ -n "${SIGNING_PASS:-}" ]; then
  : # an explicit password wins over the on-disk password file
elif [ -f "$KEYSTORE_DIR/keystore.pass" ]; then
  SIGNING_PASS_FILE="$KEYSTORE_DIR/keystore.pass"
  SIGNING_PASS="$(tr -d '\r\n' < "$SIGNING_PASS_FILE")"
else
  SIGNING_PASS="testkey123"
fi
if [ ! -f "$SIGNING_STORE" ]; then
  if [ "$SIGNING_STORE" = "$ROOT/keystore/timelock-test.jks" ]; then
    say "No TEST keystore found - generating the bundled TEST key"
    mkdir -p "$(dirname "$SIGNING_STORE")"
    keytool -genkeypair -keystore "$SIGNING_STORE" -alias "timelock-test" \
      -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12 \
      -storepass "testkey123" -keypass "testkey123" \
      -dname "CN=Screen Time Locker TEST KEY (DO NOT SHIP), OU=Open Source Test, O=Community, C=US" >/dev/null
  else
    die "Signing keystore not found: $SIGNING_STORE (set SIGNING_STORE or create it)."
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

# ----- bundled third-party classes (Shizuku client + provider) ---------------
LIBDIR="$ROOT/libs"
LIBCP="$(find "$LIBDIR" -name '*.jar' 2>/dev/null | sort | tr '\n' ':')$KOTLIN_STDLIB:"
LIBINPUTS="$(find "$LIBDIR" -name '*.jar' 2>/dev/null | sort | tr '\n' ' ') $KOTLIN_STDLIB"

# -----------------------------------------------------------------------------
say "Screen Time Locker - standalone build"
echo "    SDK          : $SDK"
echo "    build-tools  : $(basename "$BT")"
echo "    android.jar  : $AJD"
echo "    keystore     : $SIGNING_STORE"
echo "    output       : $OUT/$OUT_NAME"

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
"$JAVAC" --release 8 -Xlint:-options -nowarn \
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
( cd "$WORK/dex" && "$ZIP" -j -X -q "$WORK/base.apk" classes.dex )

# ---- [6/7] align ------------------------------------------------------------
say "[6/7] zipalign"
"$ZIPALIGN" -f -p 4 "$WORK/base.apk" "$WORK/aligned.apk"

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
