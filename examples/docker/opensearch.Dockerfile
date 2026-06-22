# Custom OpenSearch image with the OpenNLP lemmatizer plugin (built from source) + cs/sk models.
#
# Prereqs (from repo root): fetch the models you want baked in, e.g.
#   ./scripts/fetch-models.sh cs && ./scripts/fetch-models.sh sk
#
# Build (run from the repo root so the build context is the whole project):
#   docker build -f examples/docker/opensearch.Dockerfile \
#     --build-arg OPENSEARCH_VERSION=3.7.0 -t opensearch-opennlp:3.7.0 .
#
# syntax=docker/dockerfile:1
ARG OPENSEARCH_VERSION=3.7.0

# --- stage 1: build the plugin for this exact OpenSearch version ---
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
ARG OPENSEARCH_VERSION
RUN mvn -B -pl opensearch -am package -DskipTests -Dopensearch.version=${OPENSEARCH_VERSION}

# --- stage 2: the image ---
FROM opensearchproject/opensearch:${OPENSEARCH_VERSION}
COPY --from=build /src/opensearch/target/releases/opensearch-analysis-opennlp-lemmatizer-*.zip /tmp/plugin.zip
RUN /usr/share/opensearch/bin/opensearch-plugin install --batch file:///tmp/plugin.zip \
 && rm /tmp/plugin.zip
# models the plugin loads at runtime (must be fetched into ./models before building)
COPY models/*.bin /usr/share/opensearch/config/opennlp/
