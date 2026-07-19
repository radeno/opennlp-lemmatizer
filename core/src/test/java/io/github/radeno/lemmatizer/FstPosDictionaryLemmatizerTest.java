package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class FstPosDictionaryLemmatizerTest {

    @Test
    public void resolvesByFormAndPosCaseSensitivelyKeepingLemmaCase() throws Exception {
        Path dict = Files.createTempFile("fstposdict", ".txt");
        // includes Slovak diacritics to exercise UTF-8 byte ordering and a POS-ambiguous form ("je")
        Files.writeString(dict, "je\tMD\tbyť\nje\tVB\tjesť\ntri\tCD\ttri\nbratislava\tNN\tBratislava\nžena\tNN\tžena\n");

        FstPosDictionaryLemmatizer lem = FstPosDictionaryLemmatizer.fromFile(dict);
        assertEquals(5, lem.size());

        // POS disambiguates "je"; lower-cased "bratislava" resolves but the lemma keeps its case
        // (-> "Bratislava"); unknown (word, POS) returns OpenNLP's "O" marker for model fallback
        String[] words = { "je", "je", "tri", "bratislava", "žena", "xyz" };
        String[] tags = { "MD", "VB", "CD", "NN", "NN", "NN" };
        assertArrayEquals(
            new String[] { "byť", "jesť", "tri", "Bratislava", "žena", "O" },
            lem.lemmatize(words, tags));
    }

    @Test
    public void lookupIsCaseSensitiveSoChainLowercaseUpstream() throws Exception {
        Path dict = Files.createTempFile("fstposdict", ".txt");
        Files.writeString(dict, "je\tMD\tbyť\nbratislava\tNN\tBratislava\n");

        FstPosDictionaryLemmatizer lem = FstPosDictionaryLemmatizer.fromFile(dict);
        // capitalised input does NOT match (filter never folds case) -> "O" -> model fallback
        assertArrayEquals(
            new String[] { "O", "O" },
            lem.lemmatize(new String[] { "Je", "Bratislava" }, new String[] { "MD", "NN" }));
    }

    /**
     * A file not in FST key order still loads correctly (via {@link FstBuilder}'s buffered fallback), and
     * duplicate {@code (form, POS)} keys keep the first lemma. Low-level build paths are in
     * {@link FstBuilderTest}; this pins the user-facing guarantee of {@code fromFile}.
     */
    @Test
    public void fromFileLoadsAnUnsortedFileAndKeepsTheFirstOfDuplicateKeys() throws Exception {
        Path dict = Files.createTempFile("fstposdict-unsorted", ".txt");
        // 't' before 'a' -> not key order; the (je, MD) key repeats -> first ("byť") wins
        Files.writeString(dict, "tri\tCD\ttri\nje\tMD\tbyť\nje\tMD\tDUPLICATE\nauto\t*\tauto\n");

        FstPosDictionaryLemmatizer lem = FstPosDictionaryLemmatizer.fromFile(dict);
        assertEquals(3, lem.size());
        assertArrayEquals(
            new String[] { "auto", "tri", "byť" },
            lem.lemmatize(new String[] { "auto", "tri", "je" }, new String[] { "*", "CD", "MD" }));
    }
}
