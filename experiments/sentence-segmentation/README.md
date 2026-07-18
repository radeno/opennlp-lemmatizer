# Sentence segmentation for POS tagging (experiment — concluded, not shipped)

Lucene's `OpenNLPPOSFilter` tags one **sentence** at a time, buffering tokens up to a change in
`SentenceAttribute` — and only `OpenNLPTokenizer` ever sets that attribute. Behind a `whitespace`
tokenizer (what the README and every example use) the attribute stays at 0, so the **whole field is
tagged as one giant sentence**: the tagger sees input it was never trained on, and every token of the
field is held in memory at once.

This experiment asked whether fixing that improves lemma quality. It measured three ways to give the
tagger real sentence boundaries, on real Slovak text.

> **Conclusion: not shipped.** On real text, sentence segmentation does **not** improve lemma quality —
> it slightly *worsens* it (net −1 token per ~1000). Its only real benefit is bounding memory on very
> long fields (~106 MB → ~1 MB). The change was reverted; this folder keeps the code and the numbers so
> the question does not have to be re-investigated. See **Verdict** at the bottom.

## The three approaches

| approach | file | how it finds boundaries | needs a model? | memory |
|---|---|---|---|---|
| **A. punctuation heuristic** | [`SentenceChunkFilter.java.ref`](SentenceChunkFilter.java.ref) | streams token-by-token, splits on `. ! ?` (skipping ordinals `21.`, decimals `3.14`, abbreviations `t.j.`) | no | streams, ~1 MB |
| **B. OpenNLP `SentenceDetectorME`** | [`OpenNlpSentenceFilter.java.ref`](OpenNlpSentenceFilter.java.ref) | ML sentence model, `sentPosDetect()` over the joined field | yes — `opennlp-models-sentdetect-sk` (12 KB, same Maven source as `sk-pos.bin`) | buffers the field |
| **C. original-case tagging** (I1) | — | not segmentation: tag on the **original case**, lower-case only for the FST key | no | — |

## What the measurements showed

Corpus: **6 real articles, 2 sources (TASR news + Wikipedia), 4 styles, 1019 tokens** — in
[`articles/`](articles/), one sentence per line so per-sentence tagging is the "gold" reference. The
metric is lemma drift of *whole-field tagging* vs *per-sentence tagging* (lower = closer to the ideal of
tagging each sentence as the model was trained to).

### 1. On lemma quality, segmentation barely matters — and what it changes, it worsens

| configuration | lemma drift vs per-sentence gold |
|---|---|
| today (`lowercase` → whole field as one sentence) | **1 / 1019** |
| A. punctuation heuristic | 0 / 1019 |
| B. OpenNLP ML (on original case) | 0 / 1019 |

The single token today's whole-field tagging gets "wrong" vs per-sentence is `Najstaršie doklady…` →
today gives `starý` (correct: superlative of *starý*), per-sentence gives `staro`. So the long (and
technically wrong) whole-field context happens to help here, and **any** per-sentence split — heuristic
or ML — makes it worse. Segmentation is not the lever it looked like: the dictionary already absorbs the
POS drift (`NN↔NNP` → same lemma), the `*` POS-relaxed rows catch more, and the MaxEnt lemmatizer is
nearly POS-insensitive.

### 2. The ML detector collapses to the heuristic behind `lowercase`

`SentenceDetectorME` keys on the **capital letter after a period**. Our pipeline runs `lowercase` before
the lemmatizer (for case-insensitive FST lookup), so the detector sees lower-cased text and loses that
signal:

```
"...1980. Býva v Košiciach."   → 2 sentences ✓   (original case)
"...1980. býva v košiciach."   → 1 sentence  ✗   (lower-cased)
```

On the 6 real articles, ML on lower-cased text produced the **same sentence count as the punctuation
heuristic** on every one. Its only edge is on original-case text (`news.txt`: 13 vs 12 sentences) — which
our post-`lowercase` position throws away. So in this architecture the ML model is strictly worse than
the heuristic: same result, plus a model dependency, plus it buffers the whole field.

### 3. Original-case tagging (I1) is a net negative on real text

Tagging on original case and lower-casing only for the FST key differs from today on 6/1019 tokens —
and today wins 3 : 1:

| token | context | today (`lowercase` first) | original case | correct |
|---|---|---|---|---|
| `Rakúskom` | "hraničí s Rakúskom" | **Rakúsko** ✓ | rakúsky ✗ | Rakúsko |
| `Španielsku` | "v Španielsku" | **Španielsko** ✓ | španielska ✗ | Španielsko |
| `Chorvátsku` | "v Chorvátsku" | **Chorvátsko** ✓ | chorvátsku ✗ | Chorvátsko |
| `Najstaršie` | "Najstaršie doklady" | staro ✗ | **starý** ✓ | starý |

Inflected country names in capital case (`Rakúskom`) get tagged as proper/adjective and mis-lemmatise;
lower-casing them first yields `NN`, which the dictionary resolves to the country. **Lower-casing before
the tagger helps** — the opposite of what I1 assumed.

## Performance (approach A, the heuristic)

Mixed, since chunking trades per-sentence tagging calls against cheaper beam search:

| field length | whole-field | chunked | Δ |
|---|---|---|---|
| ~30 tokens (1 sentence) | 0.73 ms | 0.73 ms | neutral |
| ~155 tokens (long sentences) | 1.66 ms | 1.98 ms | ~15% slower |
| ~930 tokens (many sentences) | 10.4 ms | 6.6 ms | ~37% faster |

Break-even ≈ 150 tokens; short sentences favour chunking earlier. On very long documents chunking wins
because the tagger stops running beam search over one enormous sentence.

## Verdict

- **Lemma quality: no gain on real text, slight loss** (net −1 / 1000). Today's `lowercase` → whole-field
  pipeline is already near the ceiling for this dictionary + model; the remaining errors are dictionary
  or model gaps, not segmentation.
- **The OpenNLP ML detector is the wrong tool *here*** — it needs original case, which our post-`lowercase`
  position removes, collapsing it to the heuristic while adding a model and buffering the whole field. It
  would only pay off as an `OpenNLPTokenizer` on raw text before `lowercase`, and even then quality does
  not improve.
- **Only real benefit of any approach: memory** on long fields (~106 MB → ~1 MB, approach A streams).
  Reintroduce approach A **only** if bounding memory on long documents becomes a requirement.

Real lemma-quality gains come from a richer dictionary or a better POS/lemma model, not from pipeline
segmentation. Reverted in favour of the case-guard / `pos_format` / `model_fallback` change, which
carries no such risk.

## Reproduce

The reference filters compile against `core` + `lucene-analysis-opennlp` + `opennlp-tools`. Drop
`SentenceChunkFilter.java.ref` into `core` (approach A) or wire `OpenNlpSentenceFilter.java.ref` before
`OpenNLPPOSFilter` (approach B), then lemmatise each `articles/*.txt` whole vs one-line-per-sentence and
diff the token streams. The ML sentence model:

```bash
curl -fsSL -o sk-sentdetect.jar \
  https://repo1.maven.org/maven2/org/apache/opennlp/opennlp-models-sentdetect-sk/1.3.0/opennlp-models-sentdetect-sk-1.3.0.jar
unzip -p sk-sentdetect.jar '*.bin' > sk-sent.bin   # opennlp-sk-ud-snk-sentence, ~12 KB
```
