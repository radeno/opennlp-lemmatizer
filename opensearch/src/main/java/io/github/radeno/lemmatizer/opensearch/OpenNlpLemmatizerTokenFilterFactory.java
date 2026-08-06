package io.github.radeno.lemmatizer.opensearch;

import io.github.radeno.lemmatizer.LemmatizerFilter;
import io.github.radeno.lemmatizer.LemmatizerFilters;

import org.apache.lucene.analysis.TokenStream;
import org.opensearch.common.settings.Settings;
import org.opensearch.env.Environment;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.analysis.AbstractTokenFilterFactory;

/**
 * OpenSearch {@code opennlp_lemmatizer} token filter. POS-aware lemmatization by the OpenNLP MaxEnt model, no dictionary.
 *
 * <p>Which settings it reads, and how they are validated, lives in
 * {@link LemmatizerFilters#opennlp} — shared with the Elasticsearch wrapper, which differs from this class
 * only in the {@code super(...)} call its base class requires.
 */
public class OpenNlpLemmatizerTokenFilterFactory extends AbstractTokenFilterFactory {

    private final LemmatizerFilter lemmatizer;

    public OpenNlpLemmatizerTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(indexSettings, name, settings);
        this.lemmatizer = LemmatizerFilters.opennlp(name, env.configDir(), settings::get,
            settings::getAsBoolean);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return lemmatizer.apply(tokenStream);
    }
}
