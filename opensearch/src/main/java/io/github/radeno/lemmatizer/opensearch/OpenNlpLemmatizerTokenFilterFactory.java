package io.github.radeno.lemmatizer.opensearch;

import io.github.radeno.lemmatizer.OpenNlpLemmatizer;

import org.apache.lucene.analysis.TokenStream;
import org.opensearch.common.settings.Settings;
import org.opensearch.env.Environment;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.analysis.AbstractTokenFilterFactory;

/**
 * OpenSearch token filter that lemmatizes tokens with Apache OpenNLP (POS-aware).
 *
 * <p>Models are loaded from {@code <config>/opennlp/}. Required settings:
 * {@link OpenNlpLemmatizer#POS_MODEL_SETTING} and {@link OpenNlpLemmatizer#LEMMATIZER_MODEL_SETTING}
 * (the {@code .bin} file names).
 */
public class OpenNlpLemmatizerTokenFilterFactory extends AbstractTokenFilterFactory {

    private final OpenNlpLemmatizer lemmatizer;

    public OpenNlpLemmatizerTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(indexSettings, name, settings);
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
