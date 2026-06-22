# Custom Docker images

Bake the plugin and its models into a custom OpenSearch / Elasticsearch image. Each Dockerfile is
multi-stage: it builds the plugin from source **for the exact node version** (so the version match
is automatic) and copies the OpenNLP models into `config/opennlp/`.

Run everything from the **repo root** (the build context is the whole project).

```bash
# 1) fetch the models you want baked in
./scripts/fetch-models.sh cs
./scripts/fetch-models.sh sk

# 2a) OpenSearch
docker build -f examples/docker/opensearch.Dockerfile \
  --build-arg OPENSEARCH_VERSION=3.7.0 -t opensearch-opennlp:3.7.0 .

# 2b) Elasticsearch
docker build -f examples/docker/elasticsearch.Dockerfile \
  --build-arg ELASTICSEARCH_VERSION=9.4.2 -t elasticsearch-opennlp:9.4.2 .
```

Then run as you would the base image, e.g.:

```bash
docker run -p 9200:9200 -e discovery.type=single-node opensearch-opennlp:3.7.0
```

The `opennlp_lemmatizer` filter is ready immediately — models are already in `config/opennlp/`.

> Prefer pre-built artifacts? Skip stage 1 and `COPY` a plugin zip from a
> [GitHub Release](../../README.md#install) instead of building it.
