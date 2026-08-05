package io.github.radeno.lemmatizer;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.Util;

/**
 * Replaces each token with its FST dictionary lemma by exact (case-sensitive) {@code form → lemma}
 * lookup, leaving unknown and {@link KeywordAttribute keyword} tokens untouched. Keys are lower-cased,
 * so chain a {@code lowercase} filter before this one. Package-private; created via
 * {@link DictionaryLemmatizer#apply(TokenStream)}.
 *
 * <p>The term is encoded into a reused {@link BytesRefBuilder} (no per-token key {@code String}); the
 * remaining lookup cost is the FST walk and decoding the lemma bytes.
 */
final class DictionaryLemmatizerFilter extends TokenFilter {

    private final FST<BytesRef> fst;
    private final FST<BytesRef> foldedFst; // nullable; null -> unicode_folding off, or nothing folds
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAttr = addAttribute(KeywordAttribute.class);
    private final BytesRefBuilder keyScratch = new BytesRefBuilder();

    DictionaryLemmatizerFilter(TokenStream input, FST<BytesRef> fst) {
        this(input, fst, null);
    }

    DictionaryLemmatizerFilter(TokenStream input, FST<BytesRef> fst, FST<BytesRef> foldedFst) {
        super(input);
        this.fst = fst;
        this.foldedFst = foldedFst;
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!input.incrementToken()) {
            return false;
        }
        if (keywordAttr.isKeyword()) {
            return true;
        }
        keyScratch.copyChars(termAttr.buffer(), 0, termAttr.length()); // UTF-16 -> UTF-8 into reused buffer
        BytesRef lemma = get(fst, keyScratch.get());
        // Folded lookup only after the exact one missed, and only for a token already in folded shape —
        // see UnicodeFolder#isFolded. Costs a String per miss, which is why it stays behind the setting.
        if (lemma == null && foldedFst != null) {
            String term = termAttr.toString();
            if (UnicodeFolder.isFolded(term)) {
                keyScratch.copyChars(UnicodeFolder.fold(term));
                lemma = get(foldedFst, keyScratch.get());
            }
        }
        if (lemma != null) {
            termAttr.setEmpty().append(lemma.utf8ToString());
        }
        return true;
    }

    private static BytesRef get(FST<BytesRef> automaton, BytesRef key) {
        try {
            return Util.get(automaton, key);
        } catch (IOException e) {
            throw new UncheckedIOException("FST lookup failed", e);
        }
    }
}
