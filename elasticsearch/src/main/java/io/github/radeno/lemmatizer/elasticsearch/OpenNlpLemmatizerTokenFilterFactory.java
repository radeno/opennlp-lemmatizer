package io.github.radeno.lemmatizer.elasticsearch;

import io.github.radeno.lemmatizer.LemmatizerFilter;
import io.github.radeno.lemmatizer.LemmatizerFilters;

import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;

/**
 * Elasticsearch {@code opennlp_lemmatizer} token filter. POS-aware lemmatization by the OpenNLP MaxEnt model, no dictionary.
 *
 * <p>Which settings it reads, and how they are validated, lives in
 * {@link LemmatizerFilters#opennlp} — shared with the OpenSearch wrapper, which differs from this class
 * only in the {@code super(...)} call its base class requires.
 */
public class OpenNlpLemmatizerTokenFilterFactory extends AbstractTokenFilterFactory {

    private final LemmatizerFilter lemmatizer;

    public OpenNlpLemmatizerTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(name); // Elasticsearch 9.x: AbstractTokenFilterFactory(String name)
        this.lemmatizer = LemmatizerFilters.opennlp(name, env.configDir(), settings::get,
            settings::getAsBoolean);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return lemmatizer.apply(tokenStream);
    }
}
