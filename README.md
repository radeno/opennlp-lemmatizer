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

For a **larger, POS-free** dictionary (e.g. where the official Slovak model is thin), fetch a flat
`form → lemma` list for the `dictionary_lemmatizer` filter:

```bash
./scripts/fetch-models.sh sk-michmech   # -> models/sk-michmech.txt  (847k forms, ODbL)
```

## Install

Install straight from a [GitHub Release](https://github.com/radeno/opennlp-lemmatizer/releases) —
each zip is named for the node version it was built for.

OpenSearch (3.7.0):

```bash
./bin/opensearch-plugin install \
  https://github.com/radeno/opennlp-lemmatizer/releases/download/v0.2.0/opensearch-analysis-opennlp-lemmatizer-3.7.0.zip
mkdir -p config/opennlp && cp cs-pos.bin cs-lemmas.bin config/opennlp/   # then restart the node
```

Elasticsearch (9.4.2):

```bash
./bin/elasticsearch-plugin install \
  https://github.com/radeno/opennlp-lemmatizer/releases/download/v0.2.0/elasticsearch-analysis-opennlp-lemmatizer-9.4.2.zip
mkdir -p config/opennlp && cp cs-pos.bin cs-lemmas.bin config/opennlp/   # then restart the node
```

Running a **different** node version? A plugin must match it exactly — build from source
(see [Build](#build)) or bake it into a [custom Docker image](examples/docker/). Releases are cut
by pushing a `v*` tag: CI builds both plugins and attaches the zips.

## Docker

Bake the plugin and models into a custom image (multi-stage: builds the plugin for the exact node
version, installs it, copies the models). See [examples/docker/](examples/docker/):

```bash
./scripts/fetch-models.sh cs && ./scripts/fetch-models.sh sk
docker build -f examples/docker/opensearch.Dockerfile \
  --build-arg OPENSEARCH_VERSION=3.7.0 -t opensearch-opennlp:3.7.0 .
```

## Use

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
> sentence-/field-sized tokenizer. OpenNLP lemmas are lowercased (UD convention).

### Dictionary lemmatizer (fast, POS-free)

A second filter, `dictionary_lemmatizer`, does a plain `form → lemma` lookup from a flat dictionary
— **no part of speech**, so it can't truly disambiguate, but it runs at flat-lookup speed
(comparable to jLemmaGen) and, unlike rule-based jLemmaGen, **leaves unknown words unchanged
instead of mangling them**. Fetch a dictionary (e.g. `./scripts/fetch-models.sh sk-michmech`) into
`config/opennlp/`, then:

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [{ "type": "dictionary_lemmatizer", "dictionary": "sk-michmech.txt" }],
  "text": "Bratislava je krásne mesto"
}'
# tokens: Bratislava  byť  krásny  mesto
```

Verified against the **deployed jLemmaGen plugin**: on **Slovak** the dictionary beats it on the
cases that matter — `je → byť` (jLemmaGen: `jesť`), `Boli → byť` (jLemmaGen mangles to `Boľ`),
`lese → les` (jLemmaGen: `lesa`) — at the same speed. **Coverage is everything**, though: the
michmech Slovak list is huge (847k forms) so it wins, but its Czech list is only ~35k forms, so
there it is *not* a clear improvement. It stays POS-free (own gaps like `a → as`) and ranks below
`opennlp_lemmatizer`. The michmech dictionaries are
[ODbL](https://opendatacommons.org/licenses/odbl/) (attribution + share-alike).

## OpenNLP vs jLemmaGen

The common Czech/Slovak alternative is
[jLemmaGen](https://github.com/vhyza/elasticsearch-analysis-lemmagen) (context-free RDR rules,
e.g. the `analysis-lemmagen` plugin). The trade-off is **quality vs speed**:

| | jLemmaGen | **OpenNLP** (this plugin) |
|---|---|---|
| approach | context-free surface rules | **POS-aware** (tags part of speech, then lemmatizes) |
| POS disambiguation | no | **yes** |
| robust to capitalized words | no | yes |
| throughput | ~5,100,000 tok/s | ~3,300 tok/s (POS tagger runs per token) |
| footprint | pure-Java, one tiny `.lem` | pure-Java, two models (POS + lemmatizer) |
| **Czech** &nbsp; `je` / `Tři` / `jablka` | `on` ✗ / `Tř` ✗ / `jablka` ✗ | `být` ✓ / `tři` ✓ / `jablko` ✓ |
| **Slovak** `je` / `Boli` / `lese` | `jesť` ✗ / `Boľ` ✗ / `lesa` ✗ | `byť` ✓ / `byť` ✓ / `les` ✓ |

(Both example rows are real `_analyze` output. Note the Slovak OpenNLP model is smaller than the
Czech one, so it has its own gaps — see [docs/COMPARISON.md](docs/COMPARISON.md).)

**Rule of thumb:** jLemmaGen when raw indexing speed dominates (or in a hybrid setup where
semantic vectors already absorb most morphology); OpenNLP when lexical quality matters and the
~1500× lower throughput is acceptable. Full data — including a flat-dictionary baseline and the
higher-quality (but native) UDPipe — is in [docs/COMPARISON.md](docs/COMPARISON.md).

## Built with AI assistance

This project was designed, implemented, and documented with the help of an AI coding agent.
Everything was reviewed and verified end-to-end against real OpenSearch and Elasticsearch nodes
before release. See [AGENTS.md](AGENTS.md) for how to work on this repo with an agent.

## License

Apache License 2.0 (see [LICENSE](LICENSE), [NOTICE](NOTICE)). The Elasticsearch artifact is used
`provided` (compile-only, not redistributed); OpenNLP models may carry non-commercial (CC BY-NC-SA)
terms — verify before commercial use.
