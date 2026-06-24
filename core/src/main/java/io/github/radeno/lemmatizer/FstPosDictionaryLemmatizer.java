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

import opennlp.tools.lemmatizer.Lemmatizer;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FSTCompiler;
import org.apache.lucene.util.fst.Util;

/**
 * POS-aware lemma dictionary backed by a Lucene FST (finite-state transducer), implementing OpenNLP's
 * {@link Lemmatizer} interface. The most compact backing store: the same ~926k-entry Slovak
 * MULTEXT-East dictionary that costs ~268&nbsp;MB in OpenNLP's {@code HashMap} and ~63&nbsp;MB in the
 * {@link PosDictionaryLemmatizer} {@code CharArrayMap} fits in a single-digit-MB automaton, because an
 * FST collapses shared key prefixes and shared output suffixes across the whole dictionary.
 *
 * <p>Keys are {@code form<TAB>POS} UTF-8 byte sequences, outputs are the lemma bytes. Lookups are
 * <b>case-sensitive</b> (the filter never folds case) — chain a {@code lowercase} filter ahead of this
 * one for case-insensitive matching, exactly like {@link DictionaryLemmatizer}. The stored lemma keeps
 * its original case, so a {@code lowercase}d {@code bratislave} still resolves to {@code Bratislava}.
 * A miss returns OpenNLP's {@code "O"} marker, signalling the caller to fall back to the MaxEnt model.
 *
 * <p>The FST is immutable and shared across threads; {@link Util#get} allocates only a transient
 * reader per call and never mutates shared state, so concurrent lookups are safe.
 */
public final class FstPosDictionaryLemmatizer implements Lemmatizer {

    private static final String UNKNOWN = "O"; // OpenNLP's "not found" marker

    private final FST<BytesRef> fst;
    private final int size;

    private FstPosDictionaryLemmatizer(FST<BytesRef> fst, int size) {
        this.fst = fst;
        this.size = size;
    }

    /** One {@code form<TAB>POS -> lemma} entry, kept as raw UTF-8 bytes for sorting and FST building. */
    private record Entry(byte[] key, byte[] lemma) {}

    /** Load a {@code form<TAB>POS<TAB>lemma} dictionary file (UTF-8, one entry per line). */
    public static FstPosDictionaryLemmatizer fromFile(Path path) {
        // The FST requires inputs added in sorted (unsigned) byte order; collect, sort, then add.
        List<Entry> entries = new ArrayList<>(1 << 20);
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(raw -> {
                var line = (!raw.isEmpty() && raw.charAt(0) == '﻿') ? raw.substring(1) : raw; // strip BOM
                int t1 = line.indexOf('\t');
                if (t1 <= 0) {
                    return;
                }
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 <= t1) {
                    return;
                }
                var form = line.substring(0, t1).strip().toLowerCase(Locale.ROOT);
                var pos = line.substring(t1 + 1, t2).strip();
                var lemma = line.substring(t2 + 1).strip();
                int extra = lemma.indexOf('\t'); // ignore any further columns
                if (extra >= 0) {
                    lemma = lemma.substring(0, extra).strip();
                }
                if (form.isEmpty() || pos.isEmpty() || lemma.isEmpty()) {
                    return;
                }
                entries.add(new Entry(
                    (form + '\t' + pos).getBytes(StandardCharsets.UTF_8),
                    lemma.getBytes(StandardCharsets.UTF_8)));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load POS dictionary from " + path, e);
        }
        entries.sort((a, b) -> Arrays.compareUnsigned(a.key, b.key));

        try {
            var outputs = ByteSequenceOutputs.getSingleton();
            var compiler = new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE1, outputs).build();
            var scratch = new IntsRefBuilder();
            byte[] prevKey = null;
            int added = 0;
            for (Entry e : entries) {
                if (prevKey != null && Arrays.equals(prevKey, e.key)) {
                    continue; // first-wins on duplicate (form, POS)
                }
                var keyRef = new BytesRef(e.key);
                compiler.add(Util.toIntsRef(keyRef, scratch), new BytesRef(e.lemma));
                prevKey = e.key;
                added++;
            }
            FST.FSTMetadata<BytesRef> meta = compiler.compile();
            FST<BytesRef> fst = FST.fromFSTReader(meta, compiler.getFSTReader());
            return new FstPosDictionaryLemmatizer(fst, added);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build FST from " + path, e);
        }
    }

    @Override
    public String[] lemmatize(String[] toks, String[] tags) {
        var lemmas = new String[toks.length];
        for (int i = 0; i < toks.length; i++) {
            lemmas[i] = lemmatize(toks[i], tags[i]);
        }
        return lemmas;
    }

    @Override
    public List<List<String>> lemmatize(List<String> toks, List<String> tags) {
        var lemmas = new ArrayList<List<String>>(toks.size());
        for (int i = 0; i < toks.size(); i++) {
            lemmas.add(List.of(lemmatize(toks.get(i), tags.get(i))));
        }
        return lemmas;
    }

    /** Look up one {@code (word, POS)} pair (case-sensitive); returns the lemma or {@code "O"} when absent. */
    private String lemmatize(String word, String tag) {
        var key = new BytesRef(word + '\t' + tag);
        try {
            BytesRef out = Util.get(fst, key);
            return out == null ? UNKNOWN : out.utf8ToString();
        } catch (IOException e) {
            throw new UncheckedIOException("FST lookup failed", e);
        }
    }

    /** Number of {@code (form, POS) -> lemma} entries. */
    public int size() {
        return size;
    }
}
