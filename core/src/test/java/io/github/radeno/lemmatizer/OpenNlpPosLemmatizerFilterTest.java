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
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import org.junit.Test;

/**
 * How the POS-aware dictionary filter treats what the dictionary does not cover: the case-only guard on
 * the MaxEnt fallback, and the pure-dictionary mode ({@code model_fallback: false}).
 * Needs {@code models/sk-pos.bin} + {@code models/sk-lemmas.bin}; self-skips without them.
 */
public class OpenNlpPosLemmatizerFilterTest {

    private static final String DIR = System.getProperty("opennlp.models.dir", "models");
    private static final Path POS = Paths.get(DIR, "sk-pos.bin");
    private static final Path LEMMAS = Paths.get(DIR, "sk-lemmas.bin");
    private static final Path CS_POS = Paths.get(DIR, "cs-pos.bin");
    private static final Path CS_LEMMAS = Paths.get(DIR, "cs-lemmas.bin");

    private static void assumeSlovakModels() {
        assumeTrue("need sk-pos.bin + sk-lemmas.bin under " + DIR,
            Files.isReadable(POS) && Files.isReadable(LEMMAS));
    }

    /** One-row dictionary; the {@code *} POS makes the lookup independent of the tag the model emits. */
    private static Path dictionary() throws Exception {
        Path dict = Files.createTempFile("posdict", ".txt");
        Files.writeString(dict, "bratislave\t*\tBratislava\n");
        return dict;
    }

    private static List<String> analyze(OpenNlpLemmatizer lemmatizer, String text, boolean lowercase)
            throws Exception {
        Tokenizer source = new WhitespaceTokenizer();
        source.setReader(new StringReader(text));
        TokenStream stream = lemmatizer.apply(lowercase ? new LowerCaseFilter(source) : source);
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        List<String> tokens = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            tokens.add(term.toString());
        }
        stream.end();
        stream.close();
        return tokens;
    }

    // --- case-only guard on the model fallback ---

    /**
     * {@code LemmatizerME} lower-cases every token before applying its edit script, so a token it cannot
     * lemmatise comes back as the lower-cased original. Emitting that would destroy the case of
     * identifiers and unknown proper nouns without lemmatising anything.
     */
    @Test
    public void theModelMayNotFoldCaseWithoutLemmatising() throws Exception {
        assumeSlovakModels();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, dictionary());
        assertEquals(List.of("NATO", "SKU-4711", "iPhone", "Smith"),
            analyze(lemmatizer, "NATO SKU-4711 iPhone Smith", false));
    }

    /** The guard must not swallow a real lemma: here the model changes more than case. */
    @Test
    public void theGuardStillLetsTheModelLemmatise() throws Exception {
        assumeSlovakModels();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, dictionary());
        assertEquals(List.of("hosť", "prísť"), analyze(lemmatizer, "Hostia prišli", false));
    }

    /**
     * Behind a {@code lowercase} filter the token is already folded, so a lemma differing from it in case
     * alone cannot exist — the guard is provably inert and the recommended pipeline is untouched.
     */
    @Test
    public void theGuardIsInertBehindALowercaseFilter() throws Exception {
        assumeSlovakModels();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, dictionary());
        assertEquals(List.of("nato", "smith"), analyze(lemmatizer, "NATO Smith", true));
    }

    @Test
    public void theDictionaryStillWinsAndKeepsItsLemmaCase() throws Exception {
        assumeSlovakModels();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, dictionary());
        assertEquals(List.of("Bratislava"), analyze(lemmatizer, "Bratislave", true));
    }

    // --- model_fallback: false -> pure dictionary ---

    @Test
    public void pureDictionaryModeLeavesUncoveredTokensUntouched() throws Exception {
        assumeSlovakModels();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, dictionary(), LemmatizerOptions.defaults().modelFallback(false));
        // "Hostia"/"prišli" would be guessed by the model; only the dictionary entry is rewritten
        assertEquals(List.of("Hostia", "prišli", "Bratislava"),
            analyze(lemmatizer, "Hostia prišli bratislave", false));
    }

    /** With the fallback off there is nothing to fall back on, so no lemmatizer model is loaded at all. */
    @Test
    public void pureDictionaryModeNeedsNoLemmatizerModel() throws Exception {
        assumeSlovakModels();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, null, dictionary(), LemmatizerOptions.defaults().modelFallback(false));
        assertEquals(List.of("prišli", "Bratislava"), analyze(lemmatizer, "prišli bratislave", false));
    }

    /** Neither rule is Slovak-specific; the Czech models must behave the same way. */
    @Test
    public void bothRulesHoldForCzechToo() throws Exception {
        assumeTrue("need cs-pos.bin + cs-lemmas.bin under " + DIR,
            Files.isReadable(CS_POS) && Files.isReadable(CS_LEMMAS));
        Path dict = Files.createTempFile("csposdict", ".txt");
        Files.writeString(dict, "praze\t*\tPraha\n");

        assertEquals(List.of("NATO", "SKU-4711", "Praha"),
            analyze(OpenNlpLemmatizer.fromModels(CS_POS, CS_LEMMAS, dict), "NATO SKU-4711 praze", false));
        assertEquals(List.of("Děkuji", "Praha"),
            analyze(OpenNlpLemmatizer.fromModels(CS_POS, null, dict, LemmatizerOptions.defaults().modelFallback(false)), "Děkuji praze", false));
    }
}
