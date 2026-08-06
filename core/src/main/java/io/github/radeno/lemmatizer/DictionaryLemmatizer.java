package io.github.radeno.lemmatizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.KeywordRepeatFilter;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
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
public final class DictionaryLemmatizer implements LemmatizerFilter {

    /** Token-filter setting naming the dictionary file (in {@code <config>/opennlp/}). */
    public static final String DICTIONARY_SETTING = "dictionary";

    /**
     * The heavy, immutable half — what the node-wide cache shares. Per-filter settings live on the
     * enclosing instance instead, so two filters reading the same file with different settings still
     * share one automaton.
     */
    record Dictionary(FST<BytesRef> fst, int size, FST<BytesRef> foldedFst, int foldedSize) {
    }

    // Node-wide dedup cache (see ModelCache): one FST per file, shared across every index on the node.
    private static final ConcurrentHashMap<String, ModelCache.Cached<Dictionary>> CACHE =
        new ConcurrentHashMap<>();

    private final Dictionary dictionary;
    private final boolean keepOriginal;

    private DictionaryLemmatizer(Dictionary dictionary, boolean keepOriginal) {
        this.dictionary = dictionary;
        this.keepOriginal = keepOriginal;
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
        return fromConfig(filterName, configDir, dictionaryFile, LemmatizerOptions.defaults());
    }

    /**
     * As {@link #fromConfig(String, Path, String)} with explicit {@link LemmatizerOptions}. Being
     * POS-free, this filter reads only {@link LemmatizerOptions#keepOriginal()} and
     * {@link LemmatizerOptions#unicodeFolding()}.
     */
    public static DictionaryLemmatizer fromConfig(String filterName, Path configDir, String dictionaryFile,
                                                  LemmatizerOptions options) {
        if (dictionaryFile == null || dictionaryFile.isBlank()) {
            throw new IllegalArgumentException(
                "[" + filterName + "] token filter requires a '" + DICTIONARY_SETTING + "' setting");
        }
        Path path = configDir.resolve(OpenNlpLemmatizer.MODELS_DIRECTORY).resolve(dictionaryFile);
        // The folding flag joins the cache key: it changes which automata get built, so a folding and a
        // non-folding index reading the same file need separate entries rather than one racing the other.
        var cached = ModelCache.loadShared(CACHE, path, options.dictionaryVariant(),
            p -> load(p, options.unicodeFolding()));
        return new DictionaryLemmatizer(cached, options.keepOriginal());
    }

    /** Load a flat {@code form<TAB>lemma} dictionary file (UTF-8, one pair per line) into an FST. */
    public static DictionaryLemmatizer fromFile(Path path) {
        return fromFile(path, LemmatizerOptions.defaults());
    }

    /** As {@link #fromFile(Path)} with explicit {@link LemmatizerOptions}. */
    public static DictionaryLemmatizer fromFile(Path path, LemmatizerOptions options) {
        return new DictionaryLemmatizer(load(path, options.unicodeFolding()), options.keepOriginal());
    }

    private static Dictionary load(Path path, boolean unicodeFolding) {
        FstBuilder.Result exact = FstBuilder.build(path, DictionaryLemmatizer::parse);
        FstBuilder.Result folded = unicodeFolding
            ? FstBuilder.buildFolded(path, DictionaryLemmatizer::parseFolded)
            : new FstBuilder.Result(null, 0);
        return new Dictionary(exact.fst(), exact.size(), folded.fst(), folded.size());
    }

    /** The shared automaton behind this filter; lets a test assert two filters really share one copy. */
    Dictionary dictionary() {
        return dictionary;
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

    /**
     * Parse one line into its <b>folded</b> key, or {@code null} to skip. A form already equal to its
     * folded shape is skipped: that key is byte-identical to the exact one, which the primary automaton
     * holds and the filter tries first.
     */
    private static List<FstBuilder.Entry> parseFolded(String raw) {
        FstBuilder.Entry exact = parse(raw);
        if (exact == null) {
            return List.of();
        }
        var form = new String(exact.key(), StandardCharsets.UTF_8);
        var folded = UnicodeFolder.fold(form);
        return folded.equals(form)
            ? List.of()
            : List.of(
                new FstBuilder.Entry(folded.getBytes(StandardCharsets.UTF_8), exact.output()));
    }

    /** Number of {@code form -> lemma} entries. */
    public int size() {
        return dictionary.size();
    }

    /** Number of folded keys; {@code 0} when {@code unicode_folding} is off or nothing in the file folds. */
    public int foldedSize() {
        return dictionary.foldedSize();
    }

    @Override
    public TokenStream apply(TokenStream input) {
        if (!keepOriginal) {
            return new DictionaryLemmatizerFilter(input, dictionary.fst(), dictionary.foldedFst());
        }
        // Repeat each token, the first copy keyword-marked: the filter skips it, so the surface form
        // survives beside its lemma. RemoveDuplicates then collapses the pair whenever the lemma equals
        // the original, leaving the extra posting only where a token was really rewritten.
        var lemmatized = new DictionaryLemmatizerFilter(
            new KeywordRepeatFilter(input), dictionary.fst(), dictionary.foldedFst());
        return new RemoveDuplicatesTokenFilter(lemmatized);
    }
}
