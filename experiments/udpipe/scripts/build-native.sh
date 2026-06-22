#!/usr/bin/env bash
#
# Build the UDPipe JNI binding from source, pinned to a known UDPipe version.
# Produces (matching your current OS/arch):
#   native/libudpipe_java.<dylib|so>   JNI native library
#   lib/udpipe.jar                     Java SWIG wrapper classes (version-matched to the .so/.dylib)
#
# WHY FROM SOURCE:
#   - The only Maven artifact is cz.cuni.mff.ufal.udpipe:udpipe:1.1.0 and it ships
#     NO native library.
#   - ÚFAL's prebuilt binaries predate Apple Silicon, so there is no macOS arm64 build.
#   A JNI .dylib/.so must match the JVM architecture, so we compile locally.
#
# REQUIREMENTS: git, make, a C++ compiler, SWIG, and a JDK (JAVA_HOME with include/jni.h).
# Run it through mise so JAVA_HOME points at the project JDK:
#   mise exec -- scripts/build-native.sh
#
set -euo pipefail

UDPIPE_VERSION="${UDPIPE_VERSION:-1.3.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${ROOT}/.native-build/udpipe-${UDPIPE_VERSION}"

OS="$(uname -s)"
case "$OS" in
  Darwin) LIB_EXT="dylib" ;;
  Linux)  LIB_EXT="so" ;;
  *) echo "ERROR: unsupported OS '$OS' (need Darwin or Linux)" >&2; exit 1 ;;
esac

# --- JDK ---
: "${JAVA_HOME:?Set JAVA_HOME (tip: run via 'mise exec -- scripts/build-native.sh')}"
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
  echo "ERROR: jni.h not found under JAVA_HOME=$JAVA_HOME" >&2; exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"   # the Makefile calls javac/jar directly

# --- SWIG ---
if ! command -v swig >/dev/null 2>&1; then
  echo "ERROR: SWIG is required but missing. Install it:" >&2
  echo "   macOS:   brew install swig" >&2
  echo "   Debian:  sudo apt-get install -y swig" >&2
  exit 1
fi

# --- clone (pinned, shallow) ---
if [ ! -d "$BUILD_DIR/.git" ]; then
  rm -rf "$BUILD_DIR"; mkdir -p "$(dirname "$BUILD_DIR")"
  echo ">> cloning UDPipe v${UDPIPE_VERSION}"
  git clone --depth 1 --branch "v${UDPIPE_VERSION}" https://github.com/ufal/udpipe.git "$BUILD_DIR"
fi

# --- patch: JDK 9+ removed 'javac -source 7' (JDK 25 errors out) ---
perl -0777 -i -pe 's/javac -source 7\b/javac -source 8/g' "$BUILD_DIR/bindings/java/Makefile"

# --- build native lib + jar ---
echo ">> building (JAVA_HOME=$JAVA_HOME)"
( cd "$BUILD_DIR/bindings/java" && make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)" )

# --- collect artifacts ---
mkdir -p "$ROOT/native" "$ROOT/lib"
cp "$BUILD_DIR/bindings/java/libudpipe_java.${LIB_EXT}" "$ROOT/native/"
cp "$BUILD_DIR/bindings/java/udpipe.jar" "$ROOT/lib/"

echo ""
echo "OK:"
echo "  native/libudpipe_java.${LIB_EXT}  -> $(file -b "$ROOT/native/libudpipe_java.${LIB_EXT}")"
echo "  lib/udpipe.jar"
echo ""
echo "Next: scripts/fetch-model.sh slovak-snk   &&   scripts/smoke-test.sh"
