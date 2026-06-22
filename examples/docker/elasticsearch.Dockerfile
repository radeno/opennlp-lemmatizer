# Custom Elasticsearch image with the OpenNLP lemmatizer plugin (built from source) + cs/sk models.
#
# Prereqs (from repo root): fetch the models you want baked in, e.g.
#   ./scripts/fetch-models.sh cs && ./scripts/fetch-models.sh sk
#
# Build (run from the repo root so the build context is the whole project):
#   docker build -f examples/docker/elasticsearch.Dockerfile \
#     --build-arg ELASTICSEARCH_VERSION=9.4.2 -t elasticsearch-opennlp:9.4.2 .
#
# The plugin's elasticsearch.version must match the base image exactly — the build-arg handles both.
#
# syntax=docker/dockerfile:1
ARG ELASTICSEARCH_VERSION=9.4.2

# --- stage 1: build the plugin for this exact Elasticsearch version ---
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
ARG ELASTICSEARCH_VERSION
RUN mvn -B -pl elasticsearch -am package -DskipTests -Delasticsearch.version=${ELASTICSEARCH_VERSION}

# --- stage 2: the image ---
FROM docker.elastic.co/elasticsearch/elasticsearch:${ELASTICSEARCH_VERSION}
COPY --from=build /src/elasticsearch/target/releases/elasticsearch-analysis-opennlp-lemmatizer-*.zip /tmp/plugin.zip
RUN bin/elasticsearch-plugin install --batch file:///tmp/plugin.zip \
 && rm /tmp/plugin.zip
# models the plugin loads at runtime (must be fetched into ./models before building)
COPY models/*.bin config/opennlp/
