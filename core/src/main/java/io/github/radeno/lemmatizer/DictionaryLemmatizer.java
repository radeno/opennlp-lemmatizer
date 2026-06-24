package io.github.radeno.lemmatizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenStream;

/**
 * Fast, POS-free lemmatization by flat dictionary lookup.
 *
 * <p>Loads a tab-separated {@code form<TAB>lemma} dictionary (e.g. fetched from MULTEXT-East,
 * CC BY-SA) once into memory and replaces each token with its lemma by exact lookup, leaving unknown
 * tokens unchanged. Dictionary keys are lower-cased, so chain a {@code lowercase} filter BEFORE this
 * one for case-insensitive matching. There is no part of speech, so homonyms resolve to a single
 * baked-in lemma — but it is ~1000x faster than the POS-aware OpenNLP path.
 *
 * <p>The loaded map is immutable and shared across threads; the per-stream filter only reads it.
 */
public final class DictionaryLemmatizer {

    /** Token-filter setting naming the dictionary file (in {@code <config>/opennlp/}). */
    public static final String DICTIONARY_SETTING = "dictionary";

    private final CharArrayMap<String> formToLemma;

    private DictionaryLemmatizer(CharArrayMap<String> formToLemma) {
        this.formToLemma = formToLemma;
    }

    /**
     * Load the dictionary from {@code <configDir>/opennlp/<dictionaryFile>}.
     *
     * @param filterName token-filter name, used only in the validation error message
     * @throws IllegalArgumentException if {@code dictionaryFile} is missing/blank
     * @throws UncheckedIOException     if the dictionary cannot be read
     */
    public static DictionaryLemmatizer fromConfig(String filterName, Path configDir, String dictionaryFile) {
        if (dictionaryFile == null || dictionaryFile.isBlank()) {
            throw new IllegalArgumentException(
                "[" + filterName + "] token filter requires a '" + DICTIONARY_SETTING + "' setting");
        }
        return fromFile(configDir.resolve(OpenNlpLemmatizer.MODELS_DIRECTORY).resolve(dictionaryFile));
    }

    /** Load a {@code form<TAB>lemma} dictionary file (UTF-8, one pair per line). */
    public static DictionaryLemmatizer fromFile(Path path) {
        // CharArrayMap: keys are looked up directly off the token's char[] buffer with no per-token
        // String allocation (the hot path). ignoreCase=false — keys are lower-cased at load, so chain
        // a `lowercase` filter upstream for case-insensitive matching.
        var map = new CharArrayMap<String>(1 << 19, false);
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(raw -> {
                var line = (!raw.isEmpty() && raw.charAt(0) == '﻿') ? raw.substring(1) : raw; // strip BOM
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    return;
                }
                var form = line.substring(0, tab).strip().toLowerCase(Locale.ROOT);
                var lemma = line.substring(tab + 1).strip();
                int extra = lemma.indexOf('\t'); // ignore any further columns
                if (extra >= 0) {
                    lemma = lemma.substring(0, extra).strip();
                }
                if (!form.isEmpty() && !lemma.isEmpty() && !map.containsKey(form)) {
                    map.put(form, lemma);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load dictionary from " + path, e);
        }
        return new DictionaryLemmatizer(CharArrayMap.unmodifiableMap(map));
    }

    /** Number of {@code form -> lemma} entries. */
    public int size() {
        return formToLemma.size();
    }

    public TokenStream apply(TokenStream input) {
        return new DictionaryLemmatizerFilter(input, formToLemma);
    }
}
