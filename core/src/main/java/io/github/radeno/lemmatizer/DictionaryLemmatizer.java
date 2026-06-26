package io.github.radeno.lemmatizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FSTCompiler;
import org.apache.lucene.util.fst.Util;

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
 * end-to-end throughput matches a {@code CharArrayMap} while using a fraction of the memory.
 *
 * <p>Dictionary keys are lower-cased, so chain a {@code lowercase} filter BEFORE this one for
 * case-insensitive matching. The loaded FST is immutable and shared across threads (and, via
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

    private record Entry(byte[] key, byte[] lemma) {}

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
        List<Entry> entries = new ArrayList<>(1 << 20);
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
                if (!form.isEmpty() && !lemma.isEmpty()) {
                    entries.add(new Entry(
                        form.getBytes(StandardCharsets.UTF_8), lemma.getBytes(StandardCharsets.UTF_8)));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load dictionary from " + path, e);
        }
        entries.sort((a, b) -> Arrays.compareUnsigned(a.key, b.key)); // FST needs sorted (unsigned) keys

        try {
            var outputs = ByteSequenceOutputs.getSingleton();
            var compiler = new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE1, outputs).build();
            var scratch = new IntsRefBuilder();
            byte[] prevKey = null;
            int added = 0;
            for (Entry e : entries) {
                if (prevKey != null && Arrays.equals(prevKey, e.key)) {
                    continue; // first-wins on duplicate form
                }
                compiler.add(Util.toIntsRef(new BytesRef(e.key), scratch), new BytesRef(e.lemma));
                prevKey = e.key;
                added++;
            }
            FST<BytesRef> fst = FST.fromFSTReader(compiler.compile(), compiler.getFSTReader());
            return new DictionaryLemmatizer(fst, added);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build FST from " + path, e);
        }
    }

    /** Number of {@code form -> lemma} entries. */
    public int size() {
        return size;
    }

    public TokenStream apply(TokenStream input) {
        return new DictionaryLemmatizerFilter(input, fst);
    }
}
