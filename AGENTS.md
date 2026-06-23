# AGENTS.md

Guidance for AI coding agents working on **opennlp-lemmatizer** — a POS-aware Apache OpenNLP
lemmatizer token filter for OpenSearch and Elasticsearch. One shared `core` engine, two thin
platform plugins (`opensearch/`, `elasticsearch/`).

## Setup & build

```bash
mise install            # JDK 25 (pinned in mise.toml)
mvn clean package       # builds all modules + both plugin zips
```

Outputs:
- `opensearch/target/releases/opensearch-analysis-opennlp-lemmatizer-<v>.zip`
- `elasticsearch/target/releases/elasticsearch-analysis-opennlp-lemmatizer-<v>.zip`

**A plugin must match the target node version exactly.** Defaults: OpenSearch `3.7.0`,
Elasticsearch `9.4.2`. Build for a specific node:

```bash
mvn -pl opensearch    -am package -Dopensearch.version=3.7.0
mvn -pl elasticsearch -am package -Delasticsearch.version=9.4.2
```

## Test

```bash
mvn test                 # all modules
mvn -pl opensearch test  # just the OpenSearch analysis test
```

Quality/integration tests need OpenNLP models in `models/`; they **self-skip** (`assumeTrue`)
when absent. Fetch first:

```bash
./scripts/fetch-models.sh cs    # -> models/cs-pos.bin, models/cs-lemmas.bin
./scripts/fetch-models.sh sk
```

Surefire passes the absolute `models/` path to tests via `-Dopennlp.models.dir`.

## Code style

- Java 25. The engine lives in `core/` and has **no** OpenSearch/Elasticsearch imports. The two
  platform modules are thin wrappers that delegate to `OpenNlpLemmatizer`. Put new shared logic,
  constants, and setting names in `core` — never duplicate them across the wrappers.
- A whole wrapper factory is ~12 lines: read settings, call the shared factory, delegate `create()`:

```java
public OpenNlpLemmatizerTokenFilterFactory(IndexSettings i, Environment env, String name, Settings settings) {
    super(name); // Elasticsearch ctor; OpenSearch is super(i, name, settings)
    this.lemmatizer = OpenNlpLemmatizer.fromConfig(name, env.configDir(),
        settings.get(OpenNlpLemmatizer.POS_MODEL_SETTING),
        settings.get(OpenNlpLemmatizer.LEMMATIZER_MODEL_SETTING));
}
```

## Boundaries

- **Never commit** (all gitignored): `models/*.bin|*.lem|*.udpipe`, `**/target/`, native libraries,
  `mise.local.toml`, `**/.mvn/settings.xml`. No secrets, no company-internal references.
- **Never bundle** `org.opensearch:opensearch`, `org.elasticsearch:elasticsearch`, `lucene-core`,
  or `lucene-analysis-common` in a plugin zip — the node provides them and bundling causes JarHell.
  The assembly `includes` only `core` + `lucene-analysis-opennlp` + `opennlp-tools` + `slf4j-api`.
- Keep `opensearch/` and `elasticsearch/` behaviour-symmetric; only the platform glue differs.
- `experiments/udpipe/` is a standalone native-build PoC (not in the Maven reactor) with its own
  README and scripts — don't wire it into the plugins.

## Verify a change for real (optional)

Install the built zip into a node and check `_analyze`:

```bash
curl -XPOST localhost:9200/_analyze -H 'Content-Type: application/json' \
  -d '{"tokenizer":"whitespace","filter":[{"type":"opennlp_lemmatizer","pos_model":"cs-pos.bin","lemmatizer_model":"cs-lemmas.bin"}],"text":"Děkuji že jsi přišel"}'
# -> děkovat  že  být  přijít
```

## Commits

Conventional, imperative subject line. Do not add AI co-author trailers.
