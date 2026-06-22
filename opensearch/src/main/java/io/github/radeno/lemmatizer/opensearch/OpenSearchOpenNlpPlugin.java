package io.github.radeno.lemmatizer.opensearch;

import static java.util.Collections.singletonMap;
import static org.opensearch.plugins.AnalysisPlugin.requiresAnalysisSettings;

import java.util.Map;

import org.opensearch.index.analysis.TokenFilterFactory;
import org.opensearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.opensearch.plugins.AnalysisPlugin;
import org.opensearch.plugins.Plugin;

/** Registers the {@code opennlp_lemmatizer} token filter with OpenSearch. */
public class OpenSearchOpenNlpPlugin extends Plugin implements AnalysisPlugin {

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        return singletonMap(
            "opennlp_lemmatizer",
            requiresAnalysisSettings(OpenNlpLemmatizerTokenFilterFactory::new));
    }
}
