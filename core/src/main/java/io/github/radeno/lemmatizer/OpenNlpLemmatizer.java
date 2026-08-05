package io.github.radeno.lemmatizer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import opennlp.tools.lemmatizer.Lemmatizer;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagFormat;
import opennlp.tools.postag.POSTaggerME;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.KeywordRepeatFilter;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
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
    /**
     * Token-filter setting turning the MaxEnt model fallback off on {@code pos_dictionary_lemmatizer}.
     * With {@code model_fallback: false} a {@code (word, POS)} pair the dictionary does not cover leaves
     * its token unchanged instead of being guessed by the model — a pure dictionary filter with
     * predictable output, and {@link #LEMMATIZER_MODEL_SETTING} is then not needed. Defaults to
     * {@code true}. Ignored by the model-only {@code opennlp_lemmatizer} filter, which has no dictionary.
     */
    public static final String MODEL_FALLBACK_SETTING = "model_fallback";
    /**
     * Token-filter setting emitting the original token alongside its lemma, at the same position
     * ({@code positionIncrement: 0}), so a document still matches on the surface form when the lemma is
     * wrong — a bad model guess, a homonym resolved for the wrong domain, or a proper noun the
     * dictionary lower-cases. Defaults to {@code false}. A token whose lemma equals it is emitted once,
     * so only genuinely rewritten tokens cost an extra posting.
     *
     * <p>Shared by all three filters, including the POS-free {@code dictionary_lemmatizer} — the name
     * lives here beside the other filter settings rather than being spelled twice.
     */
    public static final String KEEP_ORIGINAL_SETTING = "keep_original";
    /**
     * Token-filter setting matching tokens that differ from a dictionary form only by Unicode folding —
     * diacritics above all, but also case, ligatures and compatibility forms (see {@link UnicodeFolder}).
     * Defaults to {@code false}.
     *
     * <p>Dictionary lookup is exact byte matching, so a Slovak lexicon whose forms carry diacritics
     * misses three quarters of itself on {@code ruzomberku}, and a Greek one misses everything typed
     * without accents. With this on, a second automaton keyed on folded forms is built and consulted
     * <b>after</b> both exact attempts, recovering {@code ruzomberku -> Ružomberok} and
     * {@code αθηνα -> Αθήνα}.
     *
     * <p>It is off by default because it is a recall/precision trade, not a fix: the folded automaton
     * roughly doubles the key count, and a folded hit is an inference from a differently written form
     * rather than an entry the dictionary holds. Two things keep the cost contained — the folded lookup
     * never runs while an exact one can succeed, and it is refused for tokens not already written in
     * folded shape, so correctly spelled text is left on exactly the path it takes today.
     */
    public static final String UNICODE_FOLDING_SETTING = "unicode_folding";

    /** Accepted {@link #POS_FORMAT_SETTING} values, lower-cased. */
    private static final Set<String> PENN_POS_FORMATS = Set.of("penn");
    private static final Set<String> NATIVE_POS_FORMATS = Set.of("native", "custom");

    // Node-wide dedup caches (shared via the per-node plugin classloader); see {@link ModelCache}. Each
    // heavy artifact (POS model, lemmatizer model, FST dictionary) is loaded once per file and reused
    // across every index on the node instead of once per (index, filter).
    private static final ConcurrentHashMap<String, ModelCache.Cached<POSModel>> POS_MODEL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ModelCache.Cached<LemmatizerModel>> LEMMA_MODEL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ModelCache.Cached<Lemmatizer>> DICTIONARY_CACHE = new ConcurrentHashMap<>();

    private final POSModel posModel;
    private final LemmatizerModel lemmatizerModel;   // nullable when the model fallback is off
    private final Lemmatizer lemmaDictionary;        // nullable; shared, consulted before the model
    private final FoldedLemmaLookup foldedDictionary; // nullable; null -> unicode_folding off
    private final boolean nativePosTags;             // true -> preserve the model's tagset (POSTagFormat.CUSTOM)
    private final boolean modelFallback;             // false -> dictionary misses leave the token unchanged
    private final boolean keepOriginal;              // true -> also emit the surface form at the same position

    private OpenNlpLemmatizer(POSModel posModel, LemmatizerModel lemmatizerModel, Lemmatizer lemmaDictionary,
                              boolean unicodeFolding, boolean nativePosTags, boolean modelFallback,
                              boolean keepOriginal) {
        this.posModel = posModel;
        this.lemmatizerModel = lemmatizerModel;
        this.lemmaDictionary = lemmaDictionary;
        // The folded half is a facet of the same shared dictionary, held separately so the filter can
        // spend both exact attempts before reaching for it.
        this.foldedDictionary = unicodeFolding && lemmaDictionary instanceof FoldedLemmaLookup folded
            ? folded : null;
        this.nativePosTags = nativePosTags;
        this.modelFallback = modelFallback;
        this.keepOriginal = keepOriginal;
    }

    /**
     * Whether {@code value} requests the model's native tagset rather than Penn normalisation.
     * {@code null}/blank means the {@code penn} default.
     *
     * @throws IllegalArgumentException on any other value — an unrecognised {@code pos_format} used to
     *     fall through to {@code penn} silently, which degrades a native-tagged dictionary to 100 %
     *     model fallback (see {@link #POS_FORMAT_SETTING})
     */
    public static boolean isNativePosFormat(String filterName, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalised = value.toLowerCase(Locale.ROOT);
        if (NATIVE_POS_FORMATS.contains(normalised)) {
            return true;
        }
        if (PENN_POS_FORMATS.contains(normalised)) {
            return false;
        }
        throw new IllegalArgumentException("[" + filterName + "] unknown '" + POS_FORMAT_SETTING + "' value ["
            + value + "]; expected one of [penn, native, custom]. Use 'native' to keep a UD/UPOS model's"
            + " own tags — the dictionary's POS column must then match that tagset");
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
        return fromConfig(filterName, configDir, posModelFile, lemmatizerModelFile, false);
    }

    /**
     * As {@link #fromConfig(String, Path, String, String)}, additionally emitting the original token
     * beside each lemma (see {@link #KEEP_ORIGINAL_SETTING}).
     */
    public static OpenNlpLemmatizer fromConfig(String filterName, Path configDir, String posModelFile,
                                               String lemmatizerModelFile, boolean keepOriginal) {
        // validated here rather than below, where the message would offer a dictionary this filter has no
        // setting for
        if (isBlank(posModelFile) || isBlank(lemmatizerModelFile)) {
            throw new IllegalArgumentException("[" + filterName + "] token filter requires both '"
                + POS_MODEL_SETTING + "' and '" + LEMMATIZER_MODEL_SETTING + "' settings");
        }
        return fromConfig(filterName, configDir, posModelFile, lemmatizerModelFile, null, false, true,
            keepOriginal);
    }

    /**
     * As {@link #fromConfig(String, Path, String, String)} plus a {@code form<TAB>POS<TAB>lemma}
     * dictionary consulted before the model. Used by the {@code pos_dictionary_lemmatizer} factory.
     * {@code nativePosTags} preserves the POS model's own tagset (see {@link #POS_FORMAT_SETTING});
     * {@code modelFallback} keeps the MaxEnt model for dictionary misses (see
     * {@link #MODEL_FALLBACK_SETTING}) — with it off, {@code lemmatizerModelFile} may be blank.
     */
    public static OpenNlpLemmatizer fromConfig(String filterName, Path configDir, String posModelFile,
                                               String lemmatizerModelFile, String lemmatizerDictFile,
                                               boolean nativePosTags, boolean modelFallback) {
        return fromConfig(filterName, configDir, posModelFile, lemmatizerModelFile, lemmatizerDictFile,
            nativePosTags, modelFallback, false);
    }

    /**
     * As {@link #fromConfig(String, Path, String, String, String, boolean, boolean)}, additionally
     * emitting the original token beside each lemma (see {@link #KEEP_ORIGINAL_SETTING}).
     */
    public static OpenNlpLemmatizer fromConfig(String filterName, Path configDir, String posModelFile,
                                               String lemmatizerModelFile, String lemmatizerDictFile,
                                               boolean nativePosTags, boolean modelFallback,
                                               boolean keepOriginal) {
        return fromConfig(filterName, configDir, posModelFile, lemmatizerModelFile, lemmatizerDictFile,
            nativePosTags, modelFallback, keepOriginal, false);
    }

    /**
     * As {@link #fromConfig(String, Path, String, String, String, boolean, boolean, boolean)}, additionally
     * matching tokens that differ from a dictionary form only by folding (see
     * {@link #UNICODE_FOLDING_SETTING}).
     */
    public static OpenNlpLemmatizer fromConfig(String filterName, Path configDir, String posModelFile,
                                               String lemmatizerModelFile, String lemmatizerDictFile,
                                               boolean nativePosTags, boolean modelFallback,
                                               boolean keepOriginal, boolean unicodeFolding) {
        if (isBlank(posModelFile)) {
            throw new IllegalArgumentException(
                "[" + filterName + "] token filter requires a '" + POS_MODEL_SETTING + "' setting");
        }
        boolean pureDictionary = !isBlank(lemmatizerDictFile) && !modelFallback;
        if (isBlank(lemmatizerModelFile) && !pureDictionary) {
            throw new IllegalArgumentException("[" + filterName + "] token filter requires a '"
                + LEMMATIZER_MODEL_SETTING + "' setting (omit it only with a dictionary and '"
                + MODEL_FALLBACK_SETTING + ": false')");
        }
        Path dir = configDir.resolve(MODELS_DIRECTORY);
        Path dictPath = isBlank(lemmatizerDictFile) ? null : dir.resolve(lemmatizerDictFile);
        Path modelPath = isBlank(lemmatizerModelFile) ? null : dir.resolve(lemmatizerModelFile);
        return fromModels(dir.resolve(posModelFile), modelPath, dictPath, nativePosTags, modelFallback,
            keepOriginal, unicodeFolding);
    }

    /** Load directly from the two model file paths (no lemmatizer dictionary). */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath) {
        return fromModels(posModelPath, lemmatizerModelPath, null, false, true);
    }

    /** As {@link #fromModels(Path, Path)} with an optional {@code form<TAB>POS<TAB>lemma} dictionary. */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath, Path dictPath) {
        return fromModels(posModelPath, lemmatizerModelPath, dictPath, false, true);
    }

    /** As {@link #fromModels(Path, Path, Path)} choosing whether to keep the model's native tagset. */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath, Path dictPath,
                                               boolean nativePosTags) {
        return fromModels(posModelPath, lemmatizerModelPath, dictPath, nativePosTags, true);
    }

    /**
     * As {@link #fromModels(Path, Path, Path, boolean)} choosing whether dictionary misses fall back to
     * the MaxEnt model. With {@code modelFallback} off and a dictionary present, {@code lemmatizerModelPath}
     * may be {@code null} — no lemmatizer model is loaded at all.
     *
     * @throws IllegalArgumentException if there would be nothing to lemmatise with (no dictionary and no model)
     */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath, Path dictPath,
                                               boolean nativePosTags, boolean modelFallback) {
        return fromModels(posModelPath, lemmatizerModelPath, dictPath, nativePosTags, modelFallback, false);
    }

    /**
     * As {@link #fromModels(Path, Path, Path, boolean, boolean)}, additionally emitting the original token
     * beside each lemma (see {@link #KEEP_ORIGINAL_SETTING}).
     */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath, Path dictPath,
                                               boolean nativePosTags, boolean modelFallback,
                                               boolean keepOriginal) {
        return fromModels(posModelPath, lemmatizerModelPath, dictPath, nativePosTags, modelFallback,
            keepOriginal, false);
    }

    /**
     * As {@link #fromModels(Path, Path, Path, boolean, boolean, boolean)}, additionally matching tokens
     * that differ from a dictionary form only by folding (see {@link #UNICODE_FOLDING_SETTING}).
     */
    public static OpenNlpLemmatizer fromModels(Path posModelPath, Path lemmatizerModelPath, Path dictPath,
                                               boolean nativePosTags, boolean modelFallback,
                                               boolean keepOriginal, boolean unicodeFolding) {
        if (dictPath == null && (lemmatizerModelPath == null || !modelFallback)) {
            throw new IllegalArgumentException(
                "a lemmatizer model is required when there is no dictionary to fall back on");
        }
        boolean loadModel = lemmatizerModelPath != null && (modelFallback || dictPath == null);
        return new OpenNlpLemmatizer(
            ModelCache.loadShared(POS_MODEL_CACHE, posModelPath, p -> load(p, POSModel::new, "POS")),
            loadModel
                ? ModelCache.loadShared(LEMMA_MODEL_CACHE, lemmatizerModelPath, p -> load(p, LemmatizerModel::new, "lemmatizer"))
                : null,
            // the folding flag joins the cache key: it decides which automata are built, so a folding and
            // a non-folding index over one file must not be handed each other's dictionary
            dictPath == null ? null : ModelCache.loadShared(DICTIONARY_CACHE, dictPath,
                unicodeFolding ? "folded" : "", p -> FstPosDictionaryLemmatizer.fromFile(p, unicodeFolding)),
            unicodeFolding, nativePosTags, modelFallback, keepOriginal);
    }

    /** Wrap {@code input} with the OpenNLP POS tagger followed by the lemmatizer. */
    public TokenStream apply(TokenStream input) {
        // Lucene's NLPPOSTaggerOp hard-codes POSTagFormat.PENN; for a non-Penn model (e.g. UPOS+gender)
        // use a CUSTOM-format tagger so the dictionary sees the tags the model actually emits.
        var posOp = nativePosTags ? new NativeFormatPosTaggerOp(posModel) : new NLPPOSTaggerOp(posModel);
        TokenStream tagged = new OpenNLPPOSFilter(input, posOp);
        // The repeat must sit AFTER the tagger: doubling the stream ahead of it would make the tagger read
        // "v v Bratislave Bratislave …" and tag nonsense. Both lemmatizer filters below skip the
        // keyword-marked copy, so it survives as the surface form.
        TokenStream toLemmatize = keepOriginal ? new KeywordRepeatFilter(tagged) : tagged;
        return dedupe(lemmatize(toLemmatize));
    }

    private TokenStream lemmatize(TokenStream tagged) {
        if (lemmaDictionary != null) {
            // POS-aware: shared dictionary first (exact, then folded when on), MaxEnt model fallback
            // unless it was turned off
            return new OpenNlpPosLemmatizerFilter(tagged, lemmaDictionary, foldedDictionary,
                modelFallback ? lemmatizerModel : null);
        }
        NLPLemmatizerOp lemmaOp;
        try {
            lemmaOp = new NLPLemmatizerOp(null, lemmatizerModel); // null dictionary -> model-only
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize OpenNLP lemmatizer", e);
        }
        return new OpenNLPLemmatizerFilter(tagged, lemmaOp);
    }

    /** Collapse the repeated copy of a token the lemmatizer left unchanged, so only rewrites cost a posting. */
    private TokenStream dedupe(TokenStream lemmatized) {
        return keepOriginal ? new RemoveDuplicatesTokenFilter(lemmatized) : lemmatized;
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
