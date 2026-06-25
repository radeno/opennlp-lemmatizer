#!/usr/bin/env bash
#
# Reproducible build of the Slovak gender-aware lemmatization assets:
#   - sk-gender.bin       : OpenNLP POS model emitting a UPOS+gender tagset (NOUN.Masc/Fem/Neut, ...),
#                           distilled from UDPipe over a large raw corpus + gold treebanks
#   - sk-gender-dict.txt   : form<TAB>UPOS.gender<TAB>lemma dictionary from MULTEXT-East
#
# Use them with pos_dictionary_lemmatizer + "pos_format":"native" (see README.md / analyzer.json).
#
# Prerequisites:
#   - JDK 25 (mise), python3, gzip, curl
#   - experiments/udpipe set up (lib/udpipe.jar, native/libudpipe_java.<dylib|so>, models/slovak-snk.udpipe)
#     -> the teacher tagger; run its scripts/build-native.sh + fetch-model.sh first
#
# Re-run when stronger silver data is available (bigger corpus = better gender; see README table 2).
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UD="$ROOT/experiments/udpipe"
OUT="${1:-$ROOT/models}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

command -v python3 >/dev/null || { echo "need python3" >&2; exit 1; }
: "${JAVA_HOME:?run via: mise exec -- experiments/gender/build-gender-model.sh}"
EXT="so"; [ "$(uname -s)" = "Darwin" ] && EXT="dylib"
[ -f "$UD/lib/udpipe.jar" ] && [ -f "$UD/native/libudpipe_java.$EXT" ] && [ -f "$UD/models/slovak-snk.udpipe" ] \
  || { echo "set up experiments/udpipe first (jar + native/$EXT + model)" >&2; exit 1; }

echo "==> resolving OpenNLP classpath"
( cd "$ROOT" && mvn -q -pl core dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" >/dev/null )
CP="$(cat "$WORK/cp.txt")"

# --- shared MSD->UPOS+gender mapping (from the MULTEXT-East spec) + converters ---
cat > "$WORK/conv.py" <<'PY'
import re, sys, html
GEN={'masculine':'Masc','feminine':'Fem','neuter':'Neut','Masc':'Masc','Fem':'Fem','Neut':'Neut'}
CAT={'Noun':'NOUN','Verb':'VERB','Adjective':'ADJ','Pronoun':'PRON','Adverb':'ADV','Adposition':'ADP',
     'Conjunction':'CCONJ','Numeral':'NUM','Particle':'PART','Interjection':'INTJ','Residual':'X','Abbreviation':'X','Punctuation':'PUNCT'}
def load_canon(path):                       # MSD -> "UPOS(.Gender)" from msd-canon-sk.tbl
    m={}
    for line in open(path,encoding='utf-8'):
        c=line.rstrip('\n').split('\t')
        if len(c)<2: continue
        msd,fs=c[0],c[1]; up=CAT.get(fs.split()[0],'X')
        if fs.split()[0]=='Noun' and 'Type=proper' in fs: up='PROPN'
        if fs.split()[0]=='Verb' and ('Type=auxiliary' in fs or 'Type=copula' in fs): up='AUX'
        if fs.split()[0]=='Conjunction' and 'Type=subordinating' in fs: up='SCONJ'
        g=re.search(r'Gender=(masculine|feminine|neuter)',fs)
        m[msd]=up+('.'+GEN[g.group(1)] if g else '')
    return m
def tok(form,tag): return None if not form or '_' in form else form+'_'+tag
def conllu(src,dst):                        # UD CoNLL-U -> word_UPOS(.Gender) sentences
    out=[]; s=[]
    for line in open(src,encoding='utf-8'):
        if line.startswith('#'): continue
        if not line.strip():
            if s: out.append(' '.join(s)); s=[]
            continue
        c=line.rstrip('\n').split('\t')
        if len(c)<6 or '-' in c[0] or '.' in c[0]: continue
        g=re.search(r'Gender=(Masc|Fem|Neut)',c[5]); t=tok(c[1],c[3]+('.'+g.group(1) if g else ''))
        if t: s.append(t)
    if s: out.append(' '.join(s))
    open(dst,'w',encoding='utf-8').write('\n'.join(out)+'\n')
def oana(src,dst,canon):                    # MTE-1984 oana-sk.xml -> word_UPOS(.Gender) sentences
    txt=open(src,encoding='utf-8').read(); out=[]
    for sent in re.findall(r'<s\b[^>]*>(.*?)</s>',txt,re.S):
        s=[]
        for mm in re.finditer(r'<w\b[^>]*>.*?</w>|<c\b[^>]*>.*?</c>',sent,re.S):
            f=mm.group(0); wm=re.match(r'<w\b[^>]*\bana="#([^"]+)"[^>]*>(.*?)</w>',f)
            if wm:
                tag=canon.get(wm.group(1)) or {'N':'NOUN','V':'VERB','A':'ADJ','P':'PRON','R':'ADV','S':'ADP','C':'CCONJ','M':'NUM','Q':'PART','I':'INTJ'}.get(wm.group(1)[0],'X')
                t=tok(html.unescape(wm.group(2)).strip(),tag)
            else:
                cm=re.match(r'<c\b[^>]*>(.*?)</c>',f); t=tok(html.unescape(cm.group(1)).strip(),'PUNCT') if cm else None
            if t: s.append(t)
        if s: out.append(' '.join(s))
    open(dst,'w',encoding='utf-8').write('\n'.join(out)+'\n')
def lower(src,dst):                         # lowercase the word, keep the tag
    with open(dst,'w',encoding='utf-8') as w:
        for line in open(src,encoding='utf-8'):
            o=[t[:t.rfind('_')].lower()+'_'+t[t.rfind('_')+1:] for t in line.split() if t.rfind('_')>0]
            if o: w.write(' '.join(o)+'\n')
def dict_build(wfl,canon_path,dst):         # MULTEXT-East wfl -> form<TAB>UPOS.gender<TAB>lemma (single-lemma)
    import collections
    canon=load_canon(canon_path); fp=collections.defaultdict(set)
    for line in open(wfl,encoding='utf-8'):
        p=line.rstrip('\n').split('\t')
        if len(p)<3: continue
        form,lemma,msd=p
        if not(form and lemma and msd): continue
        tag=canon.get(msd)
        if tag: fp[(form.lower(),tag)].add(lemma)
    with open(dst,'w',encoding='utf-8') as w:
        for (f,t) in sorted(fp):
            if len(fp[(f,t)])==1: w.write(f"{f}\t{t}\t{next(iter(fp[(f,t)]))}\n")
if __name__=='__main__':
    globals()[sys.argv[1]](*sys.argv[2:])
PY

echo "==> [1/6] MULTEXT-East spec + lexicon"
curl -fsSL -o "$WORK/canon.tbl" "https://raw.githubusercontent.com/clarinsi/mte-msd/master/tables/msd-canon-sk.tbl"
href="$(curl -fsSL "https://www.clarin.si/repository/xmlui/handle/11356/1041" | grep -oE "href=\"[^\"]*wfl-sk\.txt[^\"]*\"" | head -1 | sed -E 's/^href="//;s/"$//;s/&amp;/\&/g')"
curl -fsSL "https://www.clarin.si${href}" | gunzip -c > "$WORK/wfl-sk.txt"

echo "==> [2/6] gold: UD Slovak-SNK + MTE-1984 -> UPOS.gender"
for sp in train dev test; do curl -fsSL -o "$WORK/sk-$sp.conllu" "https://raw.githubusercontent.com/UniversalDependencies/UD_Slovak-SNK/master/sk_snk-ud-$sp.conllu"; done
python3 "$WORK/conv.py" conllu "$WORK/sk-train.conllu" "$WORK/gold.txt"
curl -fsSL -o "$WORK/mte.zip" "https://www.clarin.si/repository/xmlui/bitstream/handle/11356/1043/MTE1984-ana.zip?sequence=3&isAllowed=y"
( cd "$WORK" && unzip -o -q mte.zip MTE1984-ana/oana-sk.xml )
python3 "$WORK/conv.py" oana "$WORK/MTE1984-ana/oana-sk.xml" "$WORK/mte1984.txt" "$WORK/canon.tbl"

echo "==> [3/6] silver: UDPipe-tag Leipzig Wikipedia (chunked, segfault-safe)"
curl -fsSL -o "$WORK/leip.tar.gz" "https://downloads.wortschatz-leipzig.de/corpora/slk_wikipedia_2021_300K.tar.gz"
( cd "$WORK" && tar xzf leip.tar.gz )
SENT="$(find "$WORK" -name '*-sentences.txt' | head -1)"
"$JAVA_HOME/bin/javac" -cp "$UD/lib/udpipe.jar" -d "$WORK" "$ROOT/experiments/gender/UdpipeTag.java"
mkdir -p "$WORK/chunks"; split -l 5000 "$SENT" "$WORK/chunks/ch_"
for ch in "$WORK"/chunks/ch_*; do
  "$JAVA_HOME/bin/java" -cp "$UD/lib/udpipe.jar:$WORK" -Djava.library.path="$UD/native" \
    --enable-native-access=ALL-UNNAMED UdpipeTag "$UD/models/slovak-snk.udpipe" "$ch" "$ch.out" >/dev/null 2>&1 \
    || echo "  (chunk $(basename "$ch") crashed, skipped)"
done
cat "$WORK"/chunks/*.out > "$WORK/silver.txt"

echo "==> [4/6] lowercase + combine, then train OpenNLP POS model"
cat "$WORK/gold.txt" "$WORK/mte1984.txt" "$WORK/silver.txt" > "$WORK/all.txt"
python3 "$WORK/conv.py" lower "$WORK/all.txt" "$WORK/all-lc.txt"
"$JAVA_HOME/bin/java" -Xmx4g -cp "$CP" opennlp.tools.cmdline.CLI POSTaggerTrainer \
  -model "$OUT/sk-gender.bin" -lang sk -data "$WORK/all-lc.txt" -encoding UTF-8

echo "==> [5/6] build gender-keyed dictionary"
python3 "$WORK/conv.py" dict_build "$WORK/wfl-sk.txt" "$WORK/canon.tbl" "$OUT/sk-gender-dict.txt"

echo "==> [6/6] done"
echo "  $OUT/sk-gender.bin       ($(du -h "$OUT/sk-gender.bin" | cut -f1))"
echo "  $OUT/sk-gender-dict.txt  ($(wc -l < "$OUT/sk-gender-dict.txt" | tr -d ' ') entries)"
echo "  also needs sk-lemmas.bin (fetch-models.sh sk) for the model fallback"
