package io.github.radeno.lemmatizer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import opennlp.tools.lemmatizer.Lemmatizer;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagFormat;
import opennlp.tools.postag.POSTaggerME;

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
    /**
     * Token-filter setting choosing the POS tagset the tagger emits: {@code penn} (default) keeps
     * Lucene's behaviour of normalising the model's tags to the Penn tagset; {@code native} preserves
     * the model's own tagset verbatim (e.g. a UD/UPOS or UPOS+gender model).
     *
     * <p><b>The dictionary's POS column must match the chosen format.</b> Use {@code native} only with a
     * dictionary keyed on the model's native tags (e.g. the UPOS+gender model + dictionary). Pairing
     * {@code native} with a Penn-keyed dictionary (the standard {@code -mte-pos} build) makes every
     * lookup miss — the model emits UD {@code NOUN} while the dictionary holds Penn {@code NN} — so it
     * degrades to model fallback. Keep {@code penn} for the standard dictionary.
     */
    public static final String POS_FORMAT_SETTING = "pos_format";

    // Node-wide dedup caches (shared via the per-node plugin classloader); see {@link ModelCache}. Each
    // heavy artifact (POS model, lemmatizer model, FST dictionary) is loaded once per file and reused
    // across every index on the node instead of once per (index, filter).
    private static final ConcurrentHashMap<String, ModelCache.Cached<POSModel>> POS_MODEL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ModelCache.Cached<LemmatizerModel>> LEMMA_MODEL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ModelCache.Cached<Lemmatizer>> DICTIONARY_CACHE = new ConcurrentHashMap<>();

    private final POSModel posModel;
    private final LemmatizerModel lemmatizerModel;
    private final Lemmatizer lemmaDictionary; // nullable; shared, consulted before the model
    private final boolean nativePosTags;      // true -> preserve the model's tagset (POSTagFormat.CUSTOM)

    private OpenNlpLemmatizer(POSModel posModel, LemmatizerModel lemmatizerModel,
                              Lemmatizer lemmaDictionary, boolean nativePosTags) {
        this.posModel = posModel;
        this.lemmatizerModel = lemmatizerModel;
        this.lemmaDictionary = lemmaDictionary;
        this.nativePosTags = nativePosTags;
    }

    /** Whether {@code value} requests the model's native tagset rather than Penn normalisation. */
    public static boolean isNativePosFormat(String value) {
        return value != null && (value.equalsIgnoreCase("native") || value.equalsIgnoreCase("custom"));
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
        return fromConfig(filterName, configDir, posModelFile, lemmatizerModelFile, null, false);
    }

    /**
     * As {@link #fromConfig(String, Path, String, String)} plus a {@code form<TAB>POS<TAB>lemma}
     * dictionary consulted before the model. Used by the {@code pos_dictionary_lemmatizer} factory.
     * {@code nativePosTags} preserves the POS model's own tagset (see {@link #POS_FORMAT_SETTING}).
     */
    public static OpenNlpLemmatizer fromConfig(String filterName, Path configDir, String posModelFile,
                                               String lemmatizerModelFile, String lemmatizerDictFile,
                                               boolean nativePosTags) {
        if (isBlank(posModelFile) || isBlank(lemmatizerModelFile)) {
            throw new IllegalArgumentException("[" + filterName + "] token filter requires both '"
                + POS_MODEL_SETTING + "' and '" + LEMMATIZER_MODEL_SETTING + "' settings");
        }
        Path dir = configDir.resolve(MODELS_DIRECTORY);
        Path dictPath = isBlank(lemmatizerDictFile) ? null : dir.resolve(lemmatizerDictFile);
        return fromModels(dir.resolve(posModelFile), dir.resolve(lemmatizerModelFile), dictPath, nativePosTags);
    }

    /** Load directly from the two model file paths (no lemmatizer dictionary). */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath) {
        return fromModels(posModelPath, lemmatizerModelPath, null, false);
    }

    /** As {@link #fromModels(Path, Path)} with an optional {@code form<TAB>POS<TAB>lemma} dictionary. */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath, Path dictPath) {
        return fromModels(posModelPath, lemmatizerModelPath, dictPath, false);
    }

    /** As {@link #fromModels(Path, Path, Path)} choosing whether to keep the model's native tagset. */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath, Path dictPath,
                                               boolean nativePosTags) {
        return new OpenNlpLemmatizer(
            ModelCache.loadShared(POS_MODEL_CACHE, posModelPath, p -> load(p, POSModel::new, "POS")),
            ModelCache.loadShared(LEMMA_MODEL_CACHE, lemmatizerModelPath, p -> load(p, LemmatizerModel::new, "lemmatizer")),
            dictPath == null ? null : ModelCache.loadShared(DICTIONARY_CACHE, dictPath, FstPosDictionaryLemmatizer::fromFile),
            nativePosTags);
    }

    /** Wrap {@code input} with the OpenNLP POS tagger followed by the lemmatizer. */
    public TokenStream apply(TokenStream input) {
        // Lucene's NLPPOSTaggerOp hard-codes POSTagFormat.PENN; for a non-Penn model (e.g. UPOS+gender)
        // use a CUSTOM-format tagger so the dictionary sees the tags the model actually emits.
        var posOp = nativePosTags ? new NativeFormatPosTaggerOp(posModel) : new NLPPOSTaggerOp(posModel);
        var tagged = new OpenNLPPOSFilter(input, posOp);
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

    /**
     * A {@link NLPPOSTaggerOp} that emits the POS model's <b>native</b> tagset. Lucene's stock
     * {@code NLPPOSTaggerOp} builds {@code new POSTaggerME(model, POSTagFormat.PENN)}, coercing every
     * tag to the Penn tagset — which silently mangles a UD/UPOS(+gender) model ({@code NOUN.Masc} →
     * {@code ?}). This subclass tags with {@link POSTagFormat#CUSTOM} so the dictionary receives the
     * tags the model was trained to produce. The superclass still builds its (unused) Penn tagger.
     */
    private static final class NativeFormatPosTaggerOp extends NLPPOSTaggerOp {
        private final POSTaggerME nativeTagger;

        NativeFormatPosTaggerOp(POSModel model) {
            super(model);
            this.nativeTagger = new POSTaggerME(model, POSTagFormat.CUSTOM);
        }

        @Override
        public synchronized String[] getPOSTags(String[] sentence) {
            return nativeTagger.tag(sentence);
        }
    }
}
