package io.github.radeno.lemmatizer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import opennlp.tools.lemmatizer.Lemmatizer;
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
    private final Lemmatizer lemmaDictionary; // nullable; shared, consulted before the model

    private OpenNlpLemmatizer(POSModel posModel, LemmatizerModel lemmatizerModel,
                              Lemmatizer lemmaDictionary) {
        this.posModel = posModel;
        this.lemmatizerModel = lemmatizerModel;
        this.lemmaDictionary = lemmaDictionary;
    }

    /**
     * Pure model-only OpenNLP lemmatizer (POS tagger + MaxEnt lemmatizer model), loaded from
     * {@code <configDir>/opennlp/}. Used by the {@code opennlp_lemmatizer} filter factory.
     *
     * @param filterName the token-filter name, used only in the validation error message
     * @throws IllegalArgumentException if either model file name is missing/blank
     * @throws UncheckedIOException     if a model cannot be read
     */
    public static OpenNlpLemmatizer fromConfig(String filterName, Path configDir,
                                               String posModelFile, String lemmatizerModelFile) {
        return fromConfig(filterName, configDir, posModelFile, lemmatizerModelFile, null);
    }

    /**
     * As {@link #fromConfig(String, Path, String, String)} plus a {@code form<TAB>POS<TAB>lemma}
     * dictionary consulted before the model. Used by the {@code pos_dictionary_lemmatizer} factory.
     */
    public static OpenNlpLemmatizer fromConfig(String filterName, Path configDir, String posModelFile,
                                               String lemmatizerModelFile, String lemmatizerDictFile) {
        if (isBlank(posModelFile) || isBlank(lemmatizerModelFile)) {
            throw new IllegalArgumentException("[" + filterName + "] token filter requires both '"
                + POS_MODEL_SETTING + "' and '" + LEMMATIZER_MODEL_SETTING + "' settings");
        }
        Path dir = configDir.resolve(MODELS_DIRECTORY);
        Path dictPath = isBlank(lemmatizerDictFile) ? null : dir.resolve(lemmatizerDictFile);
        return fromModels(dir.resolve(posModelFile), dir.resolve(lemmatizerModelFile), dictPath);
    }

    /** Load directly from the two model file paths (no lemmatizer dictionary). */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath) {
        return fromModels(posModelPath, lemmatizerModelPath, null);
    }

    /** As {@link #fromModels(Path, Path)} with an optional {@code form<TAB>POS<TAB>lemma} dictionary. */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath, Path dictPath) {
        return new OpenNlpLemmatizer(
            load(posModelPath, POSModel::new, "POS"),
            load(lemmatizerModelPath, LemmatizerModel::new, "lemmatizer"),
            dictPath == null ? null : FstPosDictionaryLemmatizer.fromFile(dictPath));
    }

    /** Wrap {@code input} with the OpenNLP POS tagger followed by the lemmatizer. */
    public TokenStream apply(TokenStream input) {
        var tagged = new OpenNLPPOSFilter(input, new NLPPOSTaggerOp(posModel));
        if (lemmaDictionary != null) {
            // POS-aware: shared dictionary first, MaxEnt model fallback
            return new OpenNlpPosLemmatizerFilter(tagged, lemmaDictionary, lemmatizerModel);
        }
        NLPLemmatizerOp lemmaOp;
        try {
            lemmaOp = new NLPLemmatizerOp(null, lemmatizerModel); // null dictionary -> model-only
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize OpenNLP lemmatizer", e);
        }
        return new OpenNLPLemmatizerFilter(tagged, lemmaOp);
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
