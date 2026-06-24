package io.github.radeno.lemmatizer;

import java.io.IOException;

import opennlp.tools.lemmatizer.Lemmatizer;
import opennlp.tools.lemmatizer.LemmatizerME;
import opennlp.tools.lemmatizer.LemmatizerModel;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

/**
 * POS-aware lemmatization: a shared {@link Lemmatizer} dictionary ({@code form<TAB>POS<TAB>lemma},
 * e.g. from MULTEXT-East) is consulted first, falling back to the per-stream MaxEnt
 * {@link LemmatizerME} when the {@code (word, POS)} pair is absent. The POS tag is read from the
 * {@link TypeAttribute}, set upstream by the OpenNLP POS filter.
 *
 * <p>The dictionary is immutable and shared across threads (it is parsed once); only the lightweight
 * {@code LemmatizerME} wrapper is per-stream — same cost as the model-only path. This avoids the
 * per-thread dictionary copy that {@code NLPLemmatizerOp} would make. The concrete backing store is
 * chosen by the caller (e.g. {@link FstPosDictionaryLemmatizer}).
 */
final class OpenNlpPosLemmatizerFilter extends TokenFilter {

    private static final String UNKNOWN = "O"; // OpenNLP's "not found" marker

    private final Lemmatizer dictionary;
    private final LemmatizerME model;
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final TypeAttribute typeAttr = addAttribute(TypeAttribute.class);
    private final KeywordAttribute keywordAttr = addAttribute(KeywordAttribute.class);
    private final String[] word = new String[1];
    private final String[] tag = new String[1];

    OpenNlpPosLemmatizerFilter(TokenStream input, Lemmatizer dictionary, LemmatizerModel model) {
        super(input);
        this.dictionary = dictionary;
        this.model = new LemmatizerME(model);
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!input.incrementToken()) {
            return false;
        }
        if (keywordAttr.isKeyword()) {
            return true;
        }
        word[0] = termAttr.toString();
        tag[0] = typeAttr.type();
        String lemma = dictionary.lemmatize(word, tag)[0]; // shared dictionary first
        if (isBlank(lemma)) {
            lemma = model.lemmatize(word, tag)[0];         // MaxEnt model fallback
        }
        if (!isBlank(lemma)) {
            termAttr.setEmpty().append(lemma);
        }
        return true;
    }

    private static boolean isBlank(String lemma) {
        return lemma == null || lemma.isEmpty() || UNKNOWN.equals(lemma) || "_".equals(lemma);
    }
}
