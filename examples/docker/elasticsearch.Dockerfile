# Custom Elasticsearch image with the OpenNLP lemmatizer plugin + language models, built end-to-end.
# The build stage compiles the plugin for the exact node version AND downloads the models via
# scripts/fetch-models.sh — nothing to prepare by hand.
#
# Build (from the repo root):
#   docker build -f examples/docker/elasticsearch.Dockerfile \
#     --build-arg ELASTICSEARCH_VERSION=9.4.2 --build-arg LANGS="cs sk" -t elasticsearch-opennlp:9.4.2 .
#
# LANGS is a space-separated list passed to fetch-models.sh (e.g. "cs sk", or "sk-mte").
# The plugin's elasticsearch.version must match the base image exactly — the build-arg handles both.
#
# syntax=docker/dockerfile:1
ARG ELASTICSEARCH_VERSION=9.4.2

# --- stage 1: build the plugin for this exact version + fetch the models ---
FROM maven:3.9-eclipse-temurin-25 AS build
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
ARG ELASTICSEARCH_VERSION
ARG LANGS="cs sk"
RUN mvn -B -pl elasticsearch -am package -DskipTests -Delasticsearch.version=${ELASTICSEARCH_VERSION}
RUN mkdir -p models && for lang in ${LANGS}; do bash scripts/fetch-models.sh "${lang}"; done

# --- stage 2: the image ---
FROM docker.elastic.co/elasticsearch/elasticsearch:${ELASTICSEARCH_VERSION}
COPY --from=build /src/elasticsearch/target/releases/elasticsearch-analysis-opennlp-lemmatizer-*.zip /tmp/plugin.zip
RUN bin/elasticsearch-plugin install --batch file:///tmp/plugin.zip \
 && rm /tmp/plugin.zip
COPY --from=build /src/models/ config/opennlp/
