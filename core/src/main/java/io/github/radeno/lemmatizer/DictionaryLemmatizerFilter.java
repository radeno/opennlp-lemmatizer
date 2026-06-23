package io.github.radeno.lemmatizer;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

/**
 * Replaces each token with its dictionary lemma (lower-cased form lookup), leaving unknown tokens
 * and tokens marked {@link KeywordAttribute keyword} untouched. Package-private; created via
 * {@link DictionaryLemmatizer#apply(TokenStream)}.
 */
final class DictionaryLemmatizerFilter extends TokenFilter {

    private final Map<String, String> formToLemma;
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAttr = addAttribute(KeywordAttribute.class);

    DictionaryLemmatizerFilter(TokenStream input, Map<String, String> formToLemma) {
        super(input);
        this.formToLemma = formToLemma;
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!input.incrementToken()) {
            return false;
        }
        if (keywordAttr.isKeyword()) {
            return true;
        }
        var lemma = formToLemma.get(termAttr.toString().toLowerCase(Locale.ROOT));
        if (lemma != null) {
            termAttr.setEmpty().append(lemma);
        }
        return true;
    }
}
