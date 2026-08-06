package io.github.radeno.lemmatizer.opensearch;

import io.github.radeno.lemmatizer.LemmatizerFilter;
import io.github.radeno.lemmatizer.LemmatizerFilters;

import org.apache.lucene.analysis.TokenStream;
import org.opensearch.common.settings.Settings;
import org.opensearch.env.Environment;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.analysis.AbstractTokenFilterFactory;

/**
 * OpenSearch {@code dictionary_lemmatizer} token filter. Flat {@code form -> lemma} lookup, no part of speech, maximum speed.
 *
 * <p>Which settings it reads, and how they are validated, lives in
 * {@link LemmatizerFilters#dictionary} — shared with the Elasticsearch wrapper, which differs from this class
 * only in the {@code super(...)} call its base class requires.
 */
public class DictionaryLemmatizerTokenFilterFactory extends AbstractTokenFilterFactory {

    private final LemmatizerFilter lemmatizer;

    public DictionaryLemmatizerTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(indexSettings, name, settings);
        this.lemmatizer = LemmatizerFilters.dictionary(name, env.configDir(), settings::get,
            settings::getAsBoolean);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return lemmatizer.apply(tokenStream);
    }
}
