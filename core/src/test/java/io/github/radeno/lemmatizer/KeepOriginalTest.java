package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import org.junit.Test;

/**
 * {@code keep_original}: the surface form is indexed beside its lemma at the same position, so a
 * document still matches on what was actually written when the lemma is wrong. A token the filter left
 * alone must not be doubled — only a real rewrite may cost an extra posting.
 *
 * <p>Tokens are rendered as {@code term(+positionIncrement)}; a {@code +0} marks the stacked token.
 */
public class KeepOriginalTest {

    private static final String DIR = System.getProperty("opennlp.models.dir", "models");
    private static final Path SK_POS = Paths.get(DIR, "sk-pos.bin");
    private static final Path SK_LEMMAS = Paths.get(DIR, "sk-lemmas.bin");

    // --- flat dictionary_lemmatizer ---

    private static Path flatDictionary() throws Exception {
        Path dict = Files.createTempFile("dict", ".txt");
        Files.writeString(dict, "je\tbyť\nlese\tles\n");
        return dict;
    }

    @Test
    public void flatDictionaryKeepsTheSurfaceFormBesideTheLemma() throws Exception {
        var lemmatizer = DictionaryLemmatizer.fromFile(flatDictionary(), true);
        // "v" is not in the dictionary and "byť"/"les" are rewrites: only the rewrites are doubled
        assertEquals(List.of("je(+1)", "byť(+0)", "v(+1)", "lese(+1)", "les(+0)"),
            analyze(lemmatizer.apply(whitespace("je v lese"))));
    }

    @Test
    public void flatDictionaryIsUnchangedWhenTheSettingIsOff() throws Exception {
        var lemmatizer = DictionaryLemmatizer.fromFile(flatDictionary());
        assertEquals(List.of("byť(+1)", "v(+1)", "les(+1)"),
            analyze(lemmatizer.apply(whitespace("je v lese"))));
    }

    /** The stacked original must carry the same offsets as its lemma, or highlighting breaks. */
    @Test
    public void theStackedOriginalKeepsTheOffsetsOfItsLemma() throws Exception {
        var lemmatizer = DictionaryLemmatizer.fromFile(flatDictionary(), true);
        assertEquals(List.of("je[0,2]", "byť[0,2]", "v[3,4]", "lese[5,9]", "les[5,9]"),
            analyzeOffsets(lemmatizer.apply(whitespace("je v lese"))));
    }

    // --- POS-aware pos_dictionary_lemmatizer ---

    /** One-row dictionary; the {@code *} POS makes the lookup independent of the tag the model emits. */
    private static Path posDictionary() throws Exception {
        Path dict = Files.createTempFile("posdict", ".txt");
        Files.writeString(dict, "bratislave\t*\tBratislava\n");
        return dict;
    }

    @Test
    public void posDictionaryKeepsTheSurfaceFormBesideTheLemma() throws Exception {
        assumeSlovakModels();
        // model_fallback off, so only the dictionary hit is rewritten and the assertion stays crisp
        var lemmatizer = OpenNlpLemmatizer.fromModels(SK_POS, null, posDictionary(), false, false, true);
        assertEquals(List.of("v(+1)", "bratislave(+1)", "Bratislava(+0)"),
            analyze(lemmatizer.apply(whitespace("v bratislave"))));
    }

    @Test
    public void posDictionaryIsUnchangedWhenTheSettingIsOff() throws Exception {
        assumeSlovakModels();
        var lemmatizer = OpenNlpLemmatizer.fromModels(SK_POS, null, posDictionary(), false, false);
        assertEquals(List.of("v(+1)", "Bratislava(+1)"),
            analyze(lemmatizer.apply(whitespace("v bratislave"))));
    }

    /** The repeat sits after the POS tagger, so tagging still sees each token once and stays correct. */
    @Test
    public void posTaggingIsUnaffectedByTheRepeat() throws Exception {
        assumeSlovakModels();
        var plain = OpenNlpLemmatizer.fromModels(SK_POS, SK_LEMMAS, posDictionary());
        var kept = OpenNlpLemmatizer.fromModels(SK_POS, SK_LEMMAS, posDictionary(), false, true, true);
        String text = "Hostia prišli do Bratislavy";

        List<String> lemmasOnly = new ArrayList<>();
        for (String token : analyze(kept.apply(whitespace(text)))) {
            if (token.endsWith("(+0)")) {                      // the rewritten half of each pair
                lemmasOnly.add(token.substring(0, token.length() - 4));
            }
        }
        // every lemma the plain pipeline produces by rewriting a token must still appear, unchanged
        List<String> rewritten = new ArrayList<>();
        List<String> originals = List.of(text.split(" "));
        List<String> plainOut = analyze(plain.apply(whitespace(text)));
        for (int i = 0; i < plainOut.size(); i++) {
            String lemma = plainOut.get(i).substring(0, plainOut.get(i).length() - 4);
            if (!lemma.equals(originals.get(i))) {
                rewritten.add(lemma);
            }
        }
        assertEquals(rewritten, lemmasOnly);
    }

    // --- model-only opennlp_lemmatizer ---

    @Test
    public void theModelOnlyFilterKeepsTheSurfaceFormBesideTheLemma() throws Exception {
        assumeSlovakModels();
        var lemmatizer = OpenNlpLemmatizer.fromModels(SK_POS, SK_LEMMAS, null, false, true, true);
        // "že"/"si" are their own lemma -> emitted once; the two rewrites are doubled
        assertEquals(List.of("Ďakujem(+1)", "ďakovať(+0)", "že(+1)", "si(+1)", "prišiel(+1)", "prísť(+0)"),
            analyze(lemmatizer.apply(whitespace("Ďakujem že si prišiel"))));
    }

    @Test
    public void theModelOnlyFilterIsUnchangedWhenTheSettingIsOff() throws Exception {
        assumeSlovakModels();
        var lemmatizer = OpenNlpLemmatizer.fromModels(SK_POS, SK_LEMMAS);
        assertEquals(List.of("ďakovať(+1)", "že(+1)", "si(+1)", "prísť(+1)"),
            analyze(lemmatizer.apply(whitespace("Ďakujem že si prišiel"))));
    }

    // --- helpers ---

    private static void assumeSlovakModels() {
        assumeTrue("need sk-pos.bin + sk-lemmas.bin under " + DIR,
            Files.isReadable(SK_POS) && Files.isReadable(SK_LEMMAS));
    }

    private static Tokenizer whitespace(String text) {
        Tokenizer tokenizer = new WhitespaceTokenizer();
        tokenizer.setReader(new StringReader(text));
        return tokenizer;
    }

    private static List<String> analyze(TokenStream stream) throws Exception {
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        PositionIncrementAttribute posInc = stream.addAttribute(PositionIncrementAttribute.class);
        List<String> tokens = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            tokens.add(term.toString() + "(+" + posInc.getPositionIncrement() + ")");
        }
        stream.end();
        stream.close();
        return tokens;
    }

    private static List<String> analyzeOffsets(TokenStream stream) throws Exception {
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
        List<String> tokens = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            tokens.add(term.toString() + "[" + offset.startOffset() + "," + offset.endOffset() + "]");
        }
        stream.end();
        stream.close();
        return tokens;
    }
}
