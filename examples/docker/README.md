# Custom Docker images

Bake the plugin and its models into a custom OpenSearch / Elasticsearch image. Each Dockerfile is
multi-stage and **fully self-contained** — it builds the plugin for the exact node version *and*
downloads the language models with `scripts/fetch-models.sh`. Nothing to prepare by hand.

Run from the **repo root** (the build context is the whole project). Pick languages with `LANGS`:

```bash
# OpenSearch
docker build -f examples/docker/opensearch.Dockerfile \
  --build-arg OPENSEARCH_VERSION=3.7.0 --build-arg LANGS="cs sk" -t opensearch-opennlp:3.7.0 .

# Elasticsearch
docker build -f examples/docker/elasticsearch.Dockerfile \
  --build-arg ELASTICSEARCH_VERSION=9.4.2 --build-arg LANGS="cs sk" -t elasticsearch-opennlp:9.4.2 .
```

`LANGS` is passed straight to `fetch-models.sh`, so it accepts the official OpenNLP languages
(`cs`, `sk`, …) and the dictionary source (`sk-michmech`). Run as usual:

```bash
docker run -p 9200:9200 -e discovery.type=single-node opensearch-opennlp:3.7.0
```

The `opennlp_lemmatizer` / `dictionary_lemmatizer` filters are ready immediately — the models are
already in `config/opennlp/`.

> Prefer pre-built artifacts? Skip the build stage and `COPY` a plugin zip from a
> [GitHub Release](../../README.md#install) instead.
