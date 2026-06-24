# Improvements & known shortcomings

A running log of known limitations and ideas for later — so a future session can pick up with
context instead of rediscovering them. Add entries as you find them; remove them when fixed (and
mention the fix in the commit). Evidence/numbers come from real node tests (ES 9.4.2 + OS 3.7.0)
unless noted.

## Known shortcomings

### S1. `lowercase`-before-POS mis-tags some common words (`pos_dictionary_lemmatizer`)
- **Symptom:** `Hostia → host` (should be `hosť`) — 10× in a 104-sentence Slovak test. Also
  `saunu → saunuť` (should be `sauna`).
- **Cause:** the filter is case-sensitive, so users chain a `lowercase` filter *before* it. That
  lowercased text reaches the **internal OpenNLP POS tagger**, which mistags some words (e.g.
  `hostia`), and the wrong POS makes the dictionary miss → the MaxEnt model returns a poor lemma.
- **Trade-off:** lowercasing *helps* proper nouns (they get tagged `NN`, hit the dictionary, and the
  stored lemma keeps its case: `Bratislave → Bratislava`). So it both helps and hurts.
- **Impact:** coverage regression on a handful of common words vs jLemmaGen. `pos_dictionary_lemmatizer`
  still wins decisively on everything contextual (homonyms `je → byť` 12×, prepositions `do`, `pri`,
  case, foreign words). See S→idea **I1** for a possible fix.

### S2. MULTEXT-East single-lemma collapse can pick a non-nominative lemma
- **Symptom:** `hradu → hrada`, `hrady → hrada` (should be `hrad`), while `hrade/hradom/hradoch → hrad`
  are correct. Inconsistent within one paradigm.
- **Cause:** `fetch-models.sh -mte-pos` keeps one lemma per `(form, POS)`; for these forms the source
  lemma column is `hrada` (a genitive used as the citation form in MTE for that reading).
- **Impact:** a few wrong lemmas. See idea **I2**.

### S3. MTE casing/coverage gaps (data, not a bug)
- **Symptom:** proper nouns MTE only knows as common nouns lemmatize lowercase (`Tatry → tatra`);
  some are missing entirely (`Karpaty`, `Ružinov`, `Štrbské` → model fallback, lowercased).
- **Cause:** MULTEXT-East classification/coverage. The dictionary is faithful to MTE; MTE-known
  proper nouns (`Dunaj`, `Bratislava`, `Poprad`, `Košice`) are correctly capitalised.
- **Impact:** minor. See idea **I3**.

### S4. Out-of-dictionary common words rely on model-fallback quality
- **Symptom:** `teplou`, `čistá`, `tichom`, `srny`, `počasí` left unchanged or imperfectly lemmatised;
  jLemmaGen's RDR rules sometimes generalise better here (`teplý`, `čistý`, `srna`).
- **Cause:** not in the MTE `(form, POS)` table → MaxEnt model fallback, which is weaker than a
  rule engine on regular morphology it never memorised.
- **Impact:** the inherent ceiling of dictionary+model vs a rule generaliser on unseen regulars.

## Ideas / improvements

### I1. Decouple POS-tagging case from lookup case (addresses S1) — most promising
POS-tag on the **original-case** token, then lower-case **only for the FST lookup**. The tagger sees
real case (so `Hostia` tags correctly) while matching stays case-insensitive and the lemma keeps its
case. Would need the lowercasing to move *inside* `OpenNlpPosLemmatizerFilter` (fold the term just for
the `FstPosDictionaryLemmatizer` key), instead of an upstream `lowercase` filter — i.e. make the
filter case-insensitive on lookup again, but keep lemma-case output. Re-run the 104-sentence test to
confirm it fixes `Hostia → hosť` without regressing proper nouns.

### I2. Smarter collapse heuristic (addresses S2)
In `fetch-models.sh -mte-pos`, when a `(form, POS)` still maps to several lemmas, prefer the
nominative / shortest / most-frequent lemma rather than dropping or taking the source order. Could
also reconcile across the paradigm (if most forms of a lemma point to `hrad`, prefer `hrad`).

### I3. Optional proper-noun gazetteer overlay (addresses S3)
Layer a small curated proper-noun list (Tatry, Karpaty, Ružinov, …) over the MTE dictionary so known
toponyms lemmatise with correct case/coverage. Keep it separate from the MTE build for licensing.

### I4. Zero-allocation FST lookup (perf, low priority)
`FstPosDictionaryLemmatizer.lemmatize` allocates a `BytesRef` + `utf8ToString()` per token. The POS
tagger dominates runtime (~6k tok/s, see below), so this is invisible at the node level — but a
reusable `BytesRefBuilder` and walking the FST off the term `char[]` would remove it if the POS path
is ever optimised.

### I5. Investigate `dictionary_lemmatizer` node throughput
Measured **17k tok/s** for the flat `dictionary_lemmatizer` vs **132k** for jLemmaGen on the same node
(`_analyze`, 4490 tokens) — the flat CharArrayMap path is ~8× slower than jLemmaGen's automaton.
Surprising for an O(1) lookup; investigate cache behaviour of the 922k-entry map, or whether
`_analyze` overhead skews it. (Earlier microbench suggested ~340k tok/s, so methodology matters —
see I7.)

### I6. UDPipe lemmatizer (`udpipe_lemmatizer`) — separate native plugin
Pending 4th analyzer. Native JNI (UDPipe), needs a Linux `.so` for Docker (only macOS `.dylib`
present). Pre-tokenized "horizontal" 1:1 lemmatization proven in `experiments/udpipe` (FilterProto).
Ship as its own plugin (CC BY-NC-SA models), not in the Apache pure-Java plugin.

### I7. Cleaner throughput benchmark methodology
Node `_analyze` numbers include HTTP + JSON overhead and are single-threaded, so absolute tok/s is
noisy (especially for the fast flat filters). For trustworthy numbers use bulk-index timing or an
in-JVM/JMH harness against the analyzer directly.

## Reference numbers (this session, OS 3.7.0)

| filter | tok/s (best-of-5, 4490 tok, `_analyze`) | heap (926k dict, microbench) |
|---|---|---|
| jLemmaGen | 131,830 | — |
| `dictionary_lemmatizer` | 17,097 | ~63 MB (CharArrayMap, flat) |
| `pos_dictionary_lemmatizer` | 6,006 | **~1.5 MB (FST)** |
| `opennlp_lemmatizer` | 5,777 | model only |

`pos_dictionary_lemmatizer ≈ opennlp_lemmatizer` (POS-tagger-bound; the FST dictionary adds no cost).
FST vs a plain hash map for the same dictionary: **1.5 MB vs 268 MB** (178×), 0/925744 lookup mismatch.
