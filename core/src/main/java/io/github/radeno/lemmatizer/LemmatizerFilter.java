package io.github.radeno.lemmatizer;

import org.apache.lucene.analysis.TokenStream;

/**
 * What a configured lemmatizer hands a platform wrapper: something that wraps a token stream. Both
 * {@link OpenNlpLemmatizer} and the POS-free {@link DictionaryLemmatizer} already did exactly this;
 * naming it lets {@link LemmatizerFilters} return one type, and so lets the six OpenSearch and
 * Elasticsearch wrappers hold one field and share one body.
 */
public interface LemmatizerFilter {

    /** Wrap {@code input} with this lemmatizer's filter chain. */
    TokenStream apply(TokenStream input);
}
