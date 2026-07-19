package io.github.radeno.lemmatizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.FST;

/**
 * Fast, POS-free lemmatization by flat {@code form → lemma} dictionary lookup, backed by a Lucene FST.
 *
 * <p>Loads a tab-separated {@code form<TAB>lemma} dictionary (e.g. fetched from MULTEXT-East, CC BY-SA)
 * once and replaces each token with its lemma by exact lookup, leaving unknown tokens unchanged. A form
 * with several lemmas is resolved to a single baked-in lemma (the first seen) — there is no part of
 * speech, so genuine homonyms can't be split (use {@code pos_dictionary_lemmatizer} for that).
 *
 * <p>The FST is ~50–100× more compact than a hash map for this data (it shares key prefixes and lemma
 * suffixes across the whole dictionary): a few MB instead of ~100&nbsp;MB for the Slovak lexicon. The
 * only cost is a small per-token allocation on lookup, which is masked by the analysis pipeline — so
 * end-to-end throughput stays high while using a fraction of the memory.
 *
 * <p>Dictionary keys are lower-cased, so chain a {@code lowercase} filter BEFORE this one for
 * case-insensitive matching. It shares the {@link FstBuilder}, so an already-sorted file streams into the
 * automaton with no in-heap buffer. The loaded FST is immutable and shared across threads (and, via
 * {@link ModelCache}, across every index on the node).
 */
public final class DictionaryLemmatizer {

    /** Token-filter setting naming the dictionary file (in {@code <config>/opennlp/}). */
    public static final String DICTIONARY_SETTING = "dictionary";

    // Node-wide dedup cache (see ModelCache): one FST per file, shared across every index on the node.
    private static final ConcurrentHashMap<String, ModelCache.Cached<DictionaryLemmatizer>> CACHE =
        new ConcurrentHashMap<>();

    private final FST<BytesRef> fst;
    private final int size;

    private DictionaryLemmatizer(FST<BytesRef> fst, int size) {
        this.fst = fst;
        this.size = size;
    }

    /**
     * Load the dictionary from {@code <configDir>/opennlp/<dictionaryFile>}, sharing one FST per file
     * node-wide.
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
        Path path = configDir.resolve(OpenNlpLemmatizer.MODELS_DIRECTORY).resolve(dictionaryFile);
        return ModelCache.loadShared(CACHE, path, DictionaryLemmatizer::fromFile);
    }

    /** Load a flat {@code form<TAB>lemma} dictionary file (UTF-8, one pair per line) into an FST. */
    public static DictionaryLemmatizer fromFile(Path path) {
        FstBuilder.Result r = FstBuilder.build(path, DictionaryLemmatizer::parse);
        return new DictionaryLemmatizer(r.fst(), r.size());
    }

    /** Parse one {@code form<TAB>lemma} line into its FST key ({@code form}, lower-cased) and lemma bytes. */
    private static FstBuilder.Entry parse(String raw) {
        var line = FstBuilder.stripBom(raw);
        int tab = line.indexOf('\t');
        if (tab <= 0) {
            return null;
        }
        var form = line.substring(0, tab).strip().toLowerCase(Locale.ROOT);
        var lemma = line.substring(tab + 1).strip();
        int extra = lemma.indexOf('\t'); // ignore any further columns
        if (extra >= 0) {
            lemma = lemma.substring(0, extra).strip();
        }
        if (form.isEmpty() || lemma.isEmpty()) {
            return null;
        }
        return new FstBuilder.Entry(
            form.getBytes(StandardCharsets.UTF_8), lemma.getBytes(StandardCharsets.UTF_8));
    }

    /** Number of {@code form -> lemma} entries. */
    public int size() {
        return size;
    }

    public TokenStream apply(TokenStream input) {
        return new DictionaryLemmatizerFilter(input, fst);
    }
}
