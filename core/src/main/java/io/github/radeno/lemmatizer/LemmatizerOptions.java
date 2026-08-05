package io.github.radeno.lemmatizer;

/**
 * The optional behaviour of the lemmatizer filters, as one named value rather than a run of positional
 * booleans.
 *
 * <p>Every setting here is a flag, and flags passed positionally stop being readable at the second one.
 * Worse, the two dictionary classes had grown <em>different orders</em> for the same flags — this
 * compiled, and no test could catch it, because both values are valid:
 *
 * <pre>
 *   DictionaryLemmatizer.fromConfig(name, dir, file,             keepOriginal, unicodeFolding)
 *   OpenNlpLemmatizer   .fromConfig(name, dir, pos, model, dict,
 *                                   nativePosTags, modelFallback, keepOriginal, unicodeFolding)
 * </pre>
 *
 * Named instead, and the call says what it does:
 *
 * <pre>{@code
 * OpenNlpLemmatizer.fromModels(pos, model, dict,
 *     LemmatizerOptions.defaults().modelFallback(false).unicodeFolding(true));
 * }</pre>
 *
 * <p>It also gives each new setting one place to be added instead of a fresh overload per call path.
 *
 * <p>{@link #nativePosTags} and {@link #modelFallback} concern the POS-aware filters only; the flat,
 * POS-free {@code dictionary_lemmatizer} reads {@link #keepOriginal} and {@link #unicodeFolding} and
 * ignores the rest.
 *
 * @param nativePosTags  keep the POS model's own tagset instead of normalising it to Penn
 *                       ({@link OpenNlpLemmatizer#POS_FORMAT_SETTING})
 * @param modelFallback  let the MaxEnt model answer where the dictionary has nothing
 *                       ({@link OpenNlpLemmatizer#MODEL_FALLBACK_SETTING})
 * @param keepOriginal   emit the surface form beside the lemma
 *                       ({@link OpenNlpLemmatizer#KEEP_ORIGINAL_SETTING})
 * @param unicodeFolding also match tokens that differ from a dictionary form only by folding
 *                       ({@link OpenNlpLemmatizer#UNICODE_FOLDING_SETTING})
 */
public record LemmatizerOptions(boolean nativePosTags, boolean modelFallback, boolean keepOriginal,
                                boolean unicodeFolding) {

    private static final LemmatizerOptions DEFAULTS = new LemmatizerOptions(false, true, false, false);

    /**
     * Reads a boolean token-filter setting. Both OpenSearch's and Elasticsearch's {@code Settings} carry a
     * matching {@code getAsBoolean(String, Boolean)}, so a wrapper passes {@code settings::getAsBoolean}
     * and the platform keeps doing its own parsing — a malformed value still fails there, instead of
     * being quietly read as {@code false} by a parser of ours.
     */
    @FunctionalInterface
    public interface BooleanSettings {
        boolean read(String name, boolean fallback);
    }

    /** Stock behaviour: Penn tags, model fallback on, no surface form, no folding. */
    public static LemmatizerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Read every flag from a platform's settings, so all six token-filter factories share one definition
     * of what the filters accept instead of hand-written copies that can drift apart.
     *
     * <p>{@code pos_format} is not a flag, so its parsed result arrives already resolved (via
     * {@link OpenNlpLemmatizer#isNativePosFormat}) — that call throws on an unknown value, and the error
     * belongs at the wrapper where the raw string was read.
     */
    public static LemmatizerOptions from(boolean nativePosTags, BooleanSettings settings) {
        return new LemmatizerOptions(
            nativePosTags,
            settings.read(OpenNlpLemmatizer.MODEL_FALLBACK_SETTING, DEFAULTS.modelFallback()),
            settings.read(OpenNlpLemmatizer.KEEP_ORIGINAL_SETTING, DEFAULTS.keepOriginal()),
            settings.read(OpenNlpLemmatizer.UNICODE_FOLDING_SETTING, DEFAULTS.unicodeFolding()));
    }

    public LemmatizerOptions nativePosTags(boolean value) {
        return new LemmatizerOptions(value, modelFallback, keepOriginal, unicodeFolding);
    }

    public LemmatizerOptions modelFallback(boolean value) {
        return new LemmatizerOptions(nativePosTags, value, keepOriginal, unicodeFolding);
    }

    public LemmatizerOptions keepOriginal(boolean value) {
        return new LemmatizerOptions(nativePosTags, modelFallback, value, unicodeFolding);
    }

    public LemmatizerOptions unicodeFolding(boolean value) {
        return new LemmatizerOptions(nativePosTags, modelFallback, keepOriginal, value);
    }

    /**
     * Cache discriminator for artifacts whose content — not merely whose reading — depends on these
     * options; see {@link ModelCache}. Only folding qualifies: it decides which automata get built.
     */
    String dictionaryVariant() {
        return unicodeFolding ? "folded" : "";
    }
}
