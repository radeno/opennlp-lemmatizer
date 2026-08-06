package io.github.radeno.lemmatizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.Util;

/**
 * A loaded dictionary: the exact automaton, and — when {@code unicode_folding} is on and the file has
 * anything to fold — its folded companion.
 *
 * <p>Both {@link DictionaryLemmatizer} and {@link FstPosDictionaryLemmatizer} held this same quadruple
 * and each wrapped {@link Util#get} in the same {@code IOException} handler. They differ only in how a
 * key is spelled — a bare form against {@code form<TAB>POS} — which is why the key arrives already
 * built rather than being assembled here.
 *
 * <p>Immutable and shared across threads and, via {@link ModelCache}, across every index on the node:
 * {@code Util.get} allocates a transient reader per call and never mutates the automaton.
 *
 * @param exact      the automaton over the dictionary's own keys
 * @param size       entries in {@link #exact}
 * @param folded     automaton over folded keys, or {@code null} when folding is off or nothing folds
 * @param foldedSize entries in {@link #folded}, {@code 0} when there is none
 */
record DictionaryFsts(FST<BytesRef> exact, int size, FST<BytesRef> folded, int foldedSize) {

    /**
     * Read {@code path} into the exact automaton and, when {@code unicodeFolding} is set, the folded one
     * as well — a second pass over the file, since folding does not preserve key order and so cannot ride
     * along with the streaming build.
     */
    static DictionaryFsts load(Path path, FstBuilder.LineParser parser,
                               FstBuilder.MultiLineParser foldedParser, boolean unicodeFolding) {
        FstBuilder.Result exact = FstBuilder.build(path, parser);
        FstBuilder.Result folded = unicodeFolding
            ? FstBuilder.buildFolded(path, foldedParser)
            : new FstBuilder.Result(null, 0);
        return new DictionaryFsts(exact.fst(), exact.size(), folded.fst(), folded.size());
    }

    /** The output for {@code key} in the exact automaton, or {@code null} when absent. */
    BytesRef lookup(BytesRef key) {
        return get(exact, key);
    }

    /**
     * The output for an already-folded {@code key}, or {@code null} when absent or when there is no
     * folded automaton. Callers must have cleared the token through {@link UnicodeFolder#isFolded} and
     * must have spent every exact attempt first — see {@link FoldedLemmaLookup}.
     */
    BytesRef lookupFolded(BytesRef key) {
        return folded == null ? null : get(folded, key);
    }

    private static BytesRef get(FST<BytesRef> automaton, BytesRef key) {
        try {
            return Util.get(automaton, key);
        } catch (IOException e) {
            throw new UncheckedIOException("FST lookup failed", e);
        }
    }
}
