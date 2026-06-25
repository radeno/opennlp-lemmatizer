#!/usr/bin/env bash
#
# Resolve MULTEXT-East gender/case homonyms that pos_dictionary_lemmatizer otherwise drops, by picking
# the most frequent lemma in a real corpus (UDPipe lemmatises it). Output: <lang>-homonyms.txt, a
# form<TAB>POS<TAB>lemma file that scripts/fetch-models.sh auto-merges into the -mte-pos dictionary.
#
#   ./resolve-homonyms.sh [lang] [corpus-sentences.txt]
#
# With no corpus it downloads a general Slovak Wikipedia corpus (Leipzig). **For a specific domain,
# pass your own one-sentence-per-line corpus** — frequency is domain-sensitive (general text lemmatises
# `plese`->`ples` (dance), a tourism corpus would give `pleso` (lake)).
#
# Prerequisites: experiments/udpipe set up (teacher), python3, gzip, JDK 25 (mise).
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UD="$ROOT/experiments/udpipe"
HERE="$(cd "$(dirname "$0")" && pwd)"
LANG_CODE="${1:-sk}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
: "${JAVA_HOME:?run via: mise exec -- experiments/homonym-resolution/resolve-homonyms.sh}"
EXT="so"; [ "$(uname -s)" = "Darwin" ] && EXT="dylib"
[ -f "$UD/lib/udpipe.jar" ] && [ -f "$UD/native/libudpipe_java.$EXT" ] && [ -f "$UD/models/${LANG_CODE}lovak-snk.udpipe" -o -f "$UD/models/slovak-snk.udpipe" ] \
  || { echo "set up experiments/udpipe first" >&2; exit 1; }
MODEL="$UD/models/slovak-snk.udpipe"

# corpus: user-provided or Leipzig Wikipedia
if [ -n "${2:-}" ]; then SENT="$2"; echo "using corpus: $SENT";
else
  echo "downloading Leipzig ${LANG_CODE} Wikipedia corpus ..."
  curl -fsSL -o "$WORK/c.tar.gz" "https://downloads.wortschatz-leipzig.de/corpora/slk_wikipedia_2021_300K.tar.gz"
  ( cd "$WORK" && tar xzf c.tar.gz ); SENT="$(find "$WORK" -name '*-sentences.txt' | head -1)"
fi

echo "MULTEXT-East lexicon ..."
href="$(curl -fsSL "https://www.clarin.si/repository/xmlui/handle/11356/1041" | grep -oE "href=\"[^\"]*wfl-${LANG_CODE}\.txt[^\"]*\"" | head -1 | sed -E 's/^href="//;s/"$//;s/&amp;/\&/g')"
curl -fsSL "https://www.clarin.si${href}" | gunzip -c > "$WORK/wfl.txt"

echo "UDPipe-lemmatising the corpus in chunks (segfault-safe) ..."
"$JAVA_HOME/bin/javac" -cp "$UD/lib/udpipe.jar" -d "$WORK" "$HERE/UdpipeLemma.java"
mkdir -p "$WORK/ch"; split -l 5000 "$SENT" "$WORK/ch/c_"
for c in "$WORK"/ch/c_*; do
  "$JAVA_HOME/bin/java" -cp "$UD/lib/udpipe.jar:$WORK" -Djava.library.path="$UD/native" \
    --enable-native-access=ALL-UNNAMED UdpipeLemma "$MODEL" "$c" "$c.lem" >/dev/null 2>&1 || true
done
cat "$WORK"/ch/*.lem > "$WORK/pairs.txt"

echo "resolving ambiguous (form, POS) by corpus frequency ..."
python3 - "$WORK/wfl.txt" "$WORK/pairs.txt" "$HERE/${LANG_CODE}-homonyms.txt" <<'PY'
import sys, collections
wfl, pairs, out = sys.argv[1], sys.argv[2], sys.argv[3]
def penn(m):
    c=m[0]; t=m[1] if len(m)>1 else ''
    return {'N':'NN','A':'JJ','P':'PRP','R':'RB','Q':'RB','S':'IN','M':'CD','I':'UH'}.get(c) or \
           ('MD' if c=='V' and t in('a','c') else 'VB' if c=='V' else
            ('IN' if c=='C' and t=='s' else 'CC' if c=='C' else None))
freq=collections.defaultdict(collections.Counter)
for line in open(pairs,encoding='utf-8'):
    p=line.rstrip('\n').split('\t')
    if len(p)==2: freq[p[0]][p[1]]+=1
fp=collections.defaultdict(set)
for line in open(wfl,encoding='utf-8'):
    p=line.rstrip('\n').split('\t')
    if len(p)<3: continue
    form,lemma,msd=p[0],p[1],p[2]
    if not(form and lemma and msd): continue
    pos=penn(msd)
    if pos: fp[(form.lower(),pos)].add(lemma)
res=0
with open(out,'w',encoding='utf-8') as g:
    for (f,pos) in sorted(fp):
        ls=fp[(f,pos)]
        if len(ls)<2: continue
        best=max(ls, key=lambda c: freq.get(f,{}).get(c,0))
        if freq.get(f,{}).get(best,0)>0:
            g.write(f"{f}\t{pos}\t{best}\n"); res+=1
sys.stderr.write("  resolved %d homonyms\n" % res)
PY
echo "  -> $HERE/${LANG_CODE}-homonyms.txt ($(wc -l < "$HERE/${LANG_CODE}-homonyms.txt" | tr -d ' ') entries)"
echo "  fetch-models.sh ${LANG_CODE}-mte-pos will auto-merge it."
