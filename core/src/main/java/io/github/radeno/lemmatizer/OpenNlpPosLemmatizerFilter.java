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
 *
 * <p>A {@code null} model turns the fallback off, making this a pure dictionary filter: a
 * {@code (word, POS)} pair the dictionary does not cover leaves its token untouched.
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
    private final FoldedLemmaLookup folded; // nullable; null -> unicode_folding off
    private final LemmatizerME model;       // nullable; null -> pure-dictionary mode (no model fallback)
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final TypeAttribute typeAttr = addAttribute(TypeAttribute.class);
    private final KeywordAttribute keywordAttr = addAttribute(KeywordAttribute.class);
    private final String[] word = new String[1];
    private final String[] tag = new String[1];
    private final String[] fallbackTag = new String[1];
    // Sentinel POS for the POS-relaxed lookup: a dictionary may carry a `form<TAB>*<TAB>lemma` row for
    // every form that has a single lemma regardless of part of speech. When the POS tagger mis-tags such
    // a form the exact `(form, POS)` lookup misses, so we retry under `*` before the model — recovering
    // e.g. `saunu → sauna` when the tagger wrongly calls it a verb. Ambiguous forms have no `*` row.
    private static final String ANY_POS = "*";
    private final String[] anyTag = { ANY_POS };

    /** {@code model} may be {@code null} to disable the MaxEnt fallback (pure-dictionary mode). */
    OpenNlpPosLemmatizerFilter(TokenStream input, Lemmatizer dictionary, LemmatizerModel model) {
        this(input, dictionary, null, model);
    }

    /**
     * As above, with {@code folded} supplying the {@code unicode_folding} attempt; {@code null} leaves the
     * lookup order exactly as it was before the setting existed.
     */
    OpenNlpPosLemmatizerFilter(TokenStream input, Lemmatizer dictionary, FoldedLemmaLookup folded,
                               LemmatizerModel model) {
        super(input);
        this.dictionary = dictionary;
        this.folded = folded;
        this.model = model == null ? null : new LemmatizerME(model);
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
        String lemma = dictionary.lemmatize(word, tag)[0];     // exact (form, POS) first
        if (isBlank(lemma)) {
            lemma = dictionary.lemmatize(word, anyTag)[0];     // POS-relaxed (single-lemma forms)
        }
        // Only now, both exact attempts spent, may the folded automaton speak — and only for a token
        // already written in folded shape. A richly written token that missed the dictionary is an
        // unknown word, not a fold away from a known one, and guessing there would outrank the model.
        if (isBlank(lemma) && folded != null && UnicodeFolder.isFolded(word[0])) {
            lemma = folded.lemmatizeFolded(word[0], tag[0]);
            if (isBlank(lemma)) {
                lemma = folded.lemmatizeFolded(word[0], ANY_POS);
            }
        }
        if (isBlank(lemma)) {
            if (model == null) {
                return true;                                   // pure-dictionary mode: leave the token as-is
            }
            fallbackTag[0] = toPennTag(tag[0]);                // normalise tag for the Penn-trained model
            lemma = model.lemmatize(word, fallbackTag)[0];     // MaxEnt model fallback
            if (foldsCaseOnly(word[0], lemma)) {
                return true;                                   // model only folded case: keep the token
            }
        }
        if (!isBlank(lemma)) {
            termAttr.setEmpty().append(lemma);
        }
        return true;
    }

    private static boolean isBlank(String lemma) {
        return lemma == null || lemma.isEmpty() || UNKNOWN.equals(lemma) || "_".equals(lemma);
    }

    /**
     * Whether the model merely folded {@code word}'s case instead of lemmatising it. {@link LemmatizerME}
     * lower-cases every token before applying its edit script, so a token it cannot lemmatise comes back
     * as the lower-cased original ({@code SKU-4711 -> sku-4711}, {@code NATO -> nato}). Emitting that
     * destroys the case of identifiers and of proper nouns the model does not know without lemmatising
     * anything, so the original token is kept instead.
     *
     * <p>{@code word} must itself be foldable for this to fire, which pins down the direction of the
     * change: a model trained on capitalised lemmas may legitimately answer {@code haus -> Haus}, and that
     * lemma is kept. It also makes the guard inert behind a {@code lowercase} filter, whose
     * {@code CharacterUtils.toLowerCase} leaves no foldable code point behind.
     */
    static boolean foldsCaseOnly(String word, String lemma) {
        return lemma != null
            && !lemma.equals(word)
            && lemma.equalsIgnoreCase(word) // same letters, so the edit script changed nothing
            && isFoldable(word);            // ...and it was the word that lost its case, not the lemma
    }

    /** Whether lower-casing {@code word} code point by code point would change it — Lucene's own rule. */
    private static boolean isFoldable(String word) {
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            if (Character.toLowerCase(cp) != cp) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
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
