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
 * Golden output of {@code pos_dictionary_lemmatizer} over the real Slovak MULTEXT-East dictionary, so a
 * change in the fallback order, in the POS-relaxed retry or in the case-only guard cannot slip through
 * unnoticed. Needs {@code models/sk-pos.bin}, {@code models/sk-lemmas.bin} and {@code models/sk-mte-pos.txt};
 * self-skips without them.
 *
 * <p>The expected strings are what the filter produced before the guard existed (for the recommended
 * chain) and after it (for the bare chain) — the split is the point of the test.
 */
public class PosDictionaryLemmatizerGoldenTest {

    private static final String DIR = System.getProperty("opennlp.models.dir", "models");
    private static final Path POS = Paths.get(DIR, "sk-pos.bin");
    private static final Path LEMMAS = Paths.get(DIR, "sk-lemmas.bin");
    private static final Path DICT = Paths.get(DIR, "sk-mte-pos.txt");

    /**
     * The recommended chain, {@code lowercase} -> {@code pos_dictionary_lemmatizer}. Every expected value
     * predates the case-only guard: behind {@code lowercase} the guard can never fire, so this output must
     * stay byte-identical.
     */
    private static final String[][] WITH_LOWERCASE = {
        { "Hostia prišli do Bratislavy a navštívili starý hrad",
          "hosť prísť do Bratislava a navštíviť starý hrad" },
        { "Dunaj tečie cez Bratislavu smerom na juh",
          "Dunaj tiecť cez Bratislava smer na juh" },
        { "Peter s rodinou navštívil Košice Tatry a Poprad",
          "Peter s rodina navštíviť Košice tatra a Poprad" },
        { "Tri ženy niesli tri jablká",
          "tri žena niesť tri jablko" },
        // `lowercase` folds identifiers before the filter sees them, so the guard cannot protect them and
        // the model still mangles the e-mail. Chain `keyword_marker` when that matters.
        { "Objednávka SKU-4711 bola odoslaná na user@example.com",
          "objednávka sku-4711 byť odoslaný na user@example.cí" },
    };

    /**
     * The bare chain, no {@code lowercase}. Here every capitalised token misses the case-sensitive
     * dictionary and reaches the model, which would lower-case all of them; the guard keeps the ones the
     * model did not actually lemmatise. {@code Bratislavu} is not one of them — the model rewrites its
     * letters, so its (wrong) lemma still wins.
     */
    private static final String[][] WITHOUT_LOWERCASE = {
        { "Firma NATO USA a iPhone sú známe značky",
          "Firma NATO USA a iPhone byť známy značka" },
        { "Objednávka SKU-4711 bola odoslaná na user@example.com",
          "Objednávka SKU-4711 byť odoslaný na user@example.cí" },
        { "Dunaj tečie cez Bratislavu smerom na juh",
          "Dunaj tiecť cez bratisla smer na juh" },
    };

    private static void assumeSlovakModelsAndDictionary() {
        assumeTrue("need sk-pos.bin + sk-lemmas.bin + sk-mte-pos.txt under " + DIR,
            Files.isReadable(POS) && Files.isReadable(LEMMAS) && Files.isReadable(DICT));
    }

    private static String analyze(OpenNlpLemmatizer lemmatizer, String text, boolean lowercase)
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
        return String.join(" ", tokens);
    }

    @Test
    public void theRecommendedLowercaseChainIsUnchanged() throws Exception {
        assumeSlovakModelsAndDictionary();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, DICT);
        for (String[] pair : WITH_LOWERCASE) {
            assertEquals(pair[0], pair[1], analyze(lemmatizer, pair[0], true));
        }
    }

    @Test
    public void theBareChainKeepsTheCaseTheModelWouldHaveFolded() throws Exception {
        assumeSlovakModelsAndDictionary();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, DICT);
        for (String[] pair : WITHOUT_LOWERCASE) {
            assertEquals(pair[0], pair[1], analyze(lemmatizer, pair[0], false));
        }
    }

    /**
     * With the model off, an uncovered token is never guessed — the pure-dictionary contract. The e-mail
     * survives intact, and so does the cost of the trade: {@code odoslaná} keeps its surface form, because
     * its {@code odoslaný} lemma came from the model rather than the dictionary.
     */
    @Test
    public void pureDictionaryModeNeverInventsALemma() throws Exception {
        assumeSlovakModelsAndDictionary();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, null, DICT, false, false);
        assertEquals("objednávka sku-4711 byť odoslaná na user@example.com",
            analyze(lemmatizer, "Objednávka SKU-4711 bola odoslaná na user@example.com", true));
    }
}
