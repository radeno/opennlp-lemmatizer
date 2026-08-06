package io.github.radeno.lemmatizer;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;

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

    private final DictionaryFsts dictionary;
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAttr = addAttribute(KeywordAttribute.class);
    private final BytesRefBuilder keyScratch = new BytesRefBuilder();

    DictionaryLemmatizerFilter(TokenStream input, DictionaryFsts dictionary) {
        super(input);
        this.dictionary = dictionary;
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
        BytesRef lemma = dictionary.lookup(keyScratch.get());
        // Folded lookup only after the exact one missed, and only for a token already in folded shape —
        // UnicodeFolder#foldedKey applies that guard and yields the key in one fold. Costs a String per
        // miss, which is why it stays behind the setting.
        if (lemma == null && dictionary.folded() != null) {
            String foldedKey = UnicodeFolder.foldedKey(termAttr.toString());
            if (foldedKey != null) {
                keyScratch.copyChars(foldedKey);
                lemma = dictionary.lookupFolded(keyScratch.get());
            }
        }
        if (lemma != null) {
            termAttr.setEmpty().append(lemma.utf8ToString());
        }
        return true;
    }
}
