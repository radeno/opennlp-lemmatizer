package io.github.radeno.lemmatizer.elasticsearch;

import static org.elasticsearch.plugins.AnalysisPlugin.requiresAnalysisSettings;

import java.util.Map;

import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;

/** Registers the lemmatizer token filters with Elasticsearch. */
public class ElasticsearchOpenNlpPlugin extends Plugin implements AnalysisPlugin {

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        return Map.of(
            "opennlp_lemmatizer", requiresAnalysisSettings(OpenNlpLemmatizerTokenFilterFactory::new),
            "dictionary_lemmatizer", requiresAnalysisSettings(DictionaryLemmatizerTokenFilterFactory::new),
            "pos_dictionary_lemmatizer", requiresAnalysisSettings(PosDictionaryLemmatizerTokenFilterFactory::new));
    }
}
