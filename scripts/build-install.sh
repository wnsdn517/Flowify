#!/usr/bin/env bash
# Build the debug APK and install it to the connected device in one step, with clean output.
#
# Usage: scripts/build-install.sh [--no-install] [--test] [--serial SERIAL] [--install-sdk]
#                                  [--no-models] [--pull] [--offline] [--force] [--clean]
#   --no-install  Build only; skip adb install/force-stop.
#   --test        Run the unit tests instead of assembling/installing.
#   --serial      Use this adb device/emulator when more than one is connected.
#   --install-sdk Install command-line tools and the required Android SDK platform if missing.
#   --no-models   Skip the language model pack (rooted installs push it into Spotify otherwise).
#   --pull        Fast-forward this branch from its upstream before building (clean tree only).
#   --offline     No network: skip the git update check and build with Gradle --offline.
#   --force       Reinstall the APK and re-push the models even if unchanged since last time.
#   --clean       Clean build (drops Gradle's outputs for this project first).
#
# Every run checks (quietly, non-fatal) whether the upstream branch has new commits. Gradle's
# build cache is on, the model pack is built in the same Gradle run as the APK, and the APK /
# model pack last put on each device are remembered (.build-install/): an unchanged APK is not
# reinstalled and an unchanged pack is not copied again.
#
# Requires a JDK 17 or newer and an Android SDK with platform android-35. JAVA_HOME, ANDROID_SDK_ROOT,
# ANDROID_HOME, local.properties, and PATH are supported; no platform-specific default paths are used.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Termux ships ARM64 adb/aapt2, while the command-line SDK usually contains x86-64 host
# binaries. Prefer the native Termux tools when available so root-enabled phone builds do not
# fail with an ELF/shell syntax error.
if [ -n "${PREFIX:-}" ] && [ -x "$PREFIX/bin/adb" ]; then
  PATH="$PREFIX/bin:$PATH"
  export PATH
fi

DO_INSTALL=1
RUN_TESTS=0
INSTALL_SDK=0
WITH_MODELS=1
DO_PULL=0
OFFLINE=0
FORCE=0
CLEAN=0
ADB_SERIAL="${ANDROID_SERIAL:-}"
GRADLE_ARGS=()
if [ "$(uname -m 2>/dev/null || true)" = "aarch64" ] && command -v aapt2 >/dev/null 2>&1; then
  GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=$(command -v aapt2)")
  GRADLE_ARGS+=("-PSPICY_COMPILE_SDK=${SPICY_COMPILE_SDK:-34}")
fi
while [ "$#" -gt 0 ]; do
  arg="$1"
  case "$arg" in
    --no-install) DO_INSTALL=0 ;;
    --test) RUN_TESTS=1 ;;
    --install-sdk) INSTALL_SDK=1 ;;
    --no-models) WITH_MODELS=0 ;;
    --pull) DO_PULL=1 ;;
    --offline) OFFLINE=1 ;;
    --force) FORCE=1 ;;
    --clean) CLEAN=1 ;;
    --serial)
      shift
      [ "$#" -gt 0 ] || { echo "ERROR: --serial requires a device serial." >&2; exit 1; }
      ADB_SERIAL="$1"
      ;;
    --help|-h)
      sed -n '2,21p' "$0"
      exit 0
      ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
  shift
done

fail_check() {
  echo "ERROR: $1" >&2
  exit 1
}

# Build cache on for every run (also set in gradle.properties); --offline keeps Gradle off the
# network too.
GRADLE_ARGS+=("--build-cache")
[ "$OFFLINE" -eq 1 ] && GRADLE_ARGS+=("--offline")

# Where the last installed APK / model pack hashes are kept, per device.
STAMP_DIR="$ROOT_DIR/.build-install"

sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    # No hashing tool: a value that never matches, so nothing is ever skipped.
    date +%s%N
  fi
}

with_timeout() {
  local seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$seconds" "$@"
  else
    "$@"
  fi
}

# Git: is the upstream branch ahead? With --pull, fast-forward to it. Never fatal unless --pull
# was asked for and cannot be done safely.
git_update() {
  command -v git >/dev/null 2>&1 || return 0
  git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1 || return 0
  local branch remote
  branch="$(git -C "$ROOT_DIR" symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
  if [ -z "$branch" ]; then
    [ "$DO_PULL" -eq 1 ] && fail_check "--pull: HEAD is detached; check out a branch first."
    return 0
  fi
  remote="$(git -C "$ROOT_DIR" config "branch.$branch.remote" 2>/dev/null || true)"
  if [ -z "$remote" ] || ! git -C "$ROOT_DIR" rev-parse --verify --quiet '@{u}' >/dev/null 2>&1; then
    [ "$DO_PULL" -eq 1 ] && fail_check "--pull: branch $branch has no upstream to pull from."
    echo "==> Git: $branch has no upstream; update check skipped"
    return 0
  fi
  if ! with_timeout 20 git -C "$ROOT_DIR" fetch --quiet "$remote" >/dev/null 2>&1; then
    [ "$DO_PULL" -eq 1 ] && fail_check "--pull: could not fetch from $remote."
    echo "==> Git: could not reach $remote; update check skipped"
    return 0
  fi
  local upstream behind ahead
  upstream="$(git -C "$ROOT_DIR" rev-parse --abbrev-ref '@{u}')"
  behind="$(git -C "$ROOT_DIR" rev-list --count 'HEAD..@{u}')"
  ahead="$(git -C "$ROOT_DIR" rev-list --count '@{u}..HEAD')"
  if [ "$behind" -eq 0 ]; then
    echo "==> Git: up to date with $upstream"
    return 0
  fi
  if [ "$DO_PULL" -eq 0 ]; then
    echo "==> Git: $upstream has $behind new commit(s) (run with --pull to update)"
    return 0
  fi
  if [ "$ahead" -gt 0 ]; then
    fail_check "--pull: $branch and $upstream have diverged ($ahead local, $behind upstream); merge or rebase by hand."
  fi
  if ! git -C "$ROOT_DIR" diff --quiet || ! git -C "$ROOT_DIR" diff --cached --quiet; then
    fail_check "--pull: the working tree has uncommitted changes; commit or stash them first."
  fi
  echo "==> Git: pulling $behind commit(s) from $upstream"
  git -C "$ROOT_DIR" log --oneline 'HEAD..@{u}' | head -10 | sed 's/^/      /'
  git -C "$ROOT_DIR" merge --ff-only --quiet '@{u}' || fail_check "--pull: fast-forward failed."
}

if [ "$OFFLINE" -eq 1 ]; then
  [ "$DO_PULL" -eq 1 ] && fail_check "--pull cannot be combined with --offline."
else
  git_update
fi

if [ ! -f "$ROOT_DIR/gradlew" ]; then
  fail_check "Gradle wrapper was not found at $ROOT_DIR/gradlew."
fi

JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ]; then
  if [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  elif [ -x "$JAVA_HOME/bin/java.exe" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java.exe"
  else
    fail_check "JAVA_HOME does not contain a Java executable: $JAVA_HOME"
  fi
elif JAVA_BIN="$(command -v java 2>/dev/null || true)"; then
  :
fi

if [ -z "$JAVA_BIN" ]; then
  fail_check "Java was not found. Install JDK 17+ or set JAVA_HOME to its location."
fi

JAVAC_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC_BIN="$JAVA_HOME/bin/javac"
else
  JAVAC_BIN="$(command -v javac 2>/dev/null || true)"
fi
if [ -z "$JAVAC_BIN" ]; then
  fail_check "javac was not found. A JDK is required; the installed Java runtime alone is insufficient."
fi

if [ -z "${JAVA_HOME:-}" ]; then
  JAVAC_REALPATH="$(readlink -f "$JAVAC_BIN" 2>/dev/null || printf '%s' "$JAVAC_BIN")"
  JDK_HOME="$(cd "$(dirname "$JAVAC_REALPATH")/.." && pwd)"
  if [ -x "$JDK_HOME/bin/java" ]; then
    JAVA_BIN="$JDK_HOME/bin/java"
    export JAVA_HOME="$JDK_HOME"
  fi
fi

JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 17 ]; then
  fail_check "JDK 17+ is required, but $JAVA_BIN reported version ${JAVA_MAJOR:-unknown}."
fi

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_DIR" ] && [ -f "$ROOT_DIR/local.properties" ]; then
  SDK_DIR="$(sed -n 's/^sdk.dir=//p' "$ROOT_DIR/local.properties" | head -n 1)"
  # Java properties escape ':' and '\'; drop the escaping backslash from each pair.
  SDK_DIR="$(printf '%s' "$SDK_DIR" | sed 's/\\\(.\)/\1/g')"
  if command -v cygpath >/dev/null 2>&1; then
    SDK_DIR="$(cygpath -u "$SDK_DIR")"
  fi
fi
if [ -z "$SDK_DIR" ]; then
  for candidate in "$HOME/Android" "$HOME/Android/Sdk" "$HOME/Android/sdk" /usr/lib/android-sdk /opt/android-sdk; do
    if [ -f "$candidate/platforms/android-35/android.jar" ]; then
      SDK_DIR="$candidate"
      break
    fi
  done
fi
if [ -z "$SDK_DIR" ]; then
  for candidate in "$HOME/Android" "$HOME/Android/Sdk" "$HOME/Android/sdk" /usr/lib/android-sdk /opt/android-sdk; do
    if [ -d "$candidate" ]; then
      SDK_DIR="$candidate"
      break
    fi
  done
fi
if [ -z "$SDK_DIR" ]; then
  fail_check "Android SDK was not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or add sdk.dir to local.properties."
fi
if [ ! -d "$SDK_DIR" ]; then
  fail_check "Android SDK directory does not exist: $SDK_DIR"
fi
if [ ! -f "$SDK_DIR/platforms/android-35/android.jar" ]; then
  SDKMANAGER="$(command -v sdkmanager 2>/dev/null || true)"
  if [ -z "$SDKMANAGER" ]; then
    for candidate in "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" \
      "$SDK_DIR/cmdline-tools/bin/sdkmanager" "$SDK_DIR/tools/bin/sdkmanager"; do
      if [ -x "$candidate" ]; then
        SDKMANAGER="$candidate"
        break
      fi
    done
  fi
  if [ "$INSTALL_SDK" -eq 1 ]; then
    if [ -z "$SDKMANAGER" ]; then
      SDK_DIR="$HOME/Android/Sdk"
      TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"
      TOOLS_TMP="$(mktemp -d)"
      TOOLS_ZIP="$TOOLS_TMP/command-line-tools.zip"
      echo "==> Installing Android Command-line Tools into $SDK_DIR"
      if ! mkdir -p "$SDK_DIR"; then
        rm -rf "$TOOLS_TMP"
        fail_check "Cannot create Android SDK directory: $SDK_DIR"
      fi
      if command -v curl >/dev/null 2>&1; then
        if ! curl -fL --retry 3 --output "$TOOLS_ZIP" "$TOOLS_URL"; then
          rm -rf "$TOOLS_TMP"
          fail_check "Could not download Android Command-line Tools from $TOOLS_URL"
        fi
      elif command -v wget >/dev/null 2>&1; then
        if ! wget -q --tries=3 -O "$TOOLS_ZIP" "$TOOLS_URL"; then
          rm -rf "$TOOLS_TMP"
          fail_check "Could not download Android Command-line Tools from $TOOLS_URL"
        fi
      else
        rm -rf "$TOOLS_TMP"
        fail_check "curl or wget is required to download Android Command-line Tools."
      fi
      command -v unzip >/dev/null 2>&1 || {
        rm -rf "$TOOLS_TMP"
        fail_check "unzip is required to install Android Command-line Tools."
      }
      if ! unzip -q "$TOOLS_ZIP" -d "$TOOLS_TMP"; then
        rm -rf "$TOOLS_TMP"
        fail_check "Could not extract Android Command-line Tools."
      fi
      mkdir -p "$SDK_DIR/cmdline-tools"
      rm -rf "$SDK_DIR/cmdline-tools/latest"
      mv "$TOOLS_TMP/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
      rm -rf "$TOOLS_TMP"
      SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
      chmod +x "$SDKMANAGER"
    fi
    echo "==> Installing Android SDK platform android-35"
    SDK_LOG="$(mktemp)"
    if ! yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" "platforms;android-35" >"$SDK_LOG" 2>&1; then
      if [ ! -f "$SDK_DIR/platforms/android-35/android.jar" ]; then
        echo "SDK INSTALL FAILED. Last 40 lines:" >&2
        tail -40 "$SDK_LOG" >&2
        rm -f "$SDK_LOG"
        exit 1
      fi
    fi
    rm -f "$SDK_LOG"
  fi
  if [ ! -f "$SDK_DIR/platforms/android-35/android.jar" ]; then
    if [ "$INSTALL_SDK" -eq 1 ]; then
      fail_check "sdkmanager completed but android-35 was not installed under $SDK_DIR."
    fi
    fail_check "Android SDK platform android-35 is missing under $SDK_DIR. Use --install-sdk if sdkmanager is available."
  fi
fi
export ANDROID_SDK_ROOT="$SDK_DIR"

ADB=()
USE_ROOT_INSTALL=0
if [ "$DO_INSTALL" -eq 1 ]; then
  command -v adb >/dev/null 2>&1 || true
  if [ -n "$ADB_SERIAL" ]; then
    ADB=(-s "$ADB_SERIAL")
  fi
  if command -v adb >/dev/null 2>&1 && adb "${ADB[@]}" get-state >/dev/null 2>&1; then
    :
  elif command -v su >/dev/null 2>&1 && su -c 'id -u' 2>/dev/null | grep -qx 0; then
    USE_ROOT_INSTALL=1
  else
    fail_check "No usable adb device and root pm install is unavailable. Connect one, set ANDROID_SERIAL, or use --no-install."
  fi
fi

echo "==> Environment OK (JDK $JAVA_MAJOR, Android SDK $SDK_DIR)"

if [ "$RUN_TESTS" -eq 1 ]; then
  TEST_TASK=":app:testDebugUnitTest"
  echo "==> Running debug unit tests ($TEST_TASK)"
  LOG="$(mktemp)"
  if ! bash ./gradlew "$TEST_TASK" "${GRADLE_ARGS[@]}" --console=plain >"$LOG" 2>&1; then
    echo "TESTS FAILED. Last 60 lines:" >&2
    tail -60 "$LOG" >&2
    rm -f "$LOG"
    exit 1
  fi
  rm -f "$LOG"
  echo "==> Tests passed."
  exit 0
fi

TASK=":app:assembleDebug"
APK="app/build/outputs/apk/debug/app-debug.apk"
MODEL_ZIP="app/build/language-models/spicyex-language-models-v1.zip"
# Rooted installs push the model pack into Spotify: build it in the same Gradle run as the APK
# (one Gradle start-up instead of two; it is up to date, and instant, when nothing changed).
PUSH_MODELS=0
if [ "$DO_INSTALL" -eq 1 ] && [ "$USE_ROOT_INSTALL" -eq 1 ] && [ "$WITH_MODELS" -eq 1 ]; then
  PUSH_MODELS=1
fi
TASKS=()
[ "$CLEAN" -eq 1 ] && TASKS+=(":app:clean")
TASKS+=("$TASK")
[ "$PUSH_MODELS" -eq 1 ] && TASKS+=(":app:packageLanguageModelPack")

echo "==> Building debug APK (${TASKS[*]})"
BUILD_STARTED=$(date +%s)
LOG="$(mktemp)"
if ! bash ./gradlew "${TASKS[@]}" "${GRADLE_ARGS[@]}" --console=plain >"$LOG" 2>&1; then
  echo "BUILD FAILED. Last 60 lines:" >&2
  tail -60 "$LOG" >&2
  rm -f "$LOG"
  exit 1
fi
rm -f "$LOG"
if [ ! -f "$ROOT_DIR/$APK" ]; then
  fail_check "Gradle completed but the expected APK was not produced: $ROOT_DIR/$APK"
fi
echo "==> Build succeeded in $(( $(date +%s) - BUILD_STARTED ))s: $APK"

if [ "$DO_INSTALL" -eq 0 ]; then
  exit 0
fi

# The device this run installs to, for its stamps.
if [ "$USE_ROOT_INSTALL" -eq 1 ]; then
  TARGET_ID="root-local"
else
  TARGET_ID="adb-${ADB_SERIAL:-$(adb "${ADB[@]}" get-serialno 2>/dev/null | tr -cd 'A-Za-z0-9._-' || true)}"
fi
mkdir -p "$STAMP_DIR"
APK_STAMP="$STAMP_DIR/apk-$TARGET_ID.sha256"
APK_SHA="$(sha_of "$ROOT_DIR/$APK")"

module_installed() {
  if [ "$USE_ROOT_INSTALL" -eq 1 ]; then
    su -c 'pm path com.eza.spicyex' 2>/dev/null | grep -q '^package:'
  else
    adb "${ADB[@]}" shell pm path com.eza.spicyex 2>/dev/null | grep -q '^package:'
  fi
}

APK_CHANGED=1
if [ "$FORCE" -eq 0 ] && [ -f "$APK_STAMP" ] && [ "$(cat "$APK_STAMP")" = "$APK_SHA" ] && module_installed; then
  APK_CHANGED=0
fi

if [ "$APK_CHANGED" -eq 0 ]; then
  echo "==> APK unchanged since the last install on this device; not reinstalling (--force to)"
else
echo "==> Installing to device"
INSTALL_LOG="$(mktemp)"
if [ "$USE_ROOT_INSTALL" -eq 1 ]; then
  if ! su -c "pm install -r --user 0 '$ROOT_DIR/$APK'" >"$INSTALL_LOG" 2>&1; then
    echo "ROOT INSTALL FAILED:" >&2
    cat "$INSTALL_LOG" >&2
    rm -f "$INSTALL_LOG"
    exit 1
  fi
  # Clone/work-profile Spotify installations need the same module package visible in that user.
  su -c 'pm install-existing --user 11 com.eza.spicyex' >>"$INSTALL_LOG" 2>&1 || true
else
  if ! adb "${ADB[@]}" install -r "$APK" >"$INSTALL_LOG" 2>&1; then
  echo "INSTALL FAILED:" >&2
  cat "$INSTALL_LOG" >&2
  rm -f "$INSTALL_LOG"
    exit 1
  fi
fi
if [ "$USE_ROOT_INSTALL" -eq 1 ] || grep -q "^Success" "$INSTALL_LOG"; then
  echo "==> Installed"
else
  echo "INSTALL did not report Success:" >&2
  cat "$INSTALL_LOG" >&2
  rm -f "$INSTALL_LOG"
  exit 1
fi
rm -f "$INSTALL_LOG"
printf '%s\n' "$APK_SHA" >"$APK_STAMP"
fi

# Language models are never inside the APK.  On a rooted phone, install the exact pack produced
# from this checkout into Spotify's sandbox as part of the same operation, so the reading engine
# has its dictionaries without waiting for the in-app download.
MODELS_CHANGED=0
if [ "$USE_ROOT_INSTALL" -eq 1 ] && [ "$PUSH_MODELS" -eq 0 ]; then
  echo "==> Language models skipped (--no-models)"
fi
MODEL_DIR=/data/user/0/com.spotify.music/files/language-models-v1
if [ "$PUSH_MODELS" -eq 1 ]; then
  [ -f "$MODEL_ZIP" ] || fail_check "Gradle completed but the language model pack was not produced: $MODEL_ZIP"
  MODEL_STAMP="$STAMP_DIR/models-$TARGET_ID.sha256"
  MODEL_SHA="$(sha_of "$ROOT_DIR/$MODEL_ZIP")"
  # Same pack as last time, and still there (Spotify's data was not cleared): nothing to copy.
  if [ "$FORCE" -eq 0 ] && [ -f "$MODEL_STAMP" ] && [ "$(cat "$MODEL_STAMP")" = "$MODEL_SHA" ] \
      && su -c "test -f $MODEL_DIR/.ready" 2>/dev/null; then
    echo "==> Language models unchanged on the device; not copied again (--force to)"
  else
  MODELS_CHANGED=1
  MODEL_TMP="$(mktemp -d)"
  unzip -q -o "$MODEL_ZIP" -d "$MODEL_TMP"
  MODEL_FILES=(
    jmdict/JmdictFurigana.txt.gz
    jmdict/JmdictPreferredReadings.txt.gz
    tika/langdetect-20260320.bin
    kuromoji/characterDefinitions.bin
    kuromoji/connectionCosts.bin
    kuromoji/doubleArrayTrie.bin
    kuromoji/tokenInfoDictionary.bin
    kuromoji/tokenInfoFeaturesMap.bin
    kuromoji/tokenInfoPartOfSpeechMap.bin
    kuromoji/tokenInfoTargetMap.bin
    kuromoji/unknownDictionary.bin
  )
  for model_file in "${MODEL_FILES[@]}"; do
    [ -s "$MODEL_TMP/$model_file" ] || fail_check "Language model pack is missing or empty: $model_file"
  done
  if ! su -c "mkdir -p /data/user/0/com.spotify.music/files/language-models-v1.partial && rm -rf /data/user/0/com.spotify.music/files/language-models-v1.partial/* && cp -R '$MODEL_TMP'/. /data/user/0/com.spotify.music/files/language-models-v1.partial/ && touch /data/user/0/com.spotify.music/files/language-models-v1.partial/.ready && rm -rf /data/user/0/com.spotify.music/files/language-models-v1 && mv /data/user/0/com.spotify.music/files/language-models-v1.partial /data/user/0/com.spotify.music/files/language-models-v1"; then
    rm -rf "$MODEL_TMP"
    fail_check "Could not install language models into Spotify's sandbox."
  fi
  SPOTIFY_OWNER="$(su -c 'stat -c %u:%g /data/user/0/com.spotify.music 2>/dev/null' || true)"
  if [ -n "$SPOTIFY_OWNER" ]; then
    su -c "chown -R '$SPOTIFY_OWNER' /data/user/0/com.spotify.music/files/language-models-v1"
  fi
  rm -rf "$MODEL_TMP"
  printf '%s\n' "$MODEL_SHA" >"$MODEL_STAMP"
  echo "==> Language models installed into Spotify user 0"
  fi
fi

if [ "$APK_CHANGED" -eq 0 ] && [ "$MODELS_CHANGED" -eq 0 ]; then
  echo "==> Nothing new on the device; Spotify left running."
  echo "==> Done."
  exit 0
fi

echo "==> Force-stopping Spotify so the module reloads fresh"
if [ "$USE_ROOT_INSTALL" -eq 1 ]; then
  su -c 'am force-stop com.spotify.music' || echo "WARNING: force-stop failed (non-fatal)" >&2
else
  adb "${ADB[@]}" shell am force-stop com.spotify.music || echo "WARNING: force-stop failed (non-fatal)" >&2
fi

echo "==> Done."
