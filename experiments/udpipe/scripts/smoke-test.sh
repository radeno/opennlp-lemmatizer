#!/usr/bin/env bash
#
# Compile & run examples/SmokeTest.java against the built native binding + a model.
# Proves the whole chain loads and lemmatizes. Run via mise so JAVA_HOME is set:
#   mise exec -- scripts/smoke-test.sh [models/slovak-snk.udpipe]
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL="${1:-${ROOT}/models/slovak-snk.udpipe}"
: "${JAVA_HOME:?run via: mise exec -- scripts/smoke-test.sh}"

OS="$(uname -s)"; [ "$OS" = "Darwin" ] && EXT="dylib" || EXT="so"
[ -f "$ROOT/native/libudpipe_java.$EXT" ] || { echo "missing native/libudpipe_java.$EXT — run scripts/build-native.sh" >&2; exit 1; }
[ -f "$ROOT/lib/udpipe.jar" ] || { echo "missing lib/udpipe.jar — run scripts/build-native.sh" >&2; exit 1; }
[ -f "$MODEL" ] || { echo "missing model '$MODEL' — run scripts/fetch-model.sh slovak-snk" >&2; exit 1; }

OUT="$(mktemp -d)"
"$JAVA_HOME/bin/javac" -cp "$ROOT/lib/udpipe.jar" -d "$OUT" "$ROOT/examples/SmokeTest.java"
"$JAVA_HOME/bin/java" \
  -cp "$ROOT/lib/udpipe.jar:$OUT" \
  -Djava.library.path="$ROOT/native" \
  --enable-native-access=ALL-UNNAMED \
  SmokeTest "$MODEL"
rm -rf "$OUT"
