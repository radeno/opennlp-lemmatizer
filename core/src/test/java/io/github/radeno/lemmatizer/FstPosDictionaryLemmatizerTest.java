package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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

    // --- M2: low-memory streaming load (already-sorted file) vs. buffered fallback ---

    @Test
    public void streamBuildsAnAlreadySortedFileWithFirstWinsDedup() throws Exception {
        // LC_ALL=C key order (form<TAB>POS): '*'(0x2a) < 'C'(0x43) < 'M' < 'N'; duplicate key -> first wins.
        Path dict = Files.createTempFile("fstposdict-sorted", ".txt");
        Files.writeString(dict,
            "auto\t*\tauto\n" + "je\tMD\tbyť\n" + "je\tMD\tDUPLICATE\n" + "je\tVB\tjesť\n" + "tri\tCD\ttri\n");

        FstPosDictionaryLemmatizer lem = FstPosDictionaryLemmatizer.streamBuild(dict);
        assertEquals(4, lem.size()); // the duplicate (je, MD) collapses to one
        assertArrayEquals(
            new String[] { "auto", "byť", "jesť", "tri" },
            lem.lemmatize(new String[] { "auto", "je", "je", "tri" },
                          new String[] { "*", "MD", "VB", "CD" }));
    }

    @Test
    public void streamBuildRejectsAnOutOfOrderFileSoFromFileCanFallBack() throws Exception {
        Path dict = Files.createTempFile("fstposdict-unsorted", ".txt");
        Files.writeString(dict, "tri\tCD\ttri\nauto\t*\tauto\n"); // 't' before 'a' -> not key order

        assertThrows(FstPosDictionaryLemmatizer.UnsortedDictionaryException.class,
            () -> FstPosDictionaryLemmatizer.streamBuild(dict));

        // fromFile() must still load it correctly via the buffered fallback
        FstPosDictionaryLemmatizer lem = FstPosDictionaryLemmatizer.fromFile(dict);
        assertArrayEquals(
            new String[] { "auto", "tri" },
            lem.lemmatize(new String[] { "auto", "tri" }, new String[] { "*", "CD" }));
    }

    @Test
    public void streamAndBufferedBuildsAgreeOnTheSameSortedInput() throws Exception {
        Path dict = Files.createTempFile("fstposdict-agree", ".txt");
        Files.writeString(dict, "auto\t*\tauto\nbratislava\tNN\tBratislava\nje\tMD\tbyť\nje\tVB\tjesť\n");

        String[] words = { "auto", "bratislava", "je", "je", "xyz" };
        String[] tags = { "*", "NN", "MD", "VB", "NN" };
        assertArrayEquals(
            FstPosDictionaryLemmatizer.bufferedBuild(dict).lemmatize(words, tags),
            FstPosDictionaryLemmatizer.streamBuild(dict).lemmatize(words, tags));
    }
}
