# opennlp-lemmatizer

A **POS-aware lemmatizer token filter** for **OpenSearch** and **Elasticsearch**, powered by
[Apache OpenNLP](https://opennlp.apache.org/). It tags each token's part of speech and then
lemmatizes — so it disambiguates inflected/homonymous forms that dictionary or rule-based
lemmatizers get wrong (e.g. Slovak `je` → `byť` (to be), not `jesť` (to eat)).

Works with any language Apache OpenNLP ships POS + lemmatizer models for (35+ languages),
including Czech and Slovak. Useful for the lexical (BM25) side of search, alongside or instead
of stemming — especially for highly inflected Slavic languages.

It also ships a faster, POS-free **`dictionary_lemmatizer`** (flat `form → lemma` lookup) for when
raw speed matters more than disambiguation — see [Use](#use).

> **Verified end-to-end** on real nodes: OpenSearch **3.7.0** and Elasticsearch **9.4.2**
> (`_analyze "Děkuji že jsi přišel"` → `děkovat že být přijít` on both).

## Modules

| module | artifact | target |
|---|---|---|
| `core/` | `opennlp-lemmatizer-core` | shared Lucene/OpenNLP logic (no platform deps) |
| `opensearch/` | `opensearch-analysis-opennlp-lemmatizer` | OpenSearch 3.7 plugin |
| `elasticsearch/` | `elasticsearch-analysis-opennlp-lemmatizer` | Elasticsearch 9.4 plugin |
| `experiments/udpipe/` | — | research PoC: UDPipe (higher quality, native JNI). See [its README](experiments/udpipe/README.md). |

Both plugins are thin wrappers over the same `core` engine; only the platform glue differs.

## Build

Requires JDK 25 (pinned via [mise](https://mise.jdx.dev): `mise install`).

```bash
mvn clean package
# -> opensearch/target/releases/opensearch-analysis-opennlp-lemmatizer-<v>.zip
# -> elasticsearch/target/releases/elasticsearch-analysis-opennlp-lemmatizer-<v>.zip
```

**Plugins must match your node version exactly.** Defaults: OpenSearch `3.7.0`, Elasticsearch
`9.4.2`. Build for a different node:

```bash
mvn -pl opensearch    -am package -Dopensearch.version=3.7.0
mvn -pl elasticsearch -am package -Delasticsearch.version=9.4.2
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

> **Versions matter.** The plugin bundles Apache **OpenNLP `opennlp-tools` 2.5.4**, and
> `fetch-models.sh` pulls **models 1.3.0** (trained with OpenNLP 2.5.4). Keep the model version
> aligned with the engine — a major mismatch can fail to load. (Lucene 10.4.0, JDK 25.)

For a **larger, POS-free** dictionary, fetch a flat `form → lemma` list for the
`dictionary_lemmatizer` filter. Pick the source by language — **Slovak → `-michmech`, other
languages → `-ud`**:

```bash
./scripts/fetch-models.sh cs-ud         # -> models/cs-ud.txt        (Czech, 185k forms, gold UD lemmas)
./scripts/fetch-models.sh sk-michmech   # -> models/sk-michmech.txt  (Slovak, 847k forms, ODbL)
```

`-ud` builds the dictionary from the same [Universal Dependencies](https://universaldependencies.org/)
treebanks the OpenNLP models are trained on (gold, human-annotated lemmas) — best where the treebank
is large, like Czech. `-michmech`
([michmech/lemmatization-lists](https://github.com/michmech/lemmatization-lists), ODbL) has the
widest coverage for **Slovak** (847k forms), whose UD treebank is small.

## Install

Install straight from a [GitHub Release](https://github.com/radeno/opennlp-lemmatizer/releases) —
each zip is named for the node version it was built for.

OpenSearch (3.7.0):

```bash
./bin/opensearch-plugin install \
  https://github.com/radeno/opennlp-lemmatizer/releases/download/v0.2.0/opensearch-analysis-opennlp-lemmatizer-3.7.0.zip
./scripts/fetch-models.sh cs config/opennlp   # downloads the Czech models there, then restart
```

Elasticsearch (9.4.2):

```bash
./bin/elasticsearch-plugin install \
  https://github.com/radeno/opennlp-lemmatizer/releases/download/v0.2.0/elasticsearch-analysis-opennlp-lemmatizer-9.4.2.zip
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

This plugin ships **two** lemmatizer filters. Choose by your quality/speed trade-off; choose the
**language** simply by which model/dictionary file you name in the settings (the plugin itself is
language-neutral):

| filter | pick it when | required settings → files (per language) |
|---|---|---|
| `opennlp_lemmatizer` | best quality — POS-aware, disambiguates homonyms in context, writes the POS tag to `type` | `pos_model` + `lemmatizer_model` → e.g. `cs-pos.bin` + `cs-lemmas.bin` |
| `dictionary_lemmatizer` | max speed — flat `form → lemma` lookup, no POS | `dictionary` → e.g. `cs-ud.txt` (Czech) or `sk-michmech.txt` (Slovak) |

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

A second filter, `dictionary_lemmatizer`, does a plain `form → lemma` lookup from a flat dictionary.
It fills the same role as the popular
[jLemmaGen / `vhyza/elasticsearch-analysis-lemmagen`](https://github.com/vhyza/elasticsearch-analysis-lemmagen)
plugin — fast, flat, **no part of speech** — but backed by **richer dictionaries** (847k-form
michmech for Slovak, 185k gold Universal Dependencies lemmas for Czech) and, unlike rule-based
jLemmaGen, it **leaves unknown words unchanged instead of mangling them**. Fetch a dictionary into
`config/opennlp/` (**Slovak → `-michmech`, other languages → `-ud`**; see [Models](#models)) and name
it in the `dictionary` setting — switch languages just by switching the file, no plugin change:

Slovak (michmech, 847k forms):

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [{ "type": "dictionary_lemmatizer", "dictionary": "sk-michmech.txt" }],
  "text": "Bratislava je krásne mesto"
}'
# tokens: Bratislava  byť  krásny  mesto
```

Czech (Universal Dependencies, 185k gold forms):

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [{ "type": "dictionary_lemmatizer", "dictionary": "cs-ud.txt" }],
  "text": "Tři ženy nesly tři jablka"
}'
# tokens: tři  žena  nést  tři  jablko
```

Both verified on real nodes (**OpenSearch 3.7.0** and **Elasticsearch 9.4.2**, identical output):

- **Slovak / michmech** beats the deployed jLemmaGen on the cases that matter — `je → byť`
  (jLemmaGen: `jesť`), `Boli → byť` (jLemmaGen: `Boľ`), `lese → les` (jLemmaGen: `lesa`) — at the
  same speed; its 847k-form list dwarfs the small official Slovak model.
- **Czech / UD** uses gold, human-annotated lemmas: `Tři ženy nesly tři jablka → tři žena nést tři
  jablko` and `je → být`, where the rule-based jLemmaGen mangles `Tři → Tř` and `je → on`.

**Source and coverage are everything.** It stays POS-free, so it still can't disambiguate homonyms
in context the way `opennlp_lemmatizer` does, and ranks below it on quality. michmech dictionaries
are [ODbL](https://opendatacommons.org/licenses/odbl/) (attribution + share-alike); UD-derived
dictionaries follow their treebank's license (Czech PDT is CC BY-NC-SA).

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
