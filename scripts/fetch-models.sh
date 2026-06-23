#!/usr/bin/env bash
#
# Fetch lemmatization resources for a language into a local directory (default ./models).
#
# Usage:
#   ./scripts/fetch-models.sh <lang>            # official Apache OpenNLP POS + lemmatizer models (.bin)
#   ./scripts/fetch-models.sh <lang>-michmech   # richer flat word->lemma dictionary (ODbL)
#   ./scripts/fetch-models.sh <arg> [dest_dir] [opennlp_version]
#
# Examples:
#   ./scripts/fetch-models.sh sk            -> models/sk-pos.bin, models/sk-lemmas.bin   (Apache OpenNLP)
#   ./scripts/fetch-models.sh sk-michmech   -> models/sk-michmech.txt                    (michmech, ODbL)
#
# The '-michmech' source is michmech/lemmatization-lists (Open Database License): much larger
# coverage than the small official OpenNLP models, but a flat dictionary with no part-of-speech
# (so it cannot disambiguate homonyms). Used by the dictionary-lookup lemmatizer.
#
set -euo pipefail

ARG="${1:?usage: fetch-models.sh <lang> | <lang>-michmech [dest_dir]}"
DEST="${2:-models}"
mkdir -p "$DEST"

# --- richer flat dictionary: michmech/lemmatization-lists (ODbL) ---
# Downloads michmech's `lemma<TAB>form` list and inverts it to the `form<TAB>lemma` lookup the
# dictionary_lemmatizer filter consumes. Homonyms (a form with several lemmas) are resolved to the
# lemma with the largest paradigm (most word forms) — a good "most common reading" heuristic, e.g.
# the Slovak form `je` -> `byť` (to be), not `jesť` (to eat).
if [[ "$ARG" == *-michmech ]]; then
  command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required to build the michmech dictionary" >&2; exit 1; }
  lang="${ARG%-michmech}"
  url="https://raw.githubusercontent.com/michmech/lemmatization-lists/master/lemmatization-${lang}.txt"
  out="${DEST}/${lang}-michmech.txt"
  dtmp="$(mktemp -d)"; trap 'rm -rf "$dtmp"' EXIT
  echo "downloading michmech dictionary (ODbL): ${url}"
  curl -fsSL -o "${dtmp}/raw.txt" "${url}"
  echo "inverting lemma->form to form->lemma (homonyms -> largest paradigm)"
  python3 - "${dtmp}/raw.txt" "${out}" <<'PY'
import sys, collections
raw, out = sys.argv[1], sys.argv[2]
count = collections.Counter()
pairs = []
with open(raw, encoding="utf-8-sig") as f:          # utf-8-sig drops any BOM
    for line in f:
        cols = line.rstrip("\n").split("\t")
        if len(cols) >= 2:
            lemma, form = cols[0].strip(), cols[1].strip().lower()
            if lemma and form:
                count[lemma] += 1
                pairs.append((form, lemma))
best = {}
for form, lemma in pairs:
    if form not in best or count[lemma] > count[best[form]]:
        best[form] = lemma
with open(out, "w", encoding="utf-8") as g:
    for form in sorted(best):
        g.write(form + "\t" + best[form] + "\n")
PY
  echo "  -> ${out}  ($(wc -l < "${out}" | tr -d ' ') forms, $(du -h "${out}" | cut -f1))"
  echo "  format: <form>\\t<lemma>. Open Database License (ODbL) — credit michmech/lemmatization-lists,"
  echo "  share-alike if you redistribute this derived dictionary."
  exit 0
fi

# --- official Apache OpenNLP POS + lemmatizer models (Maven Central) ---
LANG_CODE="$ARG"
VERSION="${3:-1.3.0}"
BASE="https://repo1.maven.org/maven2/org/apache/opennlp"
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

echo "Done. OpenNLP models for '${LANG_CODE}' in ${DEST}/"
