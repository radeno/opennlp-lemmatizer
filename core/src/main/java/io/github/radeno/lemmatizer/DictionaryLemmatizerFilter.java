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
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAttr = addAttribute(KeywordAttribute.class);
    private final BytesRefBuilder keyScratch = new BytesRefBuilder();

    DictionaryLemmatizerFilter(TokenStream input, FST<BytesRef> fst) {
        super(input);
        this.fst = fst;
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
        BytesRef lemma;
        try {
            lemma = Util.get(fst, keyScratch.get());
        } catch (IOException e) {
            throw new UncheckedIOException("FST lookup failed", e);
        }
        if (lemma != null) {
            termAttr.setEmpty().append(lemma.utf8ToString());
        }
        return true;
    }
}
