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

### S2. Gender-homonyms drop out of the dictionary and the model misguesses them
- **Symptom:** `hrady → hrada`, `hradu → hrada` (should be `hrad`). Also `autom → aut` (should be
  `auto`), `banku → bank` (should be `banka`), etc.
- **Cause (verified in raw MTE):** the form is genuinely two words distinguished only by **gender** —
  `hrady` is masculine `hrad` (`Ncmp…`) *and* feminine `hrada` (`Ncfp…`); both map to Penn `NN`. So
  `(hrady, NN)` has two lemmas → `-mte-pos`'s single-lemma rule **drops it**, and at runtime the MaxEnt
  model fills the gap and guesses the wrong one. The OpenNLP tagset (`NN`) is coarser than the MSD —
  it carries no gender — so part of speech cannot disambiguate these.
- **Scope:** ~**1,025 common-word** gender-homonyms in the Slovak dictionary (plus 97 proper-name
  pairs), not just `hrad`. Many are meaningful (`auto`/`aut`, `banka`/`bank`, `axióma`/`axióm`).
- **Impact + fix:** see section **G** below — the practical fix is a frequency-preferring collapse
  (**I2**), not gender tagging (empirically capped at ~87%, see **G**).

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

### I2. Frequency-preferring collapse (addresses S2) — recommended fix
In `fetch-models.sh -mte-pos`, when a `(form, POS)` maps to several lemmas, **keep the most frequent
lemma** instead of dropping it. For ~all of the ~1,025 gender-homonyms one sense dominates
(`auto` ≫ `aut`, `banka` ≫ `bank`, `hrad` ≫ `hrada`), so this recovers the common reading correctly
at near-zero cost — and **beats gender tagging**, which section **G** shows is capped at ~87% even
with UDPipe. Risk: picks the dominant lemma when the rare sense is actually meant; tune the frequency
proxy (MTE entry count vs a corpus count) and re-run the 104-sentence test.

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

## G. Gender disambiguation — investigation, distillation & real-world test

**Problem.** ~1,025 common Slovak words are gender-homonyms that the coarse OpenNLP `NN` tag cannot
disambiguate (S2): `hrady` = `hrad`(m)/`hrada`(f), `autom` = `auto`(n)/`aut`(m), `banku` =
`banka`(f)/`bank`(m), … The dictionary drops them and the model misguesses. Fixing them "properly"
needs a tagger that emits **grammatical gender**, which OpenNLP's tagset doesn't.

**Can we train an OpenNLP tagger to emit gender?** Tried it. Tagset = UPOS+Gender (`NOUN.Masc/Fem/Neut`),
trained with the OpenNLP CLI `POSTaggerTrainer`, evaluated on the UD Slovak-SNK gold test:

| training data | tokens | overall tag acc | **gender acc** |
|---|---|---|---|
| UD Slovak-SNK (gold) | 80k | 83.8% | 80.5% |
| + MTE-1984 corpus (gold) | 184k | 82.6% | 82.5% |
| UDPipe-tagged Wikipedia (silver, distillation) | 1.13M | 87.6% | 85.8% |
| silver + gold (partial silver, segfault) | 1.31M | 88.0% | 86.2% |
| **silver + gold (full 300k re-tagged)** | 5.18M | 89.1% | **87.89%** |
| **UDPipe itself (teacher ceiling)** | — | — | 87.25% |

**Distillation works.** Using UDPipe to tag a large raw corpus → training OpenNLP on the silver took
pure-Java OpenNLP from 80.5% → **86.2%** gender, **~1 point off the UDPipe teacher (87.25%)** — nearly
saturating the teacher, with **no native dependency at runtime**.

**Real-world test (the one that matters).** Accuracy alone is misleading: what counts is gender-dict
vs the *current* behaviour. We built a gender-keyed noun dict from MTE and ran 35 sentences containing
gender-homonyms (`auto`/`aut`, `banka`/`bank`, `hrad`/`hrada`, `more`/`mor`, …), comparing the lemma
each approach gives the target word:

| approach | correct | |
|---|---|---|
| current (coarse POS → model fallback) | 27/35 | **77%** |
| frequency collapse using **MTE entry counts** | 26/35 | 74% (worse!) |
| **distilled gender tagger → gender dict** | 30/35 | **86%** |

**Corrected verdict (supersedes the pessimistic read above).**
- **Gender wins in reality: 86% vs 77% (+9 pts)** on the homonym class. It fixed `hrady→hrad`,
  `banku→banka`, `mena→mena`, `diel→diel`, `repy→repa`. The "~87% ceiling → not enough" argument was
  *wrong* for this use case: the baseline (model fallback) is only 77% on these hard words, so even an
  imperfect gender tagger is a net improvement.
- It also *introduced* a couple of regressions where the tagger mis-genders a common word
  (`autom→aut`, `more→mor`) — net +3. A larger/cleaner silver set should shrink these (see below).
- **Frequency collapse with MTE entry counts is a bad idea** (74%, worse than doing nothing): MTE
  paradigm-entry counts are not corpus frequency (`hrady→hrada`, `lese→lesa`). Revised **I2**: a
  frequency collapse needs a *real corpus* frequency list (e.g. counted from the UDPipe-lemmatised
  silver Wikipedia), not MTE counts.

**So gender distillation is a viable, net-positive improvement** for the ~1,025-form homonym class —
worth a real module + `_analyze` node test *if those words matter* (it is still only ~0.12% of the
dictionary, and needs the distillation pipeline + a new gender-keyed FST dict + a custom filter).

**Scaling the silver set (done).** UDPipe first segfaulted (SIGSEGV in `libudpipe_java.dylib`'s
`pipeline::process`) after ~70k of 300k sentences. Re-tagging the full corpus **in 5,000-line chunks,
each in a separate JVM** sidestepped the crash entirely (0 crashes, 310k sentences, **5.0M tokens**).
Retraining on 5.18M tokens raised aggregate gender **86.2% → 87.89% — now matching/slightly above the
UDPipe teacher (87.25%)**. So distillation scales and a MaxEnt student can match the neural teacher's
gender on this test. The 35-sentence real-world test stayed ~83–86% (29–30/35) — too small to resolve
the +1.7pt aggregate gain (individual homonyms like `hrady` flip between model versions); a larger
gold-annotated homonym test would be needed to measure scaling on that class specifically. Bigger raw
corpora (Leipzig `slk-sk_web_2015_1M`, FineWeb2) are the next lever if ever pursued.

Reproduction assets were in `/tmp` this session (`UdpipeTag`, `EvalGender`, `RealEval`, the 35-sentence
`realtest.tsv`); UD-SNK via `fetch-models.sh sk-ud`, MTE-1984 at CLARIN handle 11356/1043, Leipzig raw
corpora at downloads.wortschatz-leipzig.de, teacher = `experiments/udpipe` (slovak-snk).

**Node deployment attempt — BLOCKED by a model-resolution bug (needs investigation).** We then tried
deploying it for real on OpenSearch: built a gender-keyed FST dict (form + `UPOS.gender` → lemma, the
homonyms now split: `hrady`→`NOUN.Masc:hrad`/`NOUN.Fem:hrada`), trained a **lowercased** gender model
(so the `lowercase`-composed pipeline matches), and added a fallback-tag normaliser to
`OpenNlpPosLemmatizerFilter` (maps `NOUN.Masc`→`NN` before the Penn-trained lemmatizer model — committed,
correct, and needed). The clever part: no new filter — the generic `pos_dictionary_lemmatizer` takes
any `pos_model` + `dictionary`. **Offline it works perfectly** (`FstPosDictionaryLemmatizer` + the
gender model resolves `hrady→hrad/hrada`, `jablká→jablko`, `pijú→piť`, all correct). **Through the
plugin it does not** — and the root cause turned out to be *intended Lucene behaviour, not a bug*:
Lucene's `org.apache.lucene.analysis.opennlp.tools.NLPPOSTaggerOp` (used by `OpenNLPPOSFilter`, which
`OpenNlpLemmatizer.apply` chains) constructs its tagger as **`new POSTaggerME(model, POSTagFormat.PENN)`**
— it hard-codes Penn-format normalisation. So our `UPOS.gender` tags are coerced to Penn on the way out:
`ADV→RB`, `VERB→VB`, and `NOUN.Fem`/`NOUN.Masc`→`?` (no Penn equivalent for a gender-augmented tag) →
the gender dict misses on `?` and the word falls through. Verified directly: same model, same words,
`new POSTaggerME(m).tag()` → `ADV VERB NOUN.Fem NOUN.Masc` ✓ but `new NLPPOSTaggerOp(m).getPOSTags()`
→ `RB VB ? ?`; `POSTaggerME(m, POSTagFormat.UD|CUSTOM)` keeps the gender tags, `POSTagFormat.PENN` mangles
them. (Thanks to the reviewer who pushed back on the "bug" framing — it's the documented `POSTagFormat`
API.)

**Fixed and shipped as the `pos_format` setting.** `OpenNlpLemmatizer` now has a
`NativeFormatPosTaggerOp` (a `NLPPOSTaggerOp` subclass that tags with `POSTagFormat.CUSTOM`), selected by
`pos_format: native` on `pos_dictionary_lemmatizer` (default `penn` keeps the old behaviour). With it the
gender pipeline **works end-to-end on the node**: `jablká→jablko`, `pijú→piť`, `lese→les`, `vyrába→vyrábať`,
`repy→repa`, `diel→diel` (Penn path gives the wrong `dielo`). Residual errors (`hrady→hrada`, `zámky→zámka`)
are the model's genuine ~13% gender mistakes (the 87% ceiling), not the format issue. Note: the official
`sk-pos.bin` is itself a UD-native model (emits `NOUN`/`VERB`); the existing Penn dict relies on the default
`penn` normalisation, which is why the setting defaults to `penn` and gender opts into `native`. The
`toPennTag` fallback normaliser pairs with this so out-of-dict words still lemmatise. Distributing the gender
model/dict as artifacts (a build script + a fetch target) is the remaining productionisation step — see
`experiments/gender/`.

## Reference numbers (this session, OS 3.7.0)

| filter | tok/s (best-of-5, 4490 tok, `_analyze`) | heap (926k dict, microbench) |
|---|---|---|
| jLemmaGen | 131,830 | — |
| `dictionary_lemmatizer` | 17,097 | ~63 MB (CharArrayMap, flat) |
| `pos_dictionary_lemmatizer` | 6,006 | **~1.5 MB (FST)** |
| `opennlp_lemmatizer` | 5,777 | model only |

`pos_dictionary_lemmatizer ≈ opennlp_lemmatizer` (POS-tagger-bound; the FST dictionary adds no cost).
FST vs a plain hash map for the same dictionary: **1.5 MB vs 268 MB** (178×), 0/925744 lookup mismatch.
