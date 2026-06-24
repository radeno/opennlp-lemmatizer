package io.github.radeno.lemmatizer.opensearch;

import static org.opensearch.plugins.AnalysisPlugin.requiresAnalysisSettings;

import java.util.Map;

import org.opensearch.index.analysis.TokenFilterFactory;
import org.opensearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.opensearch.plugins.AnalysisPlugin;
import org.opensearch.plugins.Plugin;

/** Registers the lemmatizer token filters with OpenSearch. */
public class OpenSearchOpenNlpPlugin extends Plugin implements AnalysisPlugin {

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        return Map.of(
            "opennlp_lemmatizer", requiresAnalysisSettings(OpenNlpLemmatizerTokenFilterFactory::new),
            "dictionary_lemmatizer", requiresAnalysisSettings(DictionaryLemmatizerTokenFilterFactory::new),
            "pos_dictionary_lemmatizer", requiresAnalysisSettings(PosDictionaryLemmatizerTokenFilterFactory::new));
    }
}
