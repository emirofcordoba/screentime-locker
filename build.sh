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
#  By default this script signs the APK with a bundled TEST key
#  (keystore/timelock-test.jks, password "testkey123"). That key exists ONLY so
#  a fresh clone produces a working, installable build. It is publicly known
#  and must NEVER be used to ship a real release: anyone can sign an APK with
#  it. For a real release, generate your own private keystore and point this
#  script at it with SIGNING_STORE / SIGNING_PASS / SIGNING_ALIAS.
#
#  Usage:  bash build.sh
#
#  Optional environment overrides:
#      ANDROID_SDK_ROOT   Android SDK location (auto-detected otherwise)
#      BUILD_TOOLS        build-tools version dir (e.g. 36.0.0)
#      KOTLIN_STDLIB      path to kotlin-stdlib.jar (auto-detected otherwise)
#      SIGNING_STORE      path to your .jks / .keystore
#      SIGNING_PASS       keystore + key password
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
SIGNING_STORE="${SIGNING_STORE:-$ROOT/keystore/timelock-test.jks}"
SIGNING_PASS="${SIGNING_PASS:-testkey123}"
SIGNING_ALIAS="${SIGNING_ALIAS:-timelock-test}"
if [ ! -f "$SIGNING_STORE" ]; then
  say "No keystore found - generating the bundled TEST key"
  mkdir -p "$(dirname "$SIGNING_STORE")"
  keytool -genkeypair -keystore "$SIGNING_STORE" -alias "$SIGNING_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12 \
    -storepass "$SIGNING_PASS" -keypass "$SIGNING_PASS" \
    -dname "CN=Screen Time Locker TEST KEY (DO NOT SHIP), OU=Open Source Test, O=Community, C=US" >/dev/null
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
  --ks-pass "pass:$SIGNING_PASS" \
  --key-pass "pass:$SIGNING_PASS" \
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
