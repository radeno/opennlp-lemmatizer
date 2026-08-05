# opennlp-lemmatizer

A **POS-aware lemmatizer token filter** for **OpenSearch** and **Elasticsearch**, powered by
[Apache OpenNLP](https://opennlp.apache.org/). It tags each token's part of speech and then
lemmatizes — so it disambiguates inflected/homonymous forms that dictionary or rule-based
lemmatizers get wrong (e.g. Slovak `je` → `byť` (to be), not `jesť` (to eat)).

Works with any language Apache OpenNLP ships POS + lemmatizer models for (35+ languages),
including Czech and Slovak. Useful for the lexical (BM25) side of search, alongside or instead
of stemming — especially for highly inflected Slavic languages.

It also ships a **`pos_dictionary_lemmatizer`** (POS-aware dictionary, model fallback, compact FST) for
precise lemmas on known words, and a faster, POS-free **`dictionary_lemmatizer`** (flat `form → lemma`
lookup) for when raw speed matters more than disambiguation — see [Use](#use).

> **Verified end-to-end** on real nodes: OpenSearch **3.7.0** and Elasticsearch **9.4.4**, with the
> **v0.3.0** zips installed from the GitHub Release below — `_analyze "Děkuji že jsi přišel"` →
> `děkovat že být přijít`, and `keep_original`, `model_fallback` and the `pos_format` rejection all
> behave identically on both.

## Modules

| module | artifact | target |
|---|---|---|
| `core/` | `opennlp-lemmatizer-core` | shared Lucene/OpenNLP logic (no platform deps) |
| `opensearch/` | `opensearch-analysis-opennlp-lemmatizer` | OpenSearch 3.7 plugin |
| `elasticsearch/` | `elasticsearch-analysis-opennlp-lemmatizer` | Elasticsearch 9.4 plugin |
| `experiments/udpipe/` | — | research PoC: UDPipe (higher quality, native JNI). See [its README](experiments/udpipe/README.md). |
| `experiments/gender/` | — | research PoC: distilled UPOS+gender tagger to disambiguate gender-homonyms. See [its README](experiments/gender/README.md). |

Both plugins are thin wrappers over the same `core` engine; only the platform glue differs.

## Build

Requires JDK 25 (pinned via [mise](https://mise.jdx.dev): `mise install`).

```bash
mvn clean package
# -> opensearch/target/releases/opensearch-analysis-opennlp-lemmatizer-<v>.zip
# -> elasticsearch/target/releases/elasticsearch-analysis-opennlp-lemmatizer-<v>.zip
```

**Plugins must match your node version exactly.** Defaults: OpenSearch `3.7.0`, Elasticsearch
`9.4.4`. Build for a different node:

```bash
mvn -pl opensearch    -am package -Dopensearch.version=3.7.0
mvn -pl elasticsearch -am package -Delasticsearch.version=9.4.4
```

## Models

OpenNLP needs a POS model and a lemmatizer model per language. The official Apache OpenNLP models
cover **35+ languages** (POS, lemmatizer, tokenizer, sentence detection) — full list at
[opennlp.apache.org/models.html](https://opennlp.apache.org/models.html). Both Czech and **Slovak**
are included. Fetch them from Maven Central with the helper:

```bash
./scripts/fetch-models.sh cs      # -> models/cs-pos.bin, models/cs-lemmas.bin
./scripts/fetch-models.sh sk      # -> models/sk-pos.bin, models/sk-lemmas.bin
```

Place them in your node's `config/opennlp/` directory.

> **Versions matter.** The plugin bundles Apache **OpenNLP `opennlp-tools` 2.5.11**, and
> `fetch-models.sh` pulls **models 1.3.0** (trained with OpenNLP 2.5.4). Any 2.5.x engine reads
> those models unchanged — lemma output is byte-identical across the line — but a major mismatch
> (3.x) is untested and can fail to load. (Lucene 10.4.0, JDK 25.)

For a **larger dictionary**, fetch one of these (all need `python3`; `-mte*` also need `gzip`):

```bash
./scripts/fetch-models.sh sk-mte-pos  # -> models/sk-mte-pos.txt  (Slovak, 926k form/POS/lemma, MULTEXT-East)
./scripts/fetch-models.sh sk-mte      # -> models/sk-mte.txt      (Slovak, 922k form→lemma, MULTEXT-East)
./scripts/fetch-models.sh cs-ud       # -> models/cs-ud.txt       (Czech, 185k form→lemma, Universal Dependencies)
```

`-mte-pos` is the **POS-aware** `form/POS/lemma` dictionary for `pos_dictionary_lemmatizer`; `-mte`
and `-ud` are **flat** `form → lemma` lists for `dictionary_lemmatizer`. For Slovak gender-homonym
disambiguation, `./scripts/fetch-models.sh sk-gender` pulls a prebuilt UPOS+gender POS model and its
dictionary — pair them with `sk-lemmas.bin` (from the base `sk` fetch) and `pos_format: native`; see
[experiments/gender/](experiments/gender/README.md). `-mte*` build from the
[MULTEXT-East](http://nl.ijs.si/ME/) morphosyntactic lexicons
([CLARIN.SI "free lexicons 4.0"](https://www.clarin.si/repository/xmlui/handle/11356/1041),
**CC BY-SA 4.0 — commercial use OK**) — the authoritative academic source the popular michmech lists
were themselves derived from. Widest coverage (Slovak ~926k entries). `-ud` builds from the same
[Universal Dependencies](https://universaldependencies.org/) treebanks the OpenNLP models are trained
on (gold, human-annotated lemmas) — best where the treebank is large, like Czech. MTE covers
`bg cs en et fr hu ro sk sl uk`.

## Install

Install straight from a [GitHub Release](https://github.com/radeno/opennlp-lemmatizer/releases) —
each zip is named for the node version it was built for.

OpenSearch (3.7.0):

```bash
./bin/opensearch-plugin install \
  https://github.com/radeno/opennlp-lemmatizer/releases/download/v0.3.0/opensearch-analysis-opennlp-lemmatizer-3.7.0.zip
./scripts/fetch-models.sh cs config/opennlp   # downloads the Czech models there, then restart
```

Elasticsearch (9.4.4):

```bash
./bin/elasticsearch-plugin install \
  https://github.com/radeno/opennlp-lemmatizer/releases/download/v0.3.0/elasticsearch-analysis-opennlp-lemmatizer-9.4.4.zip
./scripts/fetch-models.sh cs config/opennlp   # downloads the Czech models there, then restart
```

Running a **different** node version? A plugin must match it exactly — build from source
(see [Build](#build)) or bake it into a [custom Docker image](examples/docker/). Releases are cut
by pushing a `v*` tag: CI builds both plugins and attaches the zips.

## Docker

Bake the plugin **and** its models into a custom image — the multi-stage build compiles the plugin
for the exact node version and runs `fetch-models.sh` itself (choose languages with `LANGS`). See
[examples/docker/](examples/docker/):

```bash
docker build -f examples/docker/opensearch.Dockerfile \
  --build-arg OPENSEARCH_VERSION=3.7.0 --build-arg LANGS="cs sk" -t opensearch-opennlp:3.7.0 .
```

## Use

This plugin ships **three** lemmatizer filters. Choose by your quality/speed trade-off; choose the
**language** simply by which model/dictionary file you name in the settings (the plugin itself is
language-neutral):

| filter | pick it when | required settings → files (per language) |
|---|---|---|
| `opennlp_lemmatizer` | best generalisation — POS-aware MaxEnt model, disambiguates homonyms in context, lemmatises words it has never seen, writes the POS tag to `type` | `pos_model` + `lemmatizer_model` → e.g. `cs-pos.bin` + `cs-lemmas.bin` |
| `pos_dictionary_lemmatizer` | best precision on known words — a POS-aware `form/POS/lemma` dictionary consulted first, the MaxEnt model fills the gaps | `pos_model` + `lemmatizer_model` + `dictionary` → e.g. `sk-pos.bin` + `sk-lemmas.bin` + `sk-mte-pos.txt` |
| `dictionary_lemmatizer` | max speed — flat `form → lemma` lookup, no POS | `dictionary` → e.g. `sk-mte.txt` (Slovak) or `cs-ud.txt` (Czech) |

All three also take [`keep_original`](#keep_original-true--index-the-surface-form-beside-the-lemma) to
index the surface form beside each lemma. The two dictionary filters additionally take
[`unicode_folding`](#unicode_folding-true--match-text-written-without-its-diacritics), which lets text
written without its diacritics still find the dictionary.

Ready-made analyzer configs for both filters, per language, are in [examples/](examples/).

### POS-aware: `opennlp_lemmatizer`

The filter type is `opennlp_lemmatizer` with two required settings: `pos_model` and
`lemmatizer_model` (file names under `config/opennlp/`). Quick check with `_analyze`:

Czech:

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [{ "type": "opennlp_lemmatizer", "pos_model": "cs-pos.bin", "lemmatizer_model": "cs-lemmas.bin" }],
  "text": "Děkuji že jsi přišel"
}'
# tokens: děkovat  že  být  přijít
```

Slovak:

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [{ "type": "opennlp_lemmatizer", "pos_model": "sk-pos.bin", "lemmatizer_model": "sk-lemmas.bin" }],
  "text": "Ďakujem že si prišiel"
}'
# tokens: ďakovať  že  si  prísť
```

Full index-analyzer settings per language: [examples/cs-analyzer.json](examples/cs-analyzer.json),
[examples/sk-analyzer.json](examples/sk-analyzer.json).

> POS tagging runs over the token stream as one sentence, so the filter is best placed after a
> sentence-/field-sized tokenizer. OpenNLP lemmas are lowercased (UD convention), and each token's
> POS tag is exposed in the `type` attribute (e.g. `NNP` for a proper noun) for downstream filters.

### Dictionary lemmatizer (fast, POS-free)

A second filter, `dictionary_lemmatizer`, does a plain `form → lemma` lookup from a flat dictionary
(stored, like the POS-aware one, in a compact **Lucene FST** — a few MB for the whole Slovak lexicon).
It fills the same role as the popular
[jLemmaGen / `vhyza/elasticsearch-analysis-lemmagen`](https://github.com/vhyza/elasticsearch-analysis-lemmagen)
plugin — fast, flat, **no part of speech** — but backed by **richer dictionaries** (922k-form
MULTEXT-East for Slovak, 185k gold Universal Dependencies lemmas for Czech) and, unlike rule-based
jLemmaGen, it **leaves unknown words unchanged instead of mangling them** (jLemmaGen is also
case-sensitive, so it mangles capitalised words — `Je → Jy`, `Deti → Deť` — whereas this filter is
case-insensitive). Fetch a dictionary into `config/opennlp/` (**Slovak → `-mte`, Czech → `-ud`**;
see [Models](#models)) and name it in the `dictionary` setting — switch languages just by switching
the file, no plugin change. Optional: `keep_original` and `unicode_folding`.

Slovak (MULTEXT-East, 922k forms):

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [ "lowercase", { "type": "dictionary_lemmatizer", "dictionary": "sk-mte.txt" } ],
  "text": "Bratislava je krásne mesto"
}'
# tokens: Bratislava  byť  krásny  mesto
```

Czech (Universal Dependencies, 185k gold forms):

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [ "lowercase", { "type": "dictionary_lemmatizer", "dictionary": "cs-ud.txt" } ],
  "text": "Tři ženy nesly tři jablka"
}'
# tokens: tři  žena  nést  tři  jablko
```

Both verified on real nodes (**OpenSearch 3.7.0** and **Elasticsearch 9.4.4**, identical output):

- **Slovak / MULTEXT-East** beats the deployed jLemmaGen on the cases that matter — `je → byť`
  (jLemmaGen: `jesť`), `tri → tri` (jLemmaGen mangles capitalised `Tri`), `priatelia → priateľ` — at
  the same speed; its 922k-form lexicon dwarfs the small official Slovak model.
- **Czech / UD** uses gold, human-annotated lemmas: `Tři ženy nesly tři jablka → tři žena nést tři
  jablko` and `je → být`, where the rule-based jLemmaGen mangles `Tři → Tř` and `je → on`.

**Source and coverage are everything.** It stays POS-free, so it still can't disambiguate homonyms
in context the way `opennlp_lemmatizer` does, and ranks below it on quality. MTE dictionaries are
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) (attribution + share-alike,
**commercial use OK**); UD-derived dictionaries follow their treebank's license (Czech PDT is CC BY-NC-SA).

### POS-aware dictionary: `pos_dictionary_lemmatizer`

The third filter is the precise middle ground: it runs the OpenNLP POS tagger, then consults a
**POS-aware `form/POS/lemma` dictionary first** and only falls back to the MaxEnt lemmatizer model for
`(word, POS)` pairs the dictionary doesn't cover. So known words get the dictionary's exact lemma
(disambiguated by part of speech — `je → byť` as a copula vs `je → jesť` as a verb), and everything
else still gets a model lemma. Required settings: `pos_model`, `lemmatizer_model`, and `dictionary`
(a `form<TAB>POS<TAB>lemma` file; fetch with **`-mte-pos`**, see [Models](#models)). Optional:
`pos_format`, `model_fallback` — with `model_fallback: false`, `lemmatizer_model` is not needed —
`keep_original` and `unicode_folding`.

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [ "lowercase", { "type": "pos_dictionary_lemmatizer",
              "pos_model": "sk-pos.bin", "lemmatizer_model": "sk-lemmas.bin", "dictionary": "sk-mte-pos.txt" } ],
  "text": "V Bratislave je hrad a tri lipy"
}'
# tokens: v  Bratislava  byť  hrad  a  tri  lipa
```

> **Light, not heavy.** The dictionary is loaded into a compact **Lucene FST** — ~1.5 MB on heap for
> the ~926k-entry Slovak dictionary (vs ~268 MB for a plain hash map), smaller than the models
> themselves, with full coverage and identical lookups (verified entry-for-entry across all 926k).
>
> **Case-sensitive — chain `lowercase`.** Like `dictionary_lemmatizer`, the filter never folds case;
> put a `lowercase` filter ahead of it for case-insensitive matching. The stored lemma keeps its case,
> so proper nouns MULTEXT-East knows stay capitalised (`Bratislave → Bratislava`, `Dunaji → Dunaj`).
> Words MTE only knows as common nouns, or doesn't know, fall back to the model lemma, which is
> lower-cased — unless the model lemmatised nothing at all, in which case the token is kept as it was
> (see *the model fallback never folds case on its own*, below).
>
> **`pos_format` (advanced).** Defaults to `penn` — Lucene normalises the POS model's tags to the Penn
> tagset, which is what the `-mte-pos` dictionary above is keyed on. **Keep the default for `sk-mte-pos.txt`.**
> Set `pos_format: native` *only* when the dictionary's POS column matches the model's own native tagset
> (e.g. the UPOS+gender model + dict from [experiments/gender/](experiments/gender/README.md)). Pairing
> `native` with the Penn `-mte-pos` dictionary makes every lookup miss (the model emits UD `NOUN`, the
> dict has Penn `NN`) → degraded model fallback. The two must agree. Only `penn`, `native` and `custom`
> are accepted; anything else (`ud`, a typo) is rejected when the index is created rather than silently
> read as `penn`.
>
> **POS-relaxed fallback.** A form with a single lemma regardless of part of speech also gets a
> `form<TAB>*<TAB>lemma` row, so when the POS tagger mis-tags such a word (`saunu` called a verb) the
> filter still recovers its lemma (`sauna`) instead of falling to the model. Ambiguous forms (`je`) have
> no `*` row, so context disambiguation is preserved. (Fetch a fresh `sk-mte-pos.txt` to get the `*` rows.)
>
> **The model fallback never folds case on its own.** `LemmatizerME` lower-cases every token before
> lemmatising, so a token it cannot lemmatise would come back merely lower-cased (`NATO → nato`,
> `SKU-4711 → sku-4711`). The filter keeps the original token in that case, so identifiers and unknown
> proper nouns survive. This cannot change anything behind a `lowercase` filter, where the token is
> already folded — see the recipes below to protect identifiers there.

### `model_fallback: false` — a pure dictionary filter

Set `model_fallback: false` to drop the MaxEnt fallback entirely: a `(word, POS)` pair the dictionary
doesn't cover leaves its token **unchanged** instead of being guessed. Output becomes predictable —
the filter never invents a lemma and never mangles a token — at the cost of the words only the model
could reach (on real Slovak text with a `lowercase` filter and punctuation split off, that is ~2 % of
tokens). `lemmatizer_model` is then not needed and is not loaded at all, which saves the model's heap
(**~4 MB** for `sk-lemmas.bin`, **~46 MB** for `cs-lemmas.bin`).

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [ "lowercase", { "type": "pos_dictionary_lemmatizer", "pos_model": "sk-pos.bin",
              "dictionary": "sk-mte-pos.txt", "model_fallback": false } ],
  "text": "Objednávka SKU-4711 bola odoslaná"
}'
# tokens: objednávka  sku-4711  byť  odoslaná      <- "odoslaná" is not in the dictionary, so it stays
```

### `keep_original: true` — index the surface form beside the lemma

Available on **all three filters**. Each rewritten token is emitted twice at the same position — the
lemma and the word as written (`position_increment: 0`) — so a document still matches on the surface
form when the lemma is wrong: a model guess, a homonym resolved for the wrong domain (`plese → ples`
in a tourism corpus), or a proper noun the dictionary lower-cases. Defaults to `false`.

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [ "lowercase", { "type": "pos_dictionary_lemmatizer", "pos_model": "sk-pos.bin",
              "lemmatizer_model": "sk-lemmas.bin", "dictionary": "sk-mte-pos.txt",
              "keep_original": true } ],
  "text": "Hostia prišli do Bratislavy"
}'
# tokens: hostia hosť | prišli prísť | do | bratislavy Bratislava
```

A token whose lemma equals it (`do`) is **not** doubled, so only real rewrites cost an extra posting.
This is the same stacked-token shape as the `keyword_repeat` recipe below, but the repeat happens
*inside* the filter, **after** the POS tagger — so the tagger still reads each token once and its tags
do not shift. Prefer the setting over the manual chain on the two POS-aware filters.

**What it costs.** Measured on 1019 tokens of real Slovak (`KeepOriginalBenchmarkTest`, 6 articles from
two sources):

| filter | tokens rewritten | extra postings | throughput |
|---|---|---|---|
| `pos_dictionary_lemmatizer` | 51.4 % | +51.4 % | unchanged (within ±10 % noise) |
| `dictionary_lemmatizer` | 49.5 % | +49.5 % | −27 % (3.4M → 2.5M tok/s) |
| `opennlp_lemmatizer` | 47.4 % | +47.4 % | unchanged (within ±1 % noise) |

Slovak is heavily inflected, so about **half of all tokens are rewritten** and the postings list grows by
roughly half — that is the real price, not CPU. On the two POS-aware filters the extra work disappears
behind POS tagging; only the flat filter, which otherwise does almost nothing per token, feels it (and
2.5M tok/s is still far above what an ingest pipeline needs).

**What it buys.** Lemmas are byte-identical with the setting on — the benchmark asserts this, so lemma
quality cannot regress. What changes is recall: the ~50 % of tokens that get rewritten stay findable as
written. That matters exactly where the lemma is wrong, which this project documents rather than hides —
`Tatry → tatra`, `Karpaty → karpata`, `hrady → hrada`, `angínu → angín`, `plese → ples` (right for a
dance corpus, wrong for a tourism one). None of those become *correct*, but none of them become
unfindable either.

### `unicode_folding: true` — match text written without its diacritics

Available on both dictionary filters (`pos_dictionary_lemmatizer`, `dictionary_lemmatizer`). Defaults to
`false`.

**Why it exists.** Dictionary lookup is exact byte matching, and **74.2 % of the forms in the Slovak
lexicon carry a diacritic**. So a user who types `ruzomberku` — as Slovaks routinely do — misses three
quarters of the dictionary and falls through to the MaxEnt model, which guesses:

```
ruzomberku  →  ruzomberka   ✗   (should be Ružomberok)
kosic       →  kosic        ✗   (Košice)
trencine    →  trencine     ✗   (Trenčín)
```

It is not a Slovak problem:

- **Greek** — a dictionary misses everything typed without accents: `αθηνα` vs `Αθήνα`.
- **Polish** — `Lodz` misses `Łódź`, and here folding is not even accent removal: `ł` has no accent to
  strip, so stripping combining marks alone would never get there.
- **Vietnamese** — nearly every word is lost once the tone marks are dropped: `Ha Noi` vs `Hà Nội`.

**What it does.** A second automaton, keyed on the *folded* form, is built beside the exact one and
consulted **after** both exact attempts. Folding is
[UTR#30](https://www.unicode.org/reports/tr30/tr30-4.html) via the same normaliser as the `icu_folding`
token filter, so it is not diacritics only — it also folds case, ligatures, width and compatibility
forms (`ﬁnance → finance`, `Ⅻ → xii`, `ποιός → ποιοσ`).

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [ "lowercase", { "type": "pos_dictionary_lemmatizer", "pos_model": "sk-pos.bin",
              "lemmatizer_model": "sk-lemmas.bin", "dictionary": "sk-mte-pos.txt",
              "unicode_folding": true } ],
  "text": "Byvam v Ruzomberku uz dlho"
}'
# tokens: bývať  v  Ružomberok  už  dlho
```

**What it buys.** Measured on 5000 Slovak forms that carry diacritics, lemmatised with and without them
(`UnicodeFoldingTest` asserts the individual cases; the table is the aggregate):

| | input **with** diacritics | input **without** |
|---|---|---|
| jLemmaGen (`sk.lem`, for reference) | 99.1 % | 55.5 % |
| `pos_dictionary_lemmatizer`, folding off | 99.2 % | **27.6 %** |
| `pos_dictionary_lemmatizer`, `unicode_folding: true` | 99.2 % | **97.8 %** |

The left column is the point: **it does not move.** The folded automaton sits behind both exact
attempts, so it can only answer where the filter previously had nothing — correctly spelled text comes
out byte-identical, and `UnicodeFoldingTest` asserts exactly that.

jLemmaGen is in the table because it is the usual alternative and it degrades more gracefully than plain
exact lookup — its RDR rules have no notion of a miss, so they always produce *something*. But guessing a
suffix cannot recover a stem it has never seen in that shape, which is why it stalls at 55.5 %.

**What it costs.**

| | |
|---|---|
| folded keys added (Slovak, 926k entries) | +680k (FST 1.2 MB → 2.4 MB total) |
| extra load time | ~0.6 s, once per file per node |
| load-time memory peak | the folded keys are buffered and sorted before compiling — folding is not order-preserving, so this build cannot stream the way the exact one does |
| accuracy on correctly spelled text | unchanged (byte-identical) |
| ambiguity introduced | 2.4 % of new keys are claimed by more than one word |

That last row is the real trade. Folding collapses distinct words (`kosičky`/`košíčky`), and a folded hit
is an inference from a differently written form, not an entry the dictionary holds. Two things keep it
contained:

- **The folded lookup never runs while an exact one can succeed** — including the POS-relaxed `*` retry.
- **It is refused for tokens not already written in plain form.** If you typed `Ružomberok` and it missed,
  folding is speculation, so the model keeps that token. Only plainly written tokens (`ruzomberku`,
  `Petr`, `ΠΟΙΟΣ`) reach the folded automaton.

Where a folded key is genuinely ambiguous it goes to the reading **most of its class supports**, not to
whichever row came first — Slovak `už` (two rows) outvotes the single `úž` row of `úžiť`, so `uz → už`.

> **Do not use `asciifolding` for this.** Its output is ASCII, so on a Greek or Cyrillic dictionary it
> leaves every form untouched — you would get an empty folded automaton and a setting that silently does
> nothing, with no error. That is why this ships ICU folding.

### Two recipes worth knowing

All three filters honour `KeywordAttribute`, so the standard Lucene chains work without any setting.

**Protect identifiers from the model** — mark them keyword and the lemmatizer leaves them alone:

```json
[ { "type": "keyword_marker", "keywords_pattern": ".*[0-9@:/].*" },
  "lowercase",
  { "type": "pos_dictionary_lemmatizer", "...": "..." } ]
```

`user@example.com` now survives intact instead of becoming `user@example.cí`. It does **not** keep its
case: Lucene's `lowercase` filter ignores `KeywordAttribute`, so `SKU-4711` still folds to `sku-4711`
wherever you place the marker. Drop `lowercase` to keep the case too — the filter then also keeps
`SKU-4711` and `Objednávka` — but the dictionary is case-sensitive, so every capitalised word misses it.
`model_fallback: false` above is the other way to get the same protection.

**Index the original alongside the lemma** (recall) — the usual stacked-token pattern:

```json
[ "lowercase", "keyword_repeat", { "type": "pos_dictionary_lemmatizer", "...": "..." }, "remove_duplicates" ]
```

`Hostia prišli do Bratislavy` → `hostia hosť | prišli prísť | do | bratislavy Bratislava`, the lemma
stacked at `position_increment: 0`. Note this feeds the POS tagger each token twice; on Slovak the lemmas
come out identical, but the tags it reports in the `type` attribute do shift. **`keep_original: true`
(above) does the same thing without that flaw** — it repeats the token after the tagger — so reach for
the manual chain only when you need the repeat around filters other than these.

**Fold diacritics away in the index — but only after the lemmatizer:**

```json
[ "lowercase", { "type": "pos_dictionary_lemmatizer", "...": "..." }, "asciifolding", "lowercase" ]
```

Order is not a style choice here. Dictionary keys carry their diacritics, so a folding filter placed
**before** the lemmatizer disables 74 % of the Slovak dictionary:

```
lowercase → asciifolding → lemmatizer   Bývam v Ružomberku  →  byvať v ruzomberka   ✗
lowercase → lemmatizer → asciifolding   Bývam v Ružomberku  →  byvat v Ruzomberok   ✓
```

The lemma carries its diacritics out of the filter and folds cleanly afterwards; it also keeps the case
it is stored with, which is why the second `lowercase` is there. This normalises what gets **indexed**
and is unrelated to [`unicode_folding`](#unicode_folding-true--match-text-written-without-its-diacritics),
which changes what the dictionary **matches**. Use both together when your users type without diacritics
*and* you want a diacritic-insensitive index.

## OpenNLP vs jLemmaGen

The common Czech/Slovak alternative is
[jLemmaGen](https://github.com/vhyza/elasticsearch-analysis-lemmagen) (context-free RDR rules,
e.g. the `analysis-lemmagen` plugin). The trade-off is **quality vs speed**:

| | jLemmaGen | **OpenNLP** (this plugin) |
|---|---|---|
| approach | context-free surface rules | **POS-aware** (tags part of speech, then lemmatizes) |
| POS disambiguation | no | **yes** |
| robust to capitalized words | no | yes |
| throughput | fast (flat lookup) | much slower (POS tagger per token) |
| footprint | pure-Java, one tiny `.lem` | pure-Java, two models (POS + lemmatizer) |
| **Czech** &nbsp; `je` / `Tři` / `jablka` | `on` ✗ / `Tř` ✗ / `jablka` ✗ | `být` ✓ / `tři` ✓ / `jablko` ✓ |
| **Slovak** `je` / `Boli` / `lese` | `jesť` ✗ / `Boľ` ✗ / `lesa` ✗ | `byť` ✓ / `byť` ✓ / `les` ✓ |

(Both example rows are real `_analyze` output. Note the Slovak OpenNLP model is smaller than the
Czech one, so it has its own gaps — see [docs/COMPARISON.md](docs/COMPARISON.md).)

**Rule of thumb:** jLemmaGen when raw indexing speed dominates (or in a hybrid setup where
semantic vectors already absorb most morphology); OpenNLP when lexical quality matters and the
lower throughput is acceptable. Full data — including a flat-dictionary baseline and the
higher-quality (but native) UDPipe — is in [docs/COMPARISON.md](docs/COMPARISON.md).

**Throughput (real node)** — OpenSearch 3.7, `_analyze` through a configured index analyzer so the
filter loads once, ~900-token request:

| filter | tokens/sec |
|---|---:|
| baseline (no filter) | ~530,000 |
| `dictionary_lemmatizer` | ~410,000 |
| jLemmaGen (deployed) | ~320,000 |
| `opennlp_lemmatizer` | ~12,000 |

The flat-lookup filters (dictionary, jLemmaGen) are HTTP-bound — equal in practice;
`opennlp_lemmatizer` is the slow one. **But the slowness buys quality the others can't match:** it
is the only filter that disambiguates by **part of speech in context** (a flat dictionary or rule
set bakes in one lemma per word form), and it also writes each token's POS tag into the `type`
attribute — e.g. `NNP` (proper noun), `NN` (noun), `JJ` (adjective) — which downstream token filters
and queries can use. The flat-lookup filters leave `type` as `word`.

<details>
<summary>📊 <b>Library microbenchmark numbers</b> (click to expand)</summary>

Steady state, no HTTP (`core` module) — pure per-token cost, *not* a node measurement:

| engine | tokens/sec |
|---|---:|
| dictionary / jLemmaGen (flat lookup) | ~5,000,000 |
| `opennlp_lemmatizer` (POS per token) | ~3,300 |
| UDPipe (native) | ~10,000–200,000 |

> An *inline* `_analyze` filter re-loads the dictionary/model per request — always measure (and run)
> through an index analyzer, as in the real-node table above.

</details>

## Built with AI assistance

This project was designed, implemented, and documented with the help of an AI coding agent.
Everything was reviewed and verified end-to-end against real OpenSearch and Elasticsearch nodes
before release. See [AGENTS.md](AGENTS.md) for how to work on this repo with an agent.

## License

Apache License 2.0 (see [LICENSE](LICENSE), [NOTICE](NOTICE)). The Elasticsearch artifact is used
`provided` (compile-only, not redistributed); OpenNLP models may carry non-commercial (CC BY-NC-SA)
terms — verify before commercial use.
