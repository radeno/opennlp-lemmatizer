package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The case-only guard on the MaxEnt fallback, tested directly — no models, so it runs in CI where
 * {@code models/} is empty and the behavioural tests self-skip.
 *
 * @see OpenNlpPosLemmatizerFilterTest for the same rules through a real token stream
 */
public class CaseOnlyGuardTest {

    /** What the guard exists for: the model lower-cased a token it could not lemmatise. */
    @Test
    public void firesWhenTheModelOnlyLowerCasedTheToken() {
        assertTrue(OpenNlpPosLemmatizerFilter.foldsCaseOnly("NATO", "nato"));
        assertTrue(OpenNlpPosLemmatizerFilter.foldsCaseOnly("SKU-4711", "sku-4711"));
        assertTrue(OpenNlpPosLemmatizerFilter.foldsCaseOnly("iPhone", "iphone"));
        assertTrue(OpenNlpPosLemmatizerFilter.foldsCaseOnly("Smith", "smith"));
        assertTrue(OpenNlpPosLemmatizerFilter.foldsCaseOnly("Ľubovňa", "ľubovňa"));
    }

    /** A real lemma changes letters, not just case, and must reach the token. */
    @Test
    public void neverFiresOnAnActualLemma() {
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("Hostia", "hosť"));
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("prišli", "prísť"));
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("Bratislavu", "bratisla"));
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("nato", "nato")); // unchanged
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("whatever", null));
    }

    /**
     * The direction matters. A model trained on capitalised lemmas may answer {@code haus -> Haus}; that
     * is a real lemma and must survive. Only a foldable token can have lost its case.
     */
    @Test
    public void neverFiresWhenTheLemmaIsTheOneCarryingTheCase() {
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("haus", "Haus"));
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("o", "O"));
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("istanbul", "Istanbul"));
    }

    /**
     * The property the recommended pipeline rests on: behind a {@code lowercase} filter the guard cannot
     * fire, so it can change nothing. Lucene's {@code LowerCaseFilter} maps every code point through
     * {@link Character#toLowerCase(int)}; this sweeps the whole code space and asserts that no such
     * already-folded token can trigger the guard, whatever the model answers.
     */
    @Test
    public void isInertBehindALowercaseFilterAcrossTheWholeCodeSpace() {
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                continue;
            }
            String folded = new String(Character.toChars(Character.toLowerCase(cp))); // what LowerCaseFilter emits
            String upper = new String(Character.toChars(Character.toUpperCase(cp)));
            if (folded.equals(upper)) {
                continue; // no case pair to confuse the guard with
            }
            assertFalse("U+" + Integer.toHexString(cp), OpenNlpPosLemmatizerFilter.foldsCaseOnly(folded, upper));
        }
    }

    /** The classic case-folding traps, and where the guard's letters-must-match rule draws the line. */
    @Test
    public void survivesTheUsualUnicodeTraps() {
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("i", "I"));  // already folded
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("ı", "I"));  // dotless i, already folded
        assertTrue(OpenNlpPosLemmatizerFilter.foldsCaseOnly("ẞ", "ß"));
        // sigma folds to its final form at the end of a word, but the letters still match ignoring case
        assertTrue(OpenNlpPosLemmatizerFilter.foldsCaseOnly("ΟΔΟΣ", "οδος"));
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("οδος", "ΟΔΟΣ")); // already folded
        // known limit: lower-casing İ appends a combining dot, so the letters no longer match and the
        // model's answer wins. Turkish is not a language these models cover.
        assertFalse(OpenNlpPosLemmatizerFilter.foldsCaseOnly("İSTANBUL", "i̇stanbul"));
    }
}
