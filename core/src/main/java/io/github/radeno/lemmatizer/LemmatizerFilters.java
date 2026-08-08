package io.github.radeno.lemmatizer;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * Builds each of the three token filters from a node's settings — the one place that knows which
 * settings a filter reads and how it validates them.
 *
 * <p>OpenSearch and Elasticsearch each need their own {@code TokenFilterFactory} subclass, so the
 * plugin carries six wrapper classes. Before this, all six repeated the settings reading, and the ES
 * and OpenSearch copies of each were 39–47 lines that differed in exactly one: the {@code super(...)}
 * call their base class requires. Every new setting therefore had to be added in four places, by hand,
 * with nothing to catch a copy left behind. Now each wrapper is its constructor, that {@code super},
 * and one call to a method here.
 *
 * <p>The settings themselves arrive as two accessors rather than a platform type, since {@code core}
 * must not depend on either platform. A wrapper passes {@code settings::get} and
 * {@code settings::getAsBoolean}, which keeps parsing and validation on the platform where a malformed
 * value still produces the platform's own error.
 */
public final class LemmatizerFilters {

    private LemmatizerFilters() {
    }

    /**
     * {@code opennlp_lemmatizer} — POS tagger plus MaxEnt lemmatizer model, no dictionary.
     *
     * <p>{@code pos_format} applies here too, even with no dictionary to key. The setting swaps the
     * tagger itself — Lucene's {@code NLPPOSTaggerOp} coerces every tag to Penn, {@code native} keeps
     * the model's own tagset — and those tags are what the MaxEnt lemmatizer receives as the POS half
     * of each {@code (word, POS)} pair, as well as what lands in the token's {@code type}. A UD/UPOS
     * model pair therefore needs {@code native} on this filter exactly as it does on the POS-aware
     * dictionary one. It used to be read only by {@link #posDictionary}, so on this filter it sat
     * there doing nothing — and an unknown value was not even rejected.
     *
     * @param name      the token-filter name, used in validation messages
     * @param configDir the node's config directory; models are read from {@code <configDir>/opennlp/}
     * @param get       reads a string setting, e.g. {@code settings::get}
     * @param flags     reads a boolean setting, e.g. {@code settings::getAsBoolean}
     */
    public static LemmatizerFilter opennlp(String name, Path configDir, UnaryOperator<String> get,
                                           LemmatizerOptions.BooleanSettings flags) {
        return OpenNlpLemmatizer.fromConfig(
            name,
            configDir,
            get.apply(OpenNlpLemmatizer.POS_MODEL_SETTING),
            get.apply(OpenNlpLemmatizer.LEMMATIZER_MODEL_SETTING),
            LemmatizerOptions.from(
                OpenNlpLemmatizer.isNativePosFormat(name, get.apply(OpenNlpLemmatizer.POS_FORMAT_SETTING)),
                flags));
    }

    /**
     * {@code pos_dictionary_lemmatizer} — POS-aware {@code form/POS/lemma} dictionary first, MaxEnt
     * model for the gaps.
     *
     * @throws IllegalArgumentException if the {@code dictionary} setting is missing or blank
     */
    public static LemmatizerFilter posDictionary(String name, Path configDir, UnaryOperator<String> get,
                                                 LemmatizerOptions.BooleanSettings flags) {
        String dictionary = get.apply(DictionaryLemmatizer.DICTIONARY_SETTING);
        if (dictionary == null || dictionary.isBlank()) {
            throw new IllegalArgumentException("[" + name + "] pos_dictionary_lemmatizer requires a '"
                + DictionaryLemmatizer.DICTIONARY_SETTING + "' setting (a form<TAB>POS<TAB>lemma file)");
        }
        return OpenNlpLemmatizer.fromConfig(
            name,
            configDir,
            get.apply(OpenNlpLemmatizer.POS_MODEL_SETTING),
            get.apply(OpenNlpLemmatizer.LEMMATIZER_MODEL_SETTING),
            dictionary,
            LemmatizerOptions.from(
                OpenNlpLemmatizer.isNativePosFormat(name, get.apply(OpenNlpLemmatizer.POS_FORMAT_SETTING)),
                flags));
    }

    /** {@code dictionary_lemmatizer} — flat {@code form -> lemma} lookup, no part of speech. */
    public static LemmatizerFilter dictionary(String name, Path configDir, UnaryOperator<String> get,
                                              LemmatizerOptions.BooleanSettings flags) {
        return DictionaryLemmatizer.fromConfig(
            name,
            configDir,
            get.apply(DictionaryLemmatizer.DICTIONARY_SETTING),
            LemmatizerOptions.from(false, flags));
    }
}
