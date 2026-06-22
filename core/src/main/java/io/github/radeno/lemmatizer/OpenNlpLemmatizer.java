package io.github.radeno.lemmatizer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.opennlp.OpenNLPLemmatizerFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPPOSFilter;
import org.apache.lucene.analysis.opennlp.tools.NLPLemmatizerOp;
import org.apache.lucene.analysis.opennlp.tools.NLPPOSTaggerOp;

/**
 * Platform-independent OpenNLP, POS-aware lemmatization built on Lucene's OpenNLP analysis module.
 *
 * <p>OpenNLP lemmatization needs a part-of-speech tag per token, so {@link #apply(TokenStream)}
 * chains an OpenNLP POS tagger in front of the lemmatizer. The (immutable) models are loaded once;
 * a fresh {@link NLPPOSTaggerOp}/{@link NLPLemmatizerOp} pair is created per stream because the
 * underlying OpenNLP {@code *ME} instances are not thread-safe.
 *
 * <p>This class has no OpenSearch/Elasticsearch dependency — the thin platform wrappers reuse it,
 * along with the shared {@link #MODELS_DIRECTORY} / setting-name constants and {@link #fromConfig}.
 */
public final class OpenNlpLemmatizer {

    /** Sub-directory of the node's config dir holding the models: {@code <config>/opennlp/}. */
    public static final String MODELS_DIRECTORY = "opennlp";
    /** Token-filter setting naming the OpenNLP POS model file. */
    public static final String POS_MODEL_SETTING = "pos_model";
    /** Token-filter setting naming the OpenNLP lemmatizer model file. */
    public static final String LEMMATIZER_MODEL_SETTING = "lemmatizer_model";

    private final POSModel posModel;
    private final LemmatizerModel lemmatizerModel;

    private OpenNlpLemmatizer(POSModel posModel, LemmatizerModel lemmatizerModel) {
        this.posModel = posModel;
        this.lemmatizerModel = lemmatizerModel;
    }

    /**
     * Resolve and load the models from {@code <configDir>/opennlp/}, validating the settings.
     * Used by the OpenSearch and Elasticsearch token-filter factories.
     *
     * @param filterName the token-filter name, used only in the validation error message
     * @throws IllegalArgumentException if either model file name is missing/blank
     * @throws UncheckedIOException     if a model cannot be read
     */
    public static OpenNlpLemmatizer fromConfig(String filterName, Path configDir,
                                               String posModelFile, String lemmatizerModelFile) {
        if (isBlank(posModelFile) || isBlank(lemmatizerModelFile)) {
            throw new IllegalArgumentException("[" + filterName + "] token filter requires both '"
                + POS_MODEL_SETTING + "' and '" + LEMMATIZER_MODEL_SETTING + "' settings");
        }
        Path dir = configDir.resolve(MODELS_DIRECTORY);
        return fromModels(dir.resolve(posModelFile), dir.resolve(lemmatizerModelFile));
    }

    /** Load directly from the two model file paths. */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath) {
        return new OpenNlpLemmatizer(
            load(posModelPath, POSModel::new, "POS"),
            load(lemmatizerModelPath, LemmatizerModel::new, "lemmatizer"));
    }

    /** Wrap {@code input} with the OpenNLP POS tagger followed by the lemmatizer. */
    public TokenStream apply(TokenStream input) {
        var posOp = new NLPPOSTaggerOp(posModel);
        NLPLemmatizerOp lemmaOp;
        try {
            lemmaOp = new NLPLemmatizerOp(null, lemmatizerModel); // null dictionary -> model-only
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize OpenNLP lemmatizer", e);
        }
        return new OpenNLPLemmatizerFilter(new OpenNLPPOSFilter(input, posOp), lemmaOp);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @FunctionalInterface
    private interface ModelFactory<M> {
        M create(InputStream in) throws IOException;
    }

    private static <M> M load(Path path, ModelFactory<M> factory, String kind) {
        try (var in = new BufferedInputStream(Files.newInputStream(path))) {
            return factory.create(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load OpenNLP " + kind + " model from " + path, e);
        }
    }
}
