package io.github.radeno.lemmatizer.elasticsearch;

import io.github.radeno.lemmatizer.OpenNlpLemmatizer;

import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;

/**
 * Elasticsearch token filter that lemmatizes tokens with Apache OpenNLP (POS-aware).
 *
 * <p>Models are loaded from {@code <config>/opennlp/}. Required settings:
 * {@link OpenNlpLemmatizer#POS_MODEL_SETTING} and {@link OpenNlpLemmatizer#LEMMATIZER_MODEL_SETTING}
 * (the {@code .bin} file names).
 */
public class OpenNlpLemmatizerTokenFilterFactory extends AbstractTokenFilterFactory {

    private final OpenNlpLemmatizer lemmatizer;

    public OpenNlpLemmatizerTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(name); // Elasticsearch 9.x: AbstractTokenFilterFactory(String name)
        this.lemmatizer = OpenNlpLemmatizer.fromConfig(
            name,
            env.configDir(),
            settings.get(OpenNlpLemmatizer.POS_MODEL_SETTING),
            settings.get(OpenNlpLemmatizer.LEMMATIZER_MODEL_SETTING));
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return lemmatizer.apply(tokenStream);
    }
}
