package io.github.radeno.lemmatizer.opensearch;

import static org.hamcrest.Matchers.instanceOf;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import io.github.radeno.lemmatizer.OpenNlpLemmatizer;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.env.Environment;
import org.opensearch.index.analysis.AnalysisTestsHelper;
import org.opensearch.index.analysis.TokenFilterFactory;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.OpenSearchTokenStreamTestCase;

public class OpenNlpLemmatizerOpenSearchTests extends OpenSearchTokenStreamTestCase {

    // Czech OpenNLP models are large (~16 MB) and not committed. Fetch with scripts/fetch-models.sh cs.
    // Surefire points opennlp.models.dir at <repo-root>/models; the test self-skips when absent.
    private static final String MODELS_DIR = System.getProperty("opennlp.models.dir", "models");

    public void testCzechLemmatization() throws IOException {
        Path pos = Paths.get(MODELS_DIR, "cs-pos.bin");
        Path lemma = Paths.get(MODELS_DIR, "cs-lemmas.bin");
        assumeTrue("Czech OpenNLP models not found in " + MODELS_DIR + " (run scripts/fetch-models.sh cs)",
            Files.isReadable(pos) && Files.isReadable(lemma));

        OpenSearchTestCase.TestAnalysis analysis = createAnalysis(pos, lemma);
        TokenFilterFactory filter = analysis.tokenFilter.get("cs_lemma");
        assertThat(filter, instanceOf(OpenNlpLemmatizerTokenFilterFactory.class));

        Tokenizer tokenizer = new WhitespaceTokenizer();
        tokenizer.setReader(new StringReader("Děkuji že jsi přišel"));
        assertTokenStreamContents(filter.create(tokenizer), new String[] { "děkovat", "že", "být", "přijít" });
    }

    private OpenSearchTestCase.TestAnalysis createAnalysis(Path pos, Path lemma) throws IOException {
        Path home = createTempDir();
        Path config = home.resolve("config").resolve(OpenNlpLemmatizer.MODELS_DIRECTORY);
        Files.createDirectories(config);
        Files.copy(pos, config.resolve("cs-pos.bin"));
        Files.copy(lemma, config.resolve("cs-lemmas.bin"));

        String path = "/io/github/radeno/lemmatizer/opennlp.json";
        Settings settings = Settings.builder()
            .loadFromStream(path, getClass().getResourceAsStream(path), false)
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(Environment.PATH_HOME_SETTING.getKey(), home)
            .build();
        return AnalysisTestsHelper.createTestAnalysisFromSettings(settings, new OpenSearchOpenNlpPlugin());
    }
}
