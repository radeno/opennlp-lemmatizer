# Custom OpenSearch image with the OpenNLP lemmatizer plugin + language models, built end-to-end.
# The build stage compiles the plugin for the exact node version AND downloads the models via
# scripts/fetch-models.sh — nothing to prepare by hand.
#
# Build (from the repo root):
#   docker build -f examples/docker/opensearch.Dockerfile \
#     --build-arg OPENSEARCH_VERSION=3.7.0 --build-arg LANGS="cs sk" -t opensearch-opennlp:3.7.0 .
#
# LANGS is a space-separated list passed to fetch-models.sh (e.g. "cs sk", or "sk-mte").
#
# syntax=docker/dockerfile:1
ARG OPENSEARCH_VERSION=3.7.0

# --- stage 1: build the plugin for this exact version + fetch the models ---
FROM maven:3.9-eclipse-temurin-25 AS build
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
ARG OPENSEARCH_VERSION
ARG LANGS="cs sk"
RUN mvn -B -pl opensearch -am package -DskipTests -Dopensearch.version=${OPENSEARCH_VERSION}
RUN mkdir -p models && for lang in ${LANGS}; do bash scripts/fetch-models.sh "${lang}"; done

# --- stage 2: the image ---
FROM opensearchproject/opensearch:${OPENSEARCH_VERSION}
COPY --from=build /src/opensearch/target/releases/opensearch-analysis-opennlp-lemmatizer-*.zip /tmp/plugin.zip
RUN /usr/share/opensearch/bin/opensearch-plugin install --batch file:///tmp/plugin.zip \
 && rm /tmp/plugin.zip
COPY --from=build /src/models/ /usr/share/opensearch/config/opennlp/
