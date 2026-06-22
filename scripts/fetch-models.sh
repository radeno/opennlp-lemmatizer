#!/usr/bin/env bash
#
# Fetch Apache OpenNLP POS + lemmatizer models for a language from Maven Central
# and drop the raw .bin files into a local directory for testing / deployment.
#
# Usage:
#   ./scripts/fetch-models.sh <lang> [dest_dir] [version]
#
#   lang     : language code as used by the opennlp-models artifacts (e.g. cs, sk)
#   dest_dir : where to put <lang>-pos.bin and <lang>-lemmas.bin   (default: ./models)
#   version  : opennlp-models version                              (default: 1.3.0)
#
# Examples:
#   ./scripts/fetch-models.sh cs                       # -> models/cs-pos.bin, models/cs-lemmas.bin
#   ./scripts/fetch-models.sh sk /path/to/opensearch/config/opennlp
#
set -euo pipefail

LANG_CODE="${1:?usage: fetch-models.sh <lang> [dest_dir] [version]}"
DEST="${2:-models}"
VERSION="${3:-1.3.0}"
BASE="https://repo1.maven.org/maven2/org/apache/opennlp"

mkdir -p "$DEST"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fetch() {
  local task="$1" out="$2"
  local jar="opennlp-models-${task}-${LANG_CODE}-${VERSION}.jar"
  local url="${BASE}/opennlp-models-${task}-${LANG_CODE}/${VERSION}/${jar}"
  echo "downloading ${task} model: ${url}"
  curl -fsSL -o "${tmp}/${jar}" "${url}"
  # each model jar contains exactly one *.bin
  local bin
  bin="$(unzip -Z1 "${tmp}/${jar}" | grep -E '\.bin$' | head -1)"
  if [ -z "${bin}" ]; then
    echo "ERROR: no .bin found inside ${jar}" >&2
    exit 1
  fi
  unzip -o -q "${tmp}/${jar}" "${bin}" -d "${tmp}"
  cp "${tmp}/${bin}" "${DEST}/${out}"
  echo "  -> ${DEST}/${out}  ($(du -h "${DEST}/${out}" | cut -f1))"
}

fetch pos        "${LANG_CODE}-pos.bin"
fetch lemmatizer "${LANG_CODE}-lemmas.bin"

echo "Done. Models for '${LANG_CODE}' in ${DEST}/"
