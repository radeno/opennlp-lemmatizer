#!/usr/bin/env bash
#
# Fetch lemmatization resources for a language into a local directory (default ./models).
#
# Usage:
#   ./scripts/fetch-models.sh <lang>            # official Apache OpenNLP POS + lemmatizer models (.bin)
#   ./scripts/fetch-models.sh <lang>-michmech   # richer flat word->lemma dictionary (ODbL)
#   ./scripts/fetch-models.sh <lang>-ud         # flat word->lemma dictionary from Universal Dependencies
#   ./scripts/fetch-models.sh <arg> [dest_dir] [opennlp_version]
#
# Examples:
#   ./scripts/fetch-models.sh sk            -> models/sk-pos.bin, models/sk-lemmas.bin   (Apache OpenNLP)
#   ./scripts/fetch-models.sh sk-michmech   -> models/sk-michmech.txt                    (michmech, ODbL)
#   ./scripts/fetch-models.sh cs-ud         -> models/cs-ud.txt                          (Universal Dependencies)
#
# Both '-michmech' and '-ud' build a flat form->lemma dictionary for the dictionary_lemmatizer filter
# (no part-of-speech, so they cannot disambiguate homonyms in context):
#   '-michmech' = michmech/lemmatization-lists (ODbL); largest coverage, e.g. Slovak (847k forms).
#   '-ud'       = the SAME Universal Dependencies treebanks the OpenNLP models are trained from, with
#                 gold (human-annotated) lemmas. Best for Czech (huge PDT treebank). Per-treebank
#                 license (Czech PDT is CC BY-NC-SA).
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

# --- flat dictionary derived from a Universal Dependencies treebank (gold lemmas) ---
# UD treebanks are the SAME source the OpenNLP models are trained from, in raw CoNLL-U form: every
# token carries a human-annotated FORM / LEMMA / UPOS. We aggregate them into the form<TAB>lemma
# lookup the dictionary_lemmatizer consumes. A form with several lemmas (homonym) is resolved to its
# most frequent lemma in the corpus — e.g. Czech `je` -> `být` (to be). Gold lemmas keep it accurate
# (e.g. cs `tři` -> `tři`, not `třít`), but coverage is bounded by the treebank's vocabulary
# (Czech PDT is huge; the Slovak treebank is much smaller).
if [[ "$ARG" == *-ud ]]; then
  command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required to build the UD dictionary" >&2; exit 1; }
  lang="${ARG%-ud}"
  case "$lang" in
    cs) repo="UD_Czech-PDT" ;;
    sk) repo="UD_Slovak-SNK" ;;
    *)  echo "ERROR: no Universal Dependencies treebank mapped for '${lang}' (known: cs, sk)" >&2; exit 1 ;;
  esac
  out="${DEST}/${lang}-ud.txt"
  dtmp="$(mktemp -d)"; trap 'rm -rf "$dtmp"' EXIT
  api="https://api.github.com/repos/UniversalDependencies/${repo}/contents"
  echo "listing CoNLL-U files in UniversalDependencies/${repo} ..."
  urls="$(curl -fsSL "$api" | python3 -c 'import sys, json; [print(e["download_url"]) for e in json.load(sys.stdin) if e["name"].endswith(".conllu")]')"
  [ -n "$urls" ] || { echo "ERROR: no .conllu files found in ${repo}" >&2; exit 1; }
  i=0
  for u in $urls; do
    i=$((i + 1))
    echo "  downloading $(basename "$u")"
    curl -fsSL -o "${dtmp}/${i}.conllu" "$u"
  done
  echo "aggregating form->lemma (most frequent gold lemma per form) ..."
  python3 - "${dtmp}"/*.conllu "${out}" <<'PY'
import sys, collections
out = sys.argv[-1]
files = sys.argv[1:-1]
count = collections.defaultdict(collections.Counter)   # form(lowercased) -> Counter(lemma)
for path in files:
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line[0] == "#":
                continue
            c = line.rstrip("\n").split("\t")
            if len(c) < 4:
                continue
            tid, form, lemma, upos = c[0], c[1], c[2], c[3]
            if "-" in tid or "." in tid:               # skip multiword ranges & empty nodes
                continue
            if not form or not lemma or form == "_" or lemma == "_" or upos == "PUNCT":
                continue
            count[form.lower()][lemma] += 1
with open(out, "w", encoding="utf-8") as g:
    for form in sorted(count):
        g.write(form + "\t" + count[form].most_common(1)[0][0] + "\n")
PY
  echo "  -> ${out}  ($(wc -l < "${out}" | tr -d ' ') forms, $(du -h "${out}" | cut -f1))"
  echo "  format: <form>\\t<lemma>, gold lemmas from Universal Dependencies (${repo})."
  echo "  License is per-treebank — Czech PDT is CC BY-NC-SA; verify before commercial use."
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
