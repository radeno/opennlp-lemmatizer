# Improvements & known shortcomings

A running log of known limitations and ideas for later — so a future session can pick up with
context instead of rediscovering them. Add entries as you find them; when one is fixed or a question
is answered, move it to **[Settled](#settled--do-not-re-investigate)** with the evidence rather than
deleting it, so it is not re-investigated. Evidence/numbers come from real node tests (ES 9.4.3 +
OS 3.7.0) unless noted.

## Known shortcomings

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
- **Impact:** the inherent ceiling of dictionary+model vs a rule generaliser on unseen regulars. The
  `model_fallback: false` setting turns the guessing off (token left unchanged) but does not lemmatise
  these either — it trades recall for predictability.

### S5. Residual homonyms the corpus-frequency merge cannot settle
- **Symptom:** `hrady → hrada` (should be `hrad`), `zámky → zámka`, `angínu → angín`.
- **Cause:** two independent limits. (a) **Near-tie corpus frequencies** — Fix A resolved `hrady` from
  general Wikipedia where `hrad` 18 vs `hrada` 19, i.e. noise decided it (see
  [experiments/homonym-resolution](../experiments/homonym-resolution/README.md)). (b) The gender path
  hits the tagger's **~87 % gender ceiling** and mis-genders some of the same forms.
- **Impact:** small and shrinking — the class is ~1,025 forms and both fixes already recover most of
  it. A domain corpus regenerates better frequencies (`resolve-homonyms.sh`), which is the cheapest
  remaining lever. `plese → ples` vs `pleso` is the canonical domain-dependent case.

### S6. Whole-field POS tagging is unbounded in memory on long fields
- **Symptom:** analysing one very long field holds a `cloneAttributes()` copy of **every token of the
  field** at once (~106 MB measured on a large field, vs ~1 MB for a streaming chunker).
- **Cause:** Lucene's `OpenNLPPOSFilter` buffers up to a change in `SentenceAttribute`, and only
  `OpenNLPTokenizer` ever sets it. Behind the `whitespace` tokenizer every example uses, the attribute
  stays 0, so the whole field is one "sentence".
- **Impact:** memory only — **lemma quality is not affected** (measured: see
  [Settled](#settled--do-not-re-investigate)). Fix is parked and ready in
  [experiments/sentence-segmentation](../experiments/sentence-segmentation/README.md) (approach A,
  punctuation heuristic). Ship it only if long-document heap becomes a real constraint; it also makes
  very long fields ~37 % faster while costing ~15 % on medium ones.

## Ideas / improvements

### I3. Optional proper-noun gazetteer overlay (addresses S3)
Layer a small curated proper-noun list (Tatry, Karpaty, Ružinov, …) over the MTE dictionary so known
toponyms lemmatise with correct case/coverage. Keep it separate from the MTE build for licensing.

### I4. Zero-allocation FST lookup (perf, low priority)
`FstPosDictionaryLemmatizer.lemmatize` allocates a `BytesRef` (`word + '\t' + tag`) plus a
`utf8ToString()` per token. `DictionaryLemmatizerFilter` already avoids the key allocation with a
reused `BytesRefBuilder`; the POS-aware one could do the same by encoding form and tag into one reused
buffer. The POS tagger dominates runtime (~6k tok/s), so this is invisible at the node level — only
worth doing if the POS path is ever optimised.

### I5. Investigate `dictionary_lemmatizer` node throughput
Measured **17k tok/s** for the flat `dictionary_lemmatizer` vs **132k** for jLemmaGen on the same node
(`_analyze`, 4490 tokens). Both numbers predate the FST switch and were taken through `_analyze`, so
they include HTTP + JSON overhead; an earlier microbench suggested ~340k tok/s for the same lookup.
Re-measure with I7's methodology before drawing any conclusion — the gap may be mostly harness.

### I6. UDPipe lemmatizer (`udpipe_lemmatizer`) — separate native plugin
Pending 4th analyzer. Native JNI (UDPipe), needs a Linux `.so` for Docker (only macOS `.dylib`
present). Pre-tokenized "horizontal" 1:1 lemmatization proven in `experiments/udpipe` (FilterProto).
Ship as its own plugin (CC BY-NC-SA models), not in the Apache pure-Java plugin.

### I7. Cleaner throughput benchmark methodology
Node `_analyze` numbers include HTTP + JSON overhead and are single-threaded, so absolute tok/s is
noisy (especially for the fast flat filters). For trustworthy numbers use bulk-index timing or an
in-JVM/JMH harness against the analyzer directly. Blocks a trustworthy answer to I5.

`KeepOriginalBenchmarkTest` is a first working piece of this: in-JVM, alternating rounds, time-sized
measurement windows, and it prints its own noise floor. It measures one setting against another rather
than filter against filter, so I5 still needs its own harness — but the shape (and the corpus in
`experiments/sentence-segmentation/articles`) can be reused. Note what it revealed: the POS-aware path
is noisy enough (±10 %) that any comparison there needs this treatment to mean anything.

### I9. `ModelCache` never releases a superseded artifact
`ModelCache.loadShared` replaces an entry when `(size, lastModified)` changes and otherwise keeps every
loaded path forever — there is no eviction when the last index using a dictionary is deleted or
re-pointed. Bounded by "one live entry per path", so with the FST dictionaries (~1.5–2 MB) it is
negligible; it matters only for the multi-MB POS models (the 24 MB gender model, 9 MB `cs-pos.bin`) on
a node that repeatedly recreates indices with different models. A reference count or a
`WeakReference`/`Cleaner`-based release would fix it if that ever shows up in a heap dump.

### I10. `NativeFormatPosTaggerOp` builds an unused Penn tagger per stream
Its `super(model)` constructs Lucene's `POSTaggerME(model, POSTagFormat.PENN)`, which is then never
used because `getPOSTags` delegates to the CUSTOM-format tagger. That is one wasted `POSTaggerME`
construction per token stream on the `pos_format: native` path. Avoidable only by not extending
`NLPPOSTaggerOp` (Lucene's `OpenNLPPOSFilter` takes that concrete type, so it would need a different
filter) — record it as a known cost rather than a fix worth its complexity today.

## Settled — do not re-investigate

Questions that were measured and closed. The evidence lives in the linked experiment folders.

### ✅ I1 — original-case POS tagging: **rejected, measured net negative**
The idea (tag on original case, lower-case only for the FST key, so `Hostia` tags correctly) was the
top-ranked improvement here for a long time. Measured on 6 real Slovak articles (1019 tokens) it
differs from today on 6 tokens and **today wins 3 : 1**: `Rakúskom → Rakúsko` ✓, `Španielsku →
Španielsko` ✓, `Chorvátsku → Chorvátsko` ✓ vs `Najstaršie → starý` ✗. Inflected capitalised country
names tag as proper/adjective and mis-lemmatise; lower-casing them first yields `NN`, which the
dictionary resolves correctly. **Lower-casing before the tagger helps** — the opposite of the
assumption. Full table: [experiments/sentence-segmentation](../experiments/sentence-segmentation/README.md) §3.

### ✅ I2 — frequency-preferring homonym collapse: **shipped as Fix A**
Implemented as corpus-frequency resolution, not the originally proposed MTE-count collapse:
UDPipe lemmatises a large corpus and each dropped `(form, POS)` keeps the lemma the corpus most often
produces. **4,793 dropped → 1,122 resolved**, committed as `sk-homonyms.txt` and auto-merged by
`fetch-models.sh sk-mte-pos`. Recovers `hradu → hrad`, `hostia → hosť`, `autom → auto`, `more → more`.
**The MTE-entry-count variant was measured and is worse than doing nothing** (26/35 = 74 % vs a 27/35 =
77 % baseline) — MTE paradigm counts are not corpus frequency. Details:
[experiments/homonym-resolution](../experiments/homonym-resolution/README.md).

### ✅ Fix B — POS-relaxed `*` rows
`fetch-models.sh` emits one `form<TAB>*<TAB>lemma` row per form that has a single lemma across all its
readings; the filter retries under `*` before the model fallback. Recovers tagger mis-tags such as
`saunu → sauna` (was `saunuť`). Together with Fix A this closed most of the common-word gap to
jLemmaGen while keeping the POS-aware advantage (`je → byť`).

### ✅ Gender disambiguation — **shipped, opt-in**
A distilled UPOS+gender POS model plus a gender-keyed dictionary, riding the existing
`pos_dictionary_lemmatizer` via `pos_format: native` — no new filter. Prebuilt artifacts ship as a
GitHub Release (`./scripts/fetch-models.sh sk-gender`) and rebuild with
`experiments/gender/build-gender-model.sh`. On the node it beats the Penn path **13/15 vs 11/15** on
gender-homonyms; on a 35-sentence real-world test **30/35 (86 %) vs 27/35 (77 %)** for the current
behaviour. Distillation from a UDPipe teacher took a pure-Java MaxEnt tagger from 80.5 % → **87.9 %**
gender accuracy, matching the teacher's 87.25 % ceiling, with no native runtime dependency. Residual
errors are the tagger's genuine ~13 % gender mistakes (S5). All tables, the training curve, model sizes
and the reproduce script: [experiments/gender](../experiments/gender/README.md).

Two sub-findings worth keeping: Lucene's `NLPPOSTaggerOp` hard-codes `POSTagFormat.PENN`, which
silently coerced `NOUN.Masc` → `?` and made the gender dictionary miss everything — this is documented
`POSTagFormat` behaviour, not a bug, and is what `pos_format: native` exists to bypass. And the
`toPennTag` normaliser in the filter is required alongside it, so out-of-dictionary words still reach
the Penn-trained MaxEnt model with a tag it understands.

### ✅ Sentence segmentation for POS tagging — **investigated, not shipped**
Three approaches measured (punctuation heuristic, OpenNLP `SentenceDetectorME`, original-case tagging).
**Lemma quality does not improve — it slightly worsens** (net −1 token per ~1000): the dictionary
already absorbs POS drift (`NN ↔ NNP` → same lemma), the `*` rows catch mis-tags, and the MaxEnt
lemmatizer is nearly POS-insensitive. The ML detector is strictly worse than the heuristic *in this
pipeline* because it keys on capitals after a period, which the upstream `lowercase` filter destroys.
Only real benefit is memory — kept open as **S6**. Code and numbers preserved in
[experiments/sentence-segmentation](../experiments/sentence-segmentation/README.md).

### ✅ I8 — `keep_original`: **shipped on all three filters**
`keep_original: true` emits the surface form beside each lemma at `positionIncrement: 0`, so a document
still matches on what was written when the lemma is wrong (a model guess, a domain-wrong homonym per
**S5**, a proper noun the dictionary lower-cases per **S3**). A token whose lemma equals it is emitted
once, so only real rewrites cost a posting.

Implemented by composition rather than new filter logic: `KeywordRepeatFilter` → the lemmatizer →
`RemoveDuplicatesTokenFilter`, with the repeat placed **after** the POS tagger. That placement is the
whole point — the `keyword_repeat` recipe the README already documented sits *before* the filter, so the
tagger reads every token twice and its `type` tags shift; the setting does not. All three filters
(including Lucene's own `OpenNLPLemmatizerFilter`) honour `KeywordAttribute` correctly, verified
empirically by protecting a single token and confirming the following lemmas do not shift — an earlier
reading of the bytecode suggested an off-by-one there and was **wrong**.

One structural change came with it: `ModelCache` now caches the flat dictionary's FST rather than the
`DictionaryLemmatizer` wrapper, because the wrapper carries per-filter settings — two indices reading
one dictionary file with different `keep_original` values must still share a single automaton.

**Measured** (`KeepOriginalBenchmarkTest`, 1019 tokens of real Slovak from the 6 articles in
`experiments/sentence-segmentation/articles`):

| filter | tokens rewritten → extra postings | throughput off → on |
|---|---|---|
| `pos_dictionary_lemmatizer` | 51.4 % | 84.5k → 83.5k tok/s (within ±9.9 % noise) |
| `dictionary_lemmatizer` | 49.5 % | 3.39M → 2.49M tok/s (**−26.6 %**) |
| `opennlp_lemmatizer` | 47.4 % | 10.29k → 10.29k tok/s (within ±1.1 % noise) |

The cost is **postings, not CPU**: Slovak inflection rewrites about half of all tokens, so the postings
list grows by about half. The POS-aware filters are tagger-bound and absorb the extra work entirely;
only the flat filter, which does almost nothing else per token, shows it. Lemma quality cannot regress —
the benchmark asserts the lemma stream is byte-identical with the setting on, and that the extra
postings equal the rewrite count exactly (no token is doubled twice).

The benchmark also follows **I7**'s advice, which turned out to matter: a first version measured the two
settings one after the other with a fixed pass count and reported `keep_original` as **21 % faster** —
physically impossible. Alternating the settings round by round and sizing each measurement window by
time (≥300 ms) instead of by pass count brought the POS-aware paths to "within noise", where they
belong. The report now prints its own noise floor so a difference smaller than it cannot be misread as
a result.

### ✅ Shipped robustness fixes
- **Case-only fallback guard** — `LemmatizerME` lower-cases every token before its edit script, so a
  token it cannot lemmatise came back as the lower-cased original (`SKU-4711 → sku-4711`, `NATO →
  nato`), destroying identifiers and unknown proper nouns. The filter now keeps the original token when
  the model only folded case. Inert behind a `lowercase` filter, and it still allows a legitimate
  `haus → Haus`.
- **`model_fallback: false`** — turns the MaxEnt fallback off for `pos_dictionary_lemmatizer`: a
  dictionary miss leaves the token unchanged (pure dictionary, predictable output, no
  `lemmatizer_model` needed).
- **`pos_format` on `opennlp_lemmatizer`** — the setting was read only on the way to
  `pos_dictionary_lemmatizer`; `LemmatizerFilters.opennlp` passed a hard-coded `false`, so on the
  model-only filter it was silently inert *and* an unknown value was not rejected — both halves of the
  failure mode the entry below exists to prevent. It is meaningful there because it swaps the tagger,
  and those tags are the POS half of every `(word, POS)` pair the MaxEnt lemmatizer receives as well as
  the token's `type`; a UD/UPOS model pair needs `native` on this filter too. Fixed by reading it, not
  by rejecting it — rejecting would have broken that pair. Found on a live 9.5.0/3.8.0 node while
  verifying 0.4.1: `pos_format: "ud"` returned tokens instead of a 400. Guarded by
  `PosFormatWiringTest`, which asserts both the rejection and that `native` actually changes the
  emitted tags (without the fix they collapse to Penn `[NN, VB, IN, NN]`).
- **`pos_format` validation** — an unrecognised value used to fall through to `penn` silently, which
  degrades a native-tagged dictionary to 100 % model fallback. It now fails fast at index creation.
- **Dictionary load logging + empty-file fail-fast** — `FstBuilder` logs entry count, streamed vs
  buffered, elapsed time and FST size, and rejects a file that parsed zero entries (Lucene returns a
  `null` FST for empty input, which used to surface as an NPE on the first token analysed).
- **Elasticsearch analysis test** — mirrors the OpenSearch one, so the two platform wrappers are
  covered symmetrically as `AGENTS.md` requires.
- **Model/dictionary path traversal** — `pos_model`, `lemmatizer_model` and `dictionary` were resolved
  with a plain `dir.resolve(name)`, which honours both `../` and absolute names, so a setting could
  name any file the node's account can read. A dictionary is parsed as `form<TAB>lemma` and comes back
  out through `_analyze`, so this was disclosure rather than a failed load — with the check disabled,
  `dictionary: "../../../../etc/passwd"` reached the parser and failed on the file's *contents*
  (`No entries parsed from /etc/passwd`), not on the path. Now resolved through `ModelPaths`, which
  normalizes and requires the result to stay inside `<config>/opennlp/`. Found by reading the
  OpenSearch 3.8.0 changelog: they closed the same hole in their own `resolveAnalyzerPath`
  ([#22094](https://github.com/opensearch-project/OpenSearch/pull/22094)) — which this plugin never
  called, since it resolves against the config dir itself. The check lives in `core` deliberately:
  Elasticsearch gives no equivalent guarantee for plugin-resolved paths, and an OpenSearch 3.7 node
  does not have the engine-side fix either. It compares the *normalized* path and does not call
  `toRealPath()`, so a symlinked `config/opennlp/` keeps working — what is rejected is traversal
  spelled out in the setting.

## Reference numbers (OS 3.7.0, `_analyze`, 4490 tokens, best-of-5)

| filter | tok/s | heap (926k dict) |
|---|---|---|
| jLemmaGen | 131,830 | — |
| `dictionary_lemmatizer` | 17,097 | **~2 MB (FST)** |
| `pos_dictionary_lemmatizer` | 6,006 | **~1.5 MB (FST)** |
| `opennlp_lemmatizer` | 5,777 | model only |

`pos_dictionary_lemmatizer ≈ opennlp_lemmatizer` (POS-tagger-bound; the FST dictionary adds no cost).
FST vs a plain hash map for the same dictionary: **1.5 MB vs 268 MB** (178×), 0/925744 lookup mismatch.
The throughput column predates the FST switch and is `_analyze`-bound — see **I5**/**I7** before
quoting it.

## Memory & load optimisations (M1 / M2 / FST flat dictionary)

- **M1 — node-wide artifact cache** (`ModelCache`): a token-filter factory is built per *(index, filter)*,
  so the same model/dictionary files were parsed into a fresh copy each time. They are now cached by
  path+size+mtime and shared across every index on the node. Node-measured: per extra index dropped from
  ~7.4 MB (`pos_dictionary` copy) / ~94 MB (`dictionary` copy) to ~0.4 MB. Never evicts — see **I9**.
- **M2 — streaming FST load**: an already-sorted dictionary streams straight into the FST with no in-heap
  entry buffer (peak build heap 238 MB → 85 MB on the 926k dict); falls back to buffer+sort otherwise.
  `fetch-models.sh` now emits the `-mte-pos` dictionary in `LC_ALL=C` (FST key) order. Both dictionaries
  now share one `FstBuilder`, so the flat `-mte` dictionary streams too — the `dictionary_lemmatizer`
  load survives an `-Xmx30m` heap that the old buffered path needed ~85 MB for.
- **FST flat dictionary**: `dictionary_lemmatizer` was a `CharArrayMap` (~106 MB, zero-alloc lookup). It
  now uses an FST like `pos_dictionary` (~2 MB). The FST lookup is ~20× slower in isolation (~2M vs ~40M
  lookups/sec) but that is masked by the pipeline — **end-to-end throughput measured equal on the node**
  while memory drops ~50×, so the CharArrayMap backing was removed entirely.
