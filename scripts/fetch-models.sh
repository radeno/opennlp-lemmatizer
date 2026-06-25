#!/usr/bin/env bash
#
# Fetch lemmatization resources for a language into a local directory (default ./models).
#
# Usage:
#   ./scripts/fetch-models.sh <lang>            # official Apache OpenNLP POS + lemmatizer models (.bin)
#   ./scripts/fetch-models.sh <lang>-mte        # flat word->lemma dictionary from MULTEXT-East
#   ./scripts/fetch-models.sh <lang>-mte-pos    # POS-aware form/POS/lemma dictionary from MULTEXT-East
#   ./scripts/fetch-models.sh <lang>-ud         # flat word->lemma dictionary from Universal Dependencies
#   ./scripts/fetch-models.sh <lang>-gender     # prebuilt UPOS+gender POS model + dictionary (Release asset)
#   ./scripts/fetch-models.sh <arg> [dest_dir] [opennlp_version]
#
# Examples:
#   ./scripts/fetch-models.sh sk            -> models/sk-pos.bin, models/sk-lemmas.bin   (Apache OpenNLP)
#   ./scripts/fetch-models.sh sk-mte        -> models/sk-mte.txt                         (MULTEXT-East)
#   ./scripts/fetch-models.sh sk-mte-pos    -> models/sk-mte-pos.txt                     (MULTEXT-East, POS)
#   ./scripts/fetch-models.sh sk-gender     -> models/sk-gender.bin, sk-gender-dict.txt  (gender, Release)
#   ./scripts/fetch-models.sh cs-ud         -> models/cs-ud.txt                          (Universal Dependencies)
#
# '-mte-pos' builds the POS-aware form<TAB>POS<TAB>lemma dictionary for the pos_dictionary_lemmatizer
# filter (the OpenNLP POS tagger disambiguates homonyms, e.g. sk `je` -> `byť` as copula vs `jesť` as
# verb). It is loaded into a compact Lucene FST (~1.5 MB for ~926k Slovak entries).
#
# Both '-mte' and '-ud' build a flat form->lemma dictionary for the dictionary_lemmatizer filter
# (no part-of-speech, so they cannot disambiguate homonyms in context):
#   '-mte' = MULTEXT-East morphosyntactic lexicons (http://nl.ijs.si/ME/), CC BY-SA 4.0 (commercial
#            use OK). Authoritative academic source (michmech was itself derived from it); widest
#            coverage, e.g. Slovak ~922k forms.
#   '-ud'  = the SAME Universal Dependencies treebanks the OpenNLP models are trained from, with gold
#            (human-annotated) lemmas. Best for Czech (huge PDT treebank). Per-treebank license.
#
set -euo pipefail

ARG="${1:?usage: fetch-models.sh <lang> | <lang>-mte | <lang>-ud [dest_dir]}"
DEST="${2:-models}"
mkdir -p "$DEST"

# --- prebuilt gender-aware POS model + dictionary (GitHub Release assets) ---
# A POS model emitting a UPOS+gender tagset plus a `form<TAB>UPOS.gender<TAB>lemma` dictionary, for
# disambiguating gender-homonyms (sk `hrady` -> `hrad`/`hrada`) with pos_dictionary_lemmatizer and
# "pos_format":"native". These are not built from open data on the fly (they need UDPipe + a large
# corpus + training) — they are downloaded as release assets. Reproduce/rebuild them with
# experiments/gender/build-gender-model.sh.
if [[ "$ARG" == *-gender ]]; then
  command -v gunzip >/dev/null 2>&1 || { echo "ERROR: gunzip is required for the gender dictionary" >&2; exit 1; }
  lang="${ARG%-gender}"
  base="https://github.com/radeno/opennlp-lemmatizer/releases/download/${lang}-gender"
  echo "downloading prebuilt '${lang}' gender model + dictionary from ${base} ..."
  curl -fsSL -o "${DEST}/${lang}-gender.bin" "${base}/${lang}-gender.bin"
  curl -fsSL "${base}/${lang}-gender-dict.txt.gz" | gunzip -c > "${DEST}/${lang}-gender-dict.txt"
  echo "  -> ${DEST}/${lang}-gender.bin  ($(du -h "${DEST}/${lang}-gender.bin" | cut -f1))"
  echo "  -> ${DEST}/${lang}-gender-dict.txt  ($(wc -l < "${DEST}/${lang}-gender-dict.txt" | tr -d ' ') entries)"
  echo "  Use with pos_dictionary_lemmatizer + \"pos_format\":\"native\" (also needs ${lang}-lemmas.bin)."
  exit 0
fi

# --- POS-aware form/POS/lemma dictionary from the MULTEXT-East lexicons (CC BY-SA 4.0) ---
# Same source as '-mte', but keeps part of speech so the pos_dictionary_lemmatizer can disambiguate
# homonyms with the OpenNLP POS tagger. We map the MULTEXT-East MSD (morphosyntactic descriptor) to
# the Penn-style tagset the OpenNLP Slovak POS model emits (NN/VB/MD/JJ/CD/RB/IN/CC/PRP/UH), then keep
# one lemma per (form, POS): if a (form, POS) is itself still ambiguous (several lemmas) it is dropped
# so the MaxEnt model handles it rather than guessing. Form keys are lower-cased (chain a `lowercase`
# filter; the filter itself is case-sensitive) while the lemma keeps its case (proper nouns stay
# capitalised, e.g. `dunaji`->`Dunaj`).
if [[ "$ARG" == *-mte-pos ]]; then
  command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required to build the MTE dictionary" >&2; exit 1; }
  command -v gunzip  >/dev/null 2>&1 || { echo "ERROR: gunzip is required to build the MTE dictionary" >&2; exit 1; }
  lang="${ARG%-mte-pos}"
  out="${DEST}/${lang}-mte-pos.txt"
  dtmp="$(mktemp -d)"; trap 'rm -rf "$dtmp"' EXIT
  handle="https://www.clarin.si/repository/xmlui/handle/11356/1041"
  echo "locating MULTEXT-East '${lang}' lexicon in ${handle} ..."
  href="$(curl -fsSL "$handle" | grep -oE "href=\"[^\"]*wfl-${lang}\.txt[^\"]*\"" | head -1 | sed -E 's/^href="//; s/"$//; s/&amp;/\&/g')"
  [ -n "$href" ] || { echo "ERROR: no MULTEXT-East lexicon for '${lang}' (free set: bg cs en et fr hu ro sk sl uk)" >&2; exit 1; }
  url="https://www.clarin.si${href}"
  echo "downloading (CC BY-SA 4.0): ${url}"
  curl -fsSL "$url" | gunzip -c > "${dtmp}/wfl.txt"
  echo "mapping MSD -> Penn POS and collapsing to one lemma per (form, POS) ..."
  python3 - "${dtmp}/wfl.txt" "${out}" <<'PY'
import sys, collections
src, out = sys.argv[1], sys.argv[2]
def penn(msd):
    # The MULTEXT-East MSD is positional (spec: clarinsi/mte-msd, tables/msd-canon-sk.tbl): char 0
    # is the category, char 1 the type. We map the category onto the Penn-style tagset the OpenNLP
    # Slovak POS model actually emits (NN VB MD JJ CD RB IN CC PRP UH); the two sub-distinctions the
    # model honours read char 1. Categories per the spec: N Noun, V Verb, A Adjective, P Pronoun,
    # R Adverb, S Adposition, C Conjunction, M Numeral, Q Particle, I Interjection, X Residual,
    # Y Abbreviation, Z Punctuation.
    cat = msd[0]
    typ = msd[1] if len(msd) > 1 else ''
    if cat == 'N': return 'NN'                              # common + proper noun
    if cat == 'V': return 'MD' if typ in ('a', 'c') else 'VB'   # auxiliary/copula 'byť' -> MD; main/modal -> VB
    if cat == 'A': return 'JJ'                              # adjective (model emits JJ for every degree)
    if cat == 'P': return 'PRP'                             # pronoun (all types)
    if cat == 'R': return 'RB'                              # adverb
    if cat == 'Q': return 'RB'                              # particle (model tags adverb-like)
    if cat == 'S': return 'IN'                              # adposition (preposition)
    if cat == 'C': return 'IN' if typ == 's' else 'CC'      # subordinating -> IN, coordinating -> CC
    if cat == 'M': return 'CD'                              # numeral (cardinal/ordinal/...)
    if cat == 'I': return 'UH'                              # interjection
    return None                                            # X residual, Y abbreviation, Z punctuation -> model
fp = collections.defaultdict(set)     # (form_lower, POS) -> {lemma}
fa = collections.defaultdict(set)     # form_lower -> {lemma} across ALL readings (for the POS-relax row)
with open(src, encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) < 3:
            continue
        form, lemma, msd = p[0], p[1], p[2]
        if not form or not lemma or not msd:
            continue
        fa[form.lower()].add(lemma)
        pos = penn(msd)
        if pos is None:
            continue
        fp[(form.lower(), pos)].add(lemma)   # key lower-cased; lemma keeps its case
kept = excluded = relaxed = 0
with open(out, "w", encoding="utf-8") as g:
    for (form, pos) in sorted(fp):
        if len(fa[form]) == 1:               # single-lemma form -> covered by its `*` row below (no
            continue                         # per-POS rows, so the file stays ~the same size)
        lemmas = fp[(form, pos)]
        if len(lemmas) == 1:                 # multi-lemma form, this POS unambiguous -> keep (disambiguates)
            g.write(form + "\t" + pos + "\t" + next(iter(lemmas)) + "\n")
            kept += 1
        else:
            excluded += 1                    # ambiguous (form, POS) -> dropped (left to the model)
    # POS-relax: one `form<TAB>*<TAB>lemma` row per form with ONE lemma across all its readings, so the
    # filter recovers the lemma when the POS tagger mis-tags such a form (e.g. saunu tagged a verb).
    for form in sorted(fa):
        if len(fa[form]) == 1:
            g.write(form + "\t*\t" + next(iter(fa[form])) + "\n")
            relaxed += 1
sys.stderr.write("  kept %d disambiguating (form,POS), dropped %d ambiguous, %d single-lemma(*) rows\n"
                 % (kept, excluded, relaxed))
PY
  # Merge corpus-frequency homonym resolutions (recovers dropped ambiguous: hradu->hrad, hostia->hosť).
  # Auto-uses the committed default; override with HOMONYMS=<file> (e.g. regenerated from your own corpus
  # via experiments/homonym-resolution/resolve-homonyms.sh for domain-accurate frequencies).
  script_dir="$(cd "$(dirname "$0")" && pwd)"
  hom="${HOMONYMS:-${script_dir}/../experiments/homonym-resolution/${lang}-homonyms.txt}"
  if [ -f "$hom" ]; then
    cat "$hom" >> "${out}"
    echo "  merged $(wc -l < "$hom" | tr -d ' ') homonym resolutions from ${hom}"
  fi
  # Emit in unsigned-byte (LC_ALL=C) order with lower-cased forms = the FST's key order, so the loader
  # streams the file straight into the automaton with no in-heap entry buffer (FstPosDictionaryLemmatizer
  # falls back to an in-heap sort if a dictionary is not in this order). Forms are already lower-cased
  # above; sorting the whole line keeps duplicate (form, POS) keys adjacent for first-wins de-duplication.
  LC_ALL=C sort -u -o "${out}" "${out}"
  echo "  -> ${out}  ($(wc -l < "${out}" | tr -d ' ') entries, $(du -h "${out}" | cut -f1))"
  echo "  format: <form>\\t<POS>\\t<lemma>, from MULTEXT-East (http://nl.ijs.si/ME/), CC BY-SA 4.0."
  echo "  Attribution: Erjavec et al., MULTEXT-East free lexicons 4.0; share-alike if you redistribute."
  exit 0
fi

# --- flat dictionary from the MULTEXT-East morphosyntactic lexicons (CC BY-SA 4.0) ---
# MULTEXT-East (http://nl.ijs.si/ME/) is the authoritative academic source the michmech lists were
# themselves derived from. We download the per-language `form<TAB>lemma<TAB>MSD` lexicon (CLARIN.SI
# "MULTEXT-East free lexicons 4.0", handle 11356/1041, CC BY-SA 4.0 — commercial use OK) and collapse
# it to the `form<TAB>lemma` lookup the dictionary_lemmatizer filter consumes. A form with several
# lemmas (homonym) resolves to the lemma with the most grammatical readings for that form (tiebreak:
# the lemma's overall frequency) — e.g. Slovak `tri` -> `tri` (numeral, 9 readings), not `trieť`
# (verb, 1). The MSD column (fine-grained PoS) is dropped here; the POS-aware path keeps it.
if [[ "$ARG" == *-mte ]]; then
  command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required to build the MTE dictionary" >&2; exit 1; }
  command -v gunzip  >/dev/null 2>&1 || { echo "ERROR: gunzip is required to build the MTE dictionary" >&2; exit 1; }
  lang="${ARG%-mte}"
  out="${DEST}/${lang}-mte.txt"
  dtmp="$(mktemp -d)"; trap 'rm -rf "$dtmp"' EXIT
  handle="https://www.clarin.si/repository/xmlui/handle/11356/1041"
  echo "locating MULTEXT-East '${lang}' lexicon in ${handle} ..."
  href="$(curl -fsSL "$handle" | grep -oE "href=\"[^\"]*wfl-${lang}\.txt[^\"]*\"" | head -1 | sed -E 's/^href="//; s/"$//; s/&amp;/\&/g')"
  [ -n "$href" ] || { echo "ERROR: no MULTEXT-East lexicon for '${lang}' (free set: bg cs en et fr hu ro sk sl uk)" >&2; exit 1; }
  url="https://www.clarin.si${href}"
  echo "downloading (CC BY-SA 4.0): ${url}"
  curl -fsSL "$url" | gunzip -c > "${dtmp}/wfl.txt"
  echo "collapsing form/lemma/MSD -> form/lemma (homonyms -> most grammatical readings) ..."
  python3 - "${dtmp}/wfl.txt" "${out}" <<'PY'
import sys, collections
src, out = sys.argv[1], sys.argv[2]
flc = collections.defaultdict(collections.Counter)   # form(lowercased) -> Counter(lemma -> #MSD readings)
ltot = collections.Counter()                          # lemma -> total entries (frequency proxy)
with open(src, encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) < 3:
            continue
        form, lemma = p[0], p[1]
        if not form or not lemma:
            continue
        flc[form.lower()][lemma] += 1
        ltot[lemma] += 1
with open(out, "w", encoding="utf-8") as g:
    for form in sorted(flc):
        lemma = max(flc[form].items(), key=lambda kv: (kv[1], ltot[kv[0]]))[0]
        g.write(form + "\t" + lemma + "\n")
PY
  echo "  -> ${out}  ($(wc -l < "${out}" | tr -d ' ') forms, $(du -h "${out}" | cut -f1))"
  echo "  format: <form>\\t<lemma>, from MULTEXT-East (http://nl.ijs.si/ME/), CC BY-SA 4.0."
  echo "  Attribution: Erjavec et al., MULTEXT-East free lexicons 4.0; share-alike if you redistribute."
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
