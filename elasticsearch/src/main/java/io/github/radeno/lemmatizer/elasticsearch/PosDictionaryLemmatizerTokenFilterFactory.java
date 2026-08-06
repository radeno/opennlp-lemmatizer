package io.github.radeno.lemmatizer.elasticsearch;

import io.github.radeno.lemmatizer.LemmatizerFilter;
import io.github.radeno.lemmatizer.LemmatizerFilters;

import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;

/**
 * Elasticsearch {@code pos_dictionary_lemmatizer} token filter. A POS-aware {@code form/POS/lemma} dictionary consulted first, the MaxEnt model filling the gaps.
 *
 * <p>Which settings it reads, and how they are validated, lives in
 * {@link LemmatizerFilters#posDictionary} — shared with the OpenSearch wrapper, which differs from this class
 * only in the {@code super(...)} call its base class requires.
 */
public class PosDictionaryLemmatizerTokenFilterFactory extends AbstractTokenFilterFactory {

    private final LemmatizerFilter lemmatizer;

    public PosDictionaryLemmatizerTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(name); // Elasticsearch 9.x: AbstractTokenFilterFactory(String name)
        this.lemmatizer = LemmatizerFilters.posDictionary(name, env.configDir(), settings::get,
            settings::getAsBoolean);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return lemmatizer.apply(tokenStream);
    }
}
