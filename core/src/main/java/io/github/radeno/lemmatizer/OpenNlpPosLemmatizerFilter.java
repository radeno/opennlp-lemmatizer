package io.github.radeno.lemmatizer;

import java.io.IOException;
import java.util.Map;

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

    // The dictionary may be keyed on any tagset the upstream POS model emits (Penn NN/VB… or a finer
    // UPOS+gender NOUN.Masc…). The MaxEnt lemmatizer model, however, was trained on the Penn tagset, so
    // before the model fallback we normalise a UPOS(.feature) tag to its Penn equivalent — otherwise the
    // model receives a tag it never saw and mangles the word. Penn tags pass through unchanged.
    private static final Map<String, String> UPOS_TO_PENN = Map.ofEntries(
        Map.entry("NOUN", "NN"), Map.entry("PROPN", "NN"), Map.entry("VERB", "VB"), Map.entry("AUX", "VB"),
        Map.entry("ADJ", "JJ"), Map.entry("ADV", "RB"), Map.entry("ADP", "IN"), Map.entry("CCONJ", "CC"),
        Map.entry("SCONJ", "IN"), Map.entry("NUM", "CD"), Map.entry("PRON", "PRP"), Map.entry("DET", "PRP"),
        Map.entry("PART", "RB"), Map.entry("INTJ", "UH"), Map.entry("X", "NN"), Map.entry("SYM", "NN"));

    private final Lemmatizer dictionary;
    private final LemmatizerME model;
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final TypeAttribute typeAttr = addAttribute(TypeAttribute.class);
    private final KeywordAttribute keywordAttr = addAttribute(KeywordAttribute.class);
    private final String[] word = new String[1];
    private final String[] tag = new String[1];
    private final String[] fallbackTag = new String[1];

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
            fallbackTag[0] = toPennTag(tag[0]);            // normalise tag for the Penn-trained model
            lemma = model.lemmatize(word, fallbackTag)[0]; // MaxEnt model fallback
        }
        if (!isBlank(lemma)) {
            termAttr.setEmpty().append(lemma);
        }
        return true;
    }

    private static boolean isBlank(String lemma) {
        return lemma == null || lemma.isEmpty() || UNKNOWN.equals(lemma) || "_".equals(lemma);
    }

    /** Map a UPOS(.feature) tag (e.g. {@code NOUN.Masc}) to its Penn equivalent; Penn tags pass through. */
    private static String toPennTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return tag;
        }
        int dot = tag.indexOf('.');
        String upos = dot >= 0 ? tag.substring(0, dot) : tag;
        return UPOS_TO_PENN.getOrDefault(upos, tag);
    }
}
