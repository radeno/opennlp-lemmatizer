package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
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
 * {@code unicode_folding}: a token written without the diacritics (or ligatures, or compatibility forms)
 * its dictionary entry carries still finds that entry, through a second automaton keyed on folded forms.
 *
 * <p>Three properties matter and are asserted separately, because each protects a different thing:
 * the folded automaton must <b>find</b> what exact lookup cannot; it must <b>not fire</b> for a token
 * already written in rich form, which is what keeps correctly spelled text on the path it takes today;
 * and it must work on <b>non-Latin</b> dictionaries, which is why the folding is UTR#30 rather than ASCII.
 */
public class UnicodeFoldingTest {

    private static final String DIR = System.getProperty("opennlp.models.dir", "models");
    private static final Path SK_POS = Paths.get(DIR, "sk-pos.bin");
    private static final Path SK_LEMMAS = Paths.get(DIR, "sk-lemmas.bin");
    private static final Path SK_DICT = Paths.get(DIR, "sk-mte-pos.txt");

    private static Tokenizer whitespace(String text) throws Exception {
        Tokenizer source = new WhitespaceTokenizer();
        source.setReader(new StringReader(text));
        return source;
    }

    private static List<String> analyze(TokenStream stream) throws Exception {
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

    private static Path dictionary(String contents) throws Exception {
        Path dict = Files.createTempFile("folding", ".txt");
        Files.writeString(dict, contents);
        return dict;
    }

    // --- the folding function itself ---

    @Test
    public void foldingCoversMoreThanDiacritics() {
        assertEquals("ruzomberok", UnicodeFolder.fold("Ružomberok"));
        assertEquals("αθηνα", UnicodeFolder.fold("Αθήνα"));
        assertEquals("петр", UnicodeFolder.fold("Пётр"));
        assertEquals("finance", UnicodeFolder.fold("ﬁnance"));
        assertEquals("strasse", UnicodeFolder.fold("straße"));
        assertEquals("xii", UnicodeFolder.fold("Ⅻ"));
    }

    /**
     * The guard: a plain token passes (case alone must not disqualify it, or the setting would need a
     * {@code lowercase} filter to work at all), a richly written one does not.
     */
    @Test
    public void onlyPlainlyWrittenTokensClearTheGuard() {
        assertTrue(UnicodeFolder.isFolded("ruzomberok"));
        assertTrue(UnicodeFolder.isFolded("Ruzomberok"));  // capital only
        assertTrue(UnicodeFolder.isFolded("Petr"));
        assertFalse(UnicodeFolder.isFolded("Ružomberok"));
        assertFalse(UnicodeFolder.isFolded("Пётр"));
        assertFalse(UnicodeFolder.isFolded("ποιός"));
        assertFalse(UnicodeFolder.isFolded("straße"));     // folding expands, so length differs
    }

    // --- flat dictionary_lemmatizer ---

    @Test
    public void flatDictionaryMatchesAFoldedToken() throws Exception {
        Path dict = dictionary("ružomberku\tRužomberok\nkošice\tKošice\n");
        var folding = DictionaryLemmatizer.fromFile(dict, false, true);
        assertEquals(List.of("Ružomberok", "Košice"), analyze(folding.apply(whitespace("ruzomberku kosice"))));
    }

    @Test
    public void flatDictionaryIsUntouchedWhenTheSettingIsOff() throws Exception {
        Path dict = dictionary("ružomberku\tRužomberok\nkošice\tKošice\n");
        var plain = DictionaryLemmatizer.fromFile(dict);
        assertEquals(List.of("ruzomberku", "kosice"), analyze(plain.apply(whitespace("ruzomberku kosice"))));
        assertEquals(0, plain.foldedSize());
    }

    /** The exact automaton still wins: a form that exists in its own right keeps its own lemma. */
    @Test
    public void anExactHitOutranksAFoldedOne() throws Exception {
        Path dict = dictionary("sud\tsud\nsúd\tsúd\n");
        var folding = DictionaryLemmatizer.fromFile(dict, false, true);
        assertEquals(List.of("sud", "súd"), analyze(folding.apply(whitespace("sud súd"))));
    }

    /** A dictionary where nothing folds is legitimate: no folded automaton, no failure. */
    @Test
    public void aDictionaryWithNothingToFoldStillLoads() throws Exception {
        Path dict = dictionary("dogs\tdog\ncats\tcat\n");
        var folding = DictionaryLemmatizer.fromFile(dict, false, true);
        assertEquals(0, folding.foldedSize());
        assertEquals(List.of("dog", "cat"), analyze(folding.apply(whitespace("dogs cats"))));
    }

    // --- non-Latin scripts: the reason the folding is UTR#30 and not ASCII ---

    @Test
    public void greekAccentsFold() throws Exception {
        Path dict = dictionary("αθήνα\tΑθήνα\nκαλημέρα\tκαλημέρα\n");
        var folding = DictionaryLemmatizer.fromFile(dict, false, true);
        assertTrue("a Greek dictionary must produce folded keys", folding.foldedSize() > 0);
        assertEquals(List.of("Αθήνα", "καλημέρα"), analyze(folding.apply(whitespace("αθηνα καλημερα"))));
    }

    @Test
    public void cyrillicYoFoldsToYe() throws Exception {
        Path dict = dictionary("пётр\tПётр\nёлка\tёлка\n");
        var folding = DictionaryLemmatizer.fromFile(dict, false, true);
        assertEquals(List.of("Пётр", "ёлка"), analyze(folding.apply(whitespace("петр елка"))));
    }

    // --- POS-aware pos_dictionary_lemmatizer, against the real Slovak lexicon ---

    private static void assumeSlovakModels() {
        assumeTrue("need sk-pos.bin + sk-lemmas.bin + sk-mte-pos.txt under " + DIR,
            Files.isReadable(SK_POS) && Files.isReadable(SK_LEMMAS) && Files.isReadable(SK_DICT));
    }

    private static String lemmatize(OpenNlpLemmatizer lemmatizer, String text) throws Exception {
        return String.join(" ", analyze(lemmatizer.apply(new LowerCaseFilter(whitespace(text)))));
    }

    @Test
    public void slovakCitiesResolveWithoutTheirDiacritics() throws Exception {
        assumeSlovakModels();
        var folding = OpenNlpLemmatizer.fromModels(SK_POS, SK_LEMMAS, SK_DICT, false, true, false, true);
        assertEquals("bývať v Ružomberok už dlho", lemmatize(folding, "Byvam v Ruzomberku uz dlho"));
        assertEquals("cestovať do Košice na víkend", lemmatize(folding, "Cestujem do Kosic na vikend"));
        assertEquals("byť byť v Trenčín minulý rok", lemmatize(folding, "Bol som v Trencine minuly rok"));
        assertEquals("ísť do Piešťany na kúpeľ", lemmatize(folding, "Idem do Piestan na kupele"));
    }

    /**
     * The whole safety argument in one assertion: with folding on, correctly spelled Slovak must come out
     * byte-identical to what it produces today. The folded automaton sits behind both exact attempts, so
     * it can only ever speak where the filter used to have nothing.
     */
    @Test
    public void correctlySpelledTextIsUnaffected() throws Exception {
        assumeSlovakModels();
        var plain = OpenNlpLemmatizer.fromModels(SK_POS, SK_LEMMAS, SK_DICT);
        var folding = OpenNlpLemmatizer.fromModels(SK_POS, SK_LEMMAS, SK_DICT, false, true, false, true);
        for (String text : new String[] {
            "Hostia prišli do Bratislavy a navštívili starý hrad",
            "Bývam v Ružomberku už dlho",
            "Peter s rodinou navštívil Košice Tatry a Poprad",
            "Tri ženy niesli tri jablká",
        }) {
            assertEquals(text, lemmatize(plain, text), lemmatize(folding, text));
        }
    }

    /**
     * The POS-relaxed folded key is settled by majority across the whole folded class, not by whichever
     * row happened to carry a {@code *}. Slovak {@code už} (two rows, CC and RB) must outvote the single
     * {@code úž} row of {@code úžiť} — with first-wins it did not, and {@code uz} lemmatised to
     * {@code úžiť}.
     */
    @Test
    public void aFoldedKeyGoesToTheReadingMostOfItsClassSupports() throws Exception {
        assumeSlovakModels();
        var folding = OpenNlpLemmatizer.fromModels(SK_POS, SK_LEMMAS, SK_DICT, false, true, false, true);
        assertEquals("už", lemmatize(folding, "uz"));
    }

    /** Folding changes what gets built, so the node-wide cache must not serve one variant for the other. */
    @Test
    public void theCacheKeepsTheFoldingAndNonFoldingDictionariesApart() throws Exception {
        Path dict = dictionary("ružomberku\tRužomberok\n");
        Path configDir = dict.getParent().resolve("cfg-" + System.nanoTime());
        Files.createDirectories(configDir.resolve(OpenNlpLemmatizer.MODELS_DIRECTORY));
        Path installed = configDir.resolve(OpenNlpLemmatizer.MODELS_DIRECTORY).resolve("d.txt");
        Files.copy(dict, installed);

        var plain = DictionaryLemmatizer.fromConfig("t", configDir, "d.txt", false, false);
        var folding = DictionaryLemmatizer.fromConfig("t", configDir, "d.txt", false, true);
        assertEquals(0, plain.foldedSize());
        assertTrue(folding.foldedSize() > 0);
        assertNotSame(plain.dictionary(), folding.dictionary());

        // ...and each variant is still shared with its own kind
        assertEquals(folding.dictionary(),
            DictionaryLemmatizer.fromConfig("t", configDir, "d.txt", false, true).dictionary());
    }
}
