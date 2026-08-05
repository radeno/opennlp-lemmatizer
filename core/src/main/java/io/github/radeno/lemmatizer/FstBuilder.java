package io.github.radeno.lemmatizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FSTCompiler;
import org.apache.lucene.util.fst.Util;

/**
 * Builds a {@code byte[] key -> byte[] output} Lucene FST from a tab-separated dictionary file, shared by
 * the flat {@link DictionaryLemmatizer} ({@code form -> lemma}) and the POS-aware
 * {@link FstPosDictionaryLemmatizer} ({@code form<TAB>POS -> lemma}). The two differ only in how a line
 * is split into (key, output), which the caller supplies as a {@link LineParser}.
 *
 * <p>An FST requires its keys added in sorted unsigned-byte order. A file already in that order
 * {@linkplain #streamBuild streams} straight into the automaton with no in-heap entry buffer — the
 * load-time peak is the FST itself, not the whole dictionary. Any file that is not in key order
 * transparently falls back to {@linkplain #bufferedBuild buffer-then-sort}, which is correct but
 * allocates the full entry list. Duplicate keys keep the first occurrence in both paths.
 */
final class FstBuilder {

    // JDK logger: no new dependency, and a node that installs a System.Logger backend (OpenSearch and
    // Elasticsearch both run log4j2) routes it there. The plugin bundles slf4j-api without a binding, so
    // logging through slf4j from core would silently no-op instead.
    private static final System.Logger LOG = System.getLogger(FstBuilder.class.getName());

    private FstBuilder() {
    }

    /** One {@code key -> output} entry as raw UTF-8 bytes, ready for unsigned-byte sorting and FST building. */
    record Entry(byte[] key, byte[] output) {
    }

    /** Splits one raw line into an {@link Entry}, or returns {@code null} to skip it (blank/malformed). */
    @FunctionalInterface
    interface LineParser {
        Entry parse(String rawLine);
    }

    /**
     * Splits one raw line into any number of {@link Entry entries} — used by {@link #buildFolded}, where a
     * single dictionary row contributes both a POS-specific folded key and a POS-relaxed one.
     */
    @FunctionalInterface
    interface MultiLineParser {
        List<Entry> parse(String rawLine);
    }

    /** A compiled FST and the number of entries added. */
    record Result(FST<BytesRef> fst, int size) {
    }

    /** Signals {@link #streamBuild} that the file is not in unsigned-byte key order (triggers fallback). */
    static final class UnsortedException extends Exception {
        UnsortedException() {
            super(null, null, false, false); // no message/cause/stacktrace: control flow, not a failure
        }
    }

    /** Strip a leading UTF-8 BOM if present, so the first field of the first line parses cleanly. */
    static String stripBom(String raw) {
        return (!raw.isEmpty() && raw.charAt(0) == '﻿') ? raw.substring(1) : raw;
    }

    /**
     * Build from {@code path}, streaming when it is already in key order and buffering otherwise, and log
     * what was loaded — entry count, which path was taken, elapsed time and FST size.
     *
     * @throws IllegalArgumentException if no line parsed, which means the file is not the expected
     *     tab-separated format. Lucene's compiler returns a {@code null} FST for an empty input, so this
     *     would otherwise surface as a {@code NullPointerException} on the first token analysed rather
     *     than when the index is created.
     */
    static Result build(Path path, LineParser parser) {
        long start = System.nanoTime();
        boolean streamed = true;
        Result result;
        try {
            result = streamBuild(path, parser);
        } catch (UnsortedException unsorted) {
            streamed = false;
            result = bufferedBuild(path, parser);
        }
        if (result.size() == 0) {
            throw new IllegalArgumentException("No entries parsed from " + path
                + "; expected tab-separated lines (form<TAB>lemma or form<TAB>POS<TAB>lemma)");
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        Result loaded = result;
        boolean viaStream = streamed;
        LOG.log(System.Logger.Level.INFO,
            () -> String.format("Loaded %d entries from %s in %d ms (%s, FST %.1f MB)",
                loaded.size(), path, ms,
                viaStream ? "streamed" : "buffered: file not in key order",
                loaded.fst().ramBytesUsed() / (1024.0 * 1024.0)));
        return loaded;
    }

    /**
     * Build the folded companion automaton for {@code unicode_folding} — same file, but each line parsed
     * into its {@linkplain UnicodeFolder folded} key. Always buffered: folding is not order-preserving, so
     * a file sorted on its original keys is never sorted on the folded ones.
     *
     * <p>{@code parser} is expected to skip lines whose form already equals its folded shape — those keys
     * are identical to the exact ones, which the primary automaton holds and the lookup consults first.
     * A dictionary where nothing folds (say a plain-ASCII lexicon) legitimately yields no entries at all,
     * so unlike {@link #build} this returns an empty {@link Result} rather than failing; the caller then
     * simply has no folded automaton to consult.
     */
    static Result buildFolded(Path path, MultiLineParser parser) {
        long start = System.nanoTime();
        Result result = foldedBuild(path, parser);
        long ms = (System.nanoTime() - start) / 1_000_000;
        LOG.log(System.Logger.Level.INFO,
            () -> result.size() == 0
                ? String.format("No foldable forms in %s; '%s' has no effect on this dictionary",
                    path, OpenNlpLemmatizer.UNICODE_FOLDING_SETTING)
                : String.format("Loaded %d folded keys from %s in %d ms (FST %.1f MB)",
                    result.size(), path, ms, result.fst().ramBytesUsed() / (1024.0 * 1024.0)));
        return result;
    }

    /**
     * Stream an already-sorted file into the FST without buffering entries; only the previous key is kept
     * in memory. Throws {@link UnsortedException} on the first out-of-order key so {@link #build} can fall
     * back to {@link #bufferedBuild}.
     */
    static Result streamBuild(Path path, LineParser parser) throws UnsortedException {
        var scratch = new IntsRefBuilder();
        byte[] prevKey = null;
        int added = 0;
        try {
            var compiler = newCompiler();
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String raw;
                while ((raw = reader.readLine()) != null) {
                    Entry e = parser.parse(raw);
                    if (e == null) {
                        continue;
                    }
                    if (prevKey != null) {
                        int cmp = Arrays.compareUnsigned(prevKey, e.key());
                        if (cmp == 0) {
                            continue;                      // duplicate key -> first wins
                        }
                        if (cmp > 0) {
                            throw new UnsortedException();  // not in key order -> fall back
                        }
                    }
                    compiler.add(Util.toIntsRef(new BytesRef(e.key()), scratch), new BytesRef(e.output()));
                    prevKey = e.key();
                    added++;
                }
            }
            return compile(compiler, added);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build FST from " + path, e);
        }
    }

    /**
     * Buffer, sort, and compile the folded entries, resolving a key claimed by several source forms to the
     * lemma <b>most of them</b> agree on rather than to whichever happened to come first.
     *
     * <p>The distinction matters at the POS-relaxed key. In the exact dictionary a {@code *} row is an
     * assertion — this form has one lemma whatever its part of speech. Its folded counterpart asserts
     * nothing of the kind: it is merely where every form sharing a folded shape lands, and those forms are
     * different words. Counting them turns the key into what it should be, the reading the folded class as
     * a whole supports. On the Slovak lexicon that is worth about a point of accuracy, and it is the
     * difference between {@code uz -> už} and {@code uz -> úžiť} (one {@code úž} row outvoted by two
     * {@code už} ones).
     *
     * <p>The count needs no map: equal keys are adjacent once sorted, so each run is tallied in place.
     * Ties keep the first entry in file order, the sort being stable.
     */
    private static Result foldedBuild(Path path, MultiLineParser parser) {
        List<Entry> entries = new ArrayList<>(1 << 20);
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(raw -> entries.addAll(parser.parse(raw)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load dictionary from " + path, e);
        }
        entries.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));

        var scratch = new IntsRefBuilder();
        int added = 0;
        try {
            var compiler = newCompiler();
            for (int i = 0; i < entries.size(); ) {
                int end = i + 1;
                while (end < entries.size() && Arrays.equals(entries.get(i).key(), entries.get(end).key())) {
                    end++;
                }
                byte[] winner = majorityOutput(entries, i, end);
                compiler.add(Util.toIntsRef(new BytesRef(entries.get(i).key()), scratch), new BytesRef(winner));
                added++;
                i = end;
            }
            return compile(compiler, added);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build FST from " + path, e);
        }
    }

    /**
     * The most frequent output in {@code entries[from, to)}, ties going to the earliest. Runs are all but
     * always one or two entries long, so the quadratic scan is cheaper than the map it replaces.
     */
    private static byte[] majorityOutput(List<Entry> entries, int from, int to) {
        if (to - from == 1) {
            return entries.get(from).output();
        }
        byte[] best = entries.get(from).output();
        int bestCount = 0;
        for (int i = from; i < to; i++) {
            byte[] candidate = entries.get(i).output();
            int count = 0;
            for (int j = from; j < to; j++) {
                if (Arrays.equals(candidate, entries.get(j).output())) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }

    /** Buffer the whole file, sort by unsigned key bytes, then build the FST. Correct for any order. */
    static Result bufferedBuild(Path path, LineParser parser) {
        List<Entry> entries = new ArrayList<>(1 << 20);
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(raw -> {
                Entry e = parser.parse(raw);
                if (e != null) {
                    entries.add(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load dictionary from " + path, e);
        }
        entries.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key())); // stable: equal keys keep file order

        var scratch = new IntsRefBuilder();
        byte[] prevKey = null;
        int added = 0;
        try {
            var compiler = newCompiler();
            for (Entry e : entries) {
                if (prevKey != null && Arrays.equals(prevKey, e.key())) {
                    continue; // first-wins on duplicate key
                }
                compiler.add(Util.toIntsRef(new BytesRef(e.key()), scratch), new BytesRef(e.output()));
                prevKey = e.key();
                added++;
            }
            return compile(compiler, added);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build FST from " + path, e);
        }
    }

    private static FSTCompiler<BytesRef> newCompiler() throws IOException {
        return new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE1, ByteSequenceOutputs.getSingleton()).build();
    }

    private static Result compile(FSTCompiler<BytesRef> compiler, int added) throws IOException {
        if (added == 0) {
            // Lucene's compiler has no automaton to hand back for an empty input; report the emptiness
            // rather than a null-bearing FST, so callers decide (build fails, buildFolded carries on).
            return new Result(null, 0);
        }
        FST.FSTMetadata<BytesRef> meta = compiler.compile();
        return new Result(FST.fromFSTReader(meta, compiler.getFSTReader()), added);
    }
}
