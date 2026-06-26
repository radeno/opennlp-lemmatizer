package io.github.radeno.lemmatizer.opensearch;

import io.github.radeno.lemmatizer.DictionaryLemmatizer;

import org.apache.lucene.analysis.TokenStream;
import org.opensearch.common.settings.Settings;
import org.opensearch.env.Environment;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.analysis.AbstractTokenFilterFactory;

/**
 * OpenSearch token filter that lemmatizes tokens by flat FST dictionary lookup (fast, POS-free,
 * low-memory).
 *
 * <p>Loads a {@code form<TAB>lemma} dictionary from {@code <config>/opennlp/}. Required setting:
 * {@link DictionaryLemmatizer#DICTIONARY_SETTING} (the dictionary file name).
 */
public class DictionaryLemmatizerTokenFilterFactory extends AbstractTokenFilterFactory {

    private final DictionaryLemmatizer lemmatizer;

    public DictionaryLemmatizerTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(indexSettings, name, settings);
        this.lemmatizer = DictionaryLemmatizer.fromConfig(
            name, env.configDir(), settings.get(DictionaryLemmatizer.DICTIONARY_SETTING));
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return lemmatizer.apply(tokenStream);
    }
}
