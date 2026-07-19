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

    /** Build from {@code path}, streaming when it is already in key order and buffering otherwise. */
    static Result build(Path path, LineParser parser) {
        try {
            return streamBuild(path, parser);
        } catch (UnsortedException unsorted) {
            return bufferedBuild(path, parser);
        }
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
        FST.FSTMetadata<BytesRef> meta = compiler.compile();
        return new Result(FST.fromFSTReader(meta, compiler.getFSTReader()), added);
    }
}
