package io.github.radeno.lemmatizer.elasticsearch;

import io.github.radeno.lemmatizer.DictionaryLemmatizer;
import io.github.radeno.lemmatizer.OpenNlpLemmatizer;

import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;

/**
 * Elasticsearch token filter combining a POS-aware {@code form<TAB>POS<TAB>lemma} dictionary (e.g.
 * MULTEXT-East) with the OpenNLP MaxEnt model as fallback: the dictionary is consulted first and the
 * model fills only the gaps. Required settings (files under {@code <config>/opennlp/}):
 * {@code pos_model}, {@code lemmatizer_model}, {@code dictionary}.
 */
public class PosDictionaryLemmatizerTokenFilterFactory extends AbstractTokenFilterFactory {

    private final OpenNlpLemmatizer lemmatizer;

    public PosDictionaryLemmatizerTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(name); // Elasticsearch 9.x: AbstractTokenFilterFactory(String name)
        String dictionary = settings.get(DictionaryLemmatizer.DICTIONARY_SETTING);
        if (dictionary == null || dictionary.isBlank()) {
            throw new IllegalArgumentException("[" + name + "] pos_dictionary_lemmatizer requires a '"
                + DictionaryLemmatizer.DICTIONARY_SETTING + "' setting (a form<TAB>POS<TAB>lemma file)");
        }
        this.lemmatizer = OpenNlpLemmatizer.fromConfig(
            name,
            env.configDir(),
            settings.get(OpenNlpLemmatizer.POS_MODEL_SETTING),
            settings.get(OpenNlpLemmatizer.LEMMATIZER_MODEL_SETTING),
            dictionary);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return lemmatizer.apply(tokenStream);
    }
}
