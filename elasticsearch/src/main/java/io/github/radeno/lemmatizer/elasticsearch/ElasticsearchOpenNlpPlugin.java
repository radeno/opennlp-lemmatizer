package io.github.radeno.lemmatizer.elasticsearch;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.plugins.AnalysisPlugin.requiresAnalysisSettings;

import java.util.Map;

import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;

/** Registers the {@code opennlp_lemmatizer} token filter with Elasticsearch. */
public class ElasticsearchOpenNlpPlugin extends Plugin implements AnalysisPlugin {

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        return singletonMap(
            "opennlp_lemmatizer",
            requiresAnalysisSettings(OpenNlpLemmatizerTokenFilterFactory::new));
    }
}
