package io.github.radeno.lemmatizer.elasticsearch;

import io.github.radeno.lemmatizer.LemmatizerFilter;
import io.github.radeno.lemmatizer.LemmatizerFilters;

import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;

/**
 * Elasticsearch {@code dictionary_lemmatizer} token filter. Flat {@code form -> lemma} lookup, no part of speech, maximum speed.
 *
 * <p>Which settings it reads, and how they are validated, lives in
 * {@link LemmatizerFilters#dictionary} — shared with the OpenSearch wrapper, which differs from this class
 * only in the {@code super(...)} call its base class requires.
 */
public class DictionaryLemmatizerTokenFilterFactory extends AbstractTokenFilterFactory {

    private final LemmatizerFilter lemmatizer;

    public DictionaryLemmatizerTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(name); // Elasticsearch 9.x: AbstractTokenFilterFactory(String name)
        this.lemmatizer = LemmatizerFilters.dictionary(name, env.configDir(), settings::get,
            settings::getAsBoolean);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return lemmatizer.apply(tokenStream);
    }
}
