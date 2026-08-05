package io.github.radeno.lemmatizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import opennlp.tools.lemmatizer.Lemmatizer;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.Util;

/**
 * POS-aware lemma dictionary backed by a Lucene FST (finite-state transducer), implementing OpenNLP's
 * {@link Lemmatizer} interface. The most compact backing store: the same ~926k-entry Slovak
 * MULTEXT-East dictionary that costs ~268&nbsp;MB in OpenNLP's {@code HashMap} fits in a single-digit-MB
 * automaton, because an FST collapses shared key prefixes and shared output suffixes across the whole
 * dictionary.
 *
 * <p>Keys are {@code form<TAB>POS} UTF-8 byte sequences, outputs are the lemma bytes. Lookups are
 * <b>case-sensitive</b> (the filter never folds case) — chain a {@code lowercase} filter ahead of this
 * one for case-insensitive matching, exactly like {@link DictionaryLemmatizer}. The stored lemma keeps
 * its original case, so a {@code lowercase}d {@code bratislave} still resolves to {@code Bratislava}.
 * A miss returns OpenNLP's {@code "O"} marker, signalling the caller to fall back to the MaxEnt model.
 *
 * <p>The FST requires its keys added in sorted (unsigned-byte) order. The build script emits the
 * dictionary already {@code LC_ALL=C}-sorted with lower-cased forms — i.e. in FST key order — so the
 * shared {@link FstBuilder} streams it straight into the automaton with no in-heap entry buffer (the
 * load-time memory peak otherwise scales with the whole dictionary); any file not in key order falls
 * back to buffer-then-sort.
 *
 * <p>The FST is immutable and shared across threads; {@link Util#get} allocates only a transient
 * reader per call and never mutates shared state, so concurrent lookups are safe.
 */
public final class FstPosDictionaryLemmatizer implements Lemmatizer, FoldedLemmaLookup {

    private static final String UNKNOWN = "O";  // OpenNLP's "not found" marker
    private static final String ANY_POS = "*";  // POS-relaxed key; see OpenNlpPosLemmatizerFilter

    private final FST<BytesRef> fst;
    private final int size;
    private final FST<BytesRef> foldedFst; // nullable; null -> unicode_folding off, or nothing folds
    private final int foldedSize;

    private FstPosDictionaryLemmatizer(FST<BytesRef> fst, int size, FST<BytesRef> foldedFst, int foldedSize) {
        this.fst = fst;
        this.size = size;
        this.foldedFst = foldedFst;
        this.foldedSize = foldedSize;
    }

    /** Load a {@code form<TAB>POS<TAB>lemma} dictionary file (UTF-8, one entry per line). */
    public static FstPosDictionaryLemmatizer fromFile(Path path) {
        return fromFile(path, false);
    }

    /**
     * As {@link #fromFile(Path)}, additionally building the folded companion automaton when
     * {@code unicodeFolding} is set (see {@link OpenNlpLemmatizer#UNICODE_FOLDING_SETTING}). That costs a
     * second pass over the file and roughly another key per foldable form, so it is off by default.
     */
    public static FstPosDictionaryLemmatizer fromFile(Path path, boolean unicodeFolding) {
        FstBuilder.Result exact = FstBuilder.build(path, FstPosDictionaryLemmatizer::parse);
        FstBuilder.Result folded = unicodeFolding
            ? FstBuilder.buildFolded(path, FstPosDictionaryLemmatizer::parseFolded)
            : new FstBuilder.Result(null, 0);
        return new FstPosDictionaryLemmatizer(exact.fst(), exact.size(), folded.fst(), folded.size());
    }

    /**
     * Parse one {@code form<TAB>POS<TAB>lemma} line into its FST key ({@code form<TAB>POS}, lower-cased
     * form) and lemma bytes, or {@code null} to skip (blank/short line).
     */
    private static FstBuilder.Entry parse(String raw) {
        var line = FstBuilder.stripBom(raw);
        int t1 = line.indexOf('\t');
        if (t1 <= 0) {
            return null;
        }
        int t2 = line.indexOf('\t', t1 + 1);
        if (t2 <= t1) {
            return null;
        }
        var form = line.substring(0, t1).strip().toLowerCase(Locale.ROOT);
        var pos = line.substring(t1 + 1, t2).strip();
        var lemma = line.substring(t2 + 1).strip();
        int extra = lemma.indexOf('\t'); // ignore any further columns
        if (extra >= 0) {
            lemma = lemma.substring(0, extra).strip();
        }
        if (form.isEmpty() || pos.isEmpty() || lemma.isEmpty()) {
            return null;
        }
        return new FstBuilder.Entry(
            (form + '\t' + pos).getBytes(StandardCharsets.UTF_8),
            lemma.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse one line into its <b>folded</b> keys: the POS-specific {@code fold(form)<TAB>POS} and the
     * POS-relaxed {@code fold(form)<TAB>*}. A form already equal to its folded shape yields neither — its
     * folded key would be byte-identical to the exact one, which the primary automaton holds and the
     * lookup tries first.
     *
     * <p>The relaxed key is emitted for <b>every</b> row, not only for rows whose own POS is {@code *},
     * so that {@link FstBuilder} can settle it by majority across the whole folded class. Emitting it only
     * where the source row said {@code *} would hand the key to whichever single form happened to carry
     * that marker, however rare a word it is.
     */
    private static List<FstBuilder.Entry> parseFolded(String raw) {
        FstBuilder.Entry exact = parse(raw);
        if (exact == null) {
            return List.of();
        }
        var key = new String(exact.key(), StandardCharsets.UTF_8);
        int tab = key.indexOf('\t');
        var form = key.substring(0, tab);
        var folded = UnicodeFolder.fold(form);
        if (folded.equals(form)) {
            return List.of();
        }
        var pos = key.substring(tab + 1);
        var relaxed = new FstBuilder.Entry(
            (folded + '\t' + ANY_POS).getBytes(StandardCharsets.UTF_8), exact.output());
        return pos.equals(ANY_POS)
            ? List.of(relaxed) // the POS-specific key would be the same bytes
            : List.of(new FstBuilder.Entry(
                (folded + '\t' + pos).getBytes(StandardCharsets.UTF_8), exact.output()), relaxed);
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
        return get(fst, word + '\t' + tag);
    }

    /**
     * Look up {@code (fold(word), POS)} in the folded automaton — the last dictionary attempt, made only
     * after both exact ones missed and only for a token the caller has cleared through
     * {@link UnicodeFolder#isFolded}. Returns {@code "O"} when folding is off or the key is absent.
     */
    @Override
    public String lemmatizeFolded(String word, String tag) {
        return foldedFst == null ? UNKNOWN : get(foldedFst, UnicodeFolder.fold(word) + '\t' + tag);
    }

    private static String get(FST<BytesRef> automaton, String key) {
        try {
            BytesRef out = Util.get(automaton, new BytesRef(key));
            return out == null ? UNKNOWN : out.utf8ToString();
        } catch (IOException e) {
            throw new UncheckedIOException("FST lookup failed", e);
        }
    }

    /** Number of {@code (form, POS) -> lemma} entries. */
    public int size() {
        return size;
    }

    /** Number of folded keys; {@code 0} when {@code unicode_folding} is off or nothing in the file folds. */
    public int foldedSize() {
        return foldedSize;
    }
}
