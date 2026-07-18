package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The sentence-boundary heuristic, tested directly — no models, so it runs in CI where {@code models/}
 * is empty. {@link OpenNlpPosLemmatizerFilterTest} and {@link PosDictionaryLemmatizerGoldenTest} cover
 * the effect on real tagging.
 */
public class SentenceChunkFilterTest {

    @Test
    public void endsOnTheThreeUnambiguousTerminators() {
        assertTrue(SentenceChunkFilter.endsSentence("hrad."));
        assertTrue(SentenceChunkFilter.endsSentence("naozaj!"));
        assertTrue(SentenceChunkFilter.endsSentence("čo?"));
        assertTrue(SentenceChunkFilter.endsSentence("nuž…"));
    }

    @Test
    public void doesNotEndMidSentence() {
        assertFalse(SentenceChunkFilter.endsSentence("Hostia"));
        assertFalse(SentenceChunkFilter.endsSentence("do"));
        assertFalse(SentenceChunkFilter.endsSentence("mesta,"));
    }

    /** A final period is ambiguous: these are the cases it must NOT read as a sentence end. */
    @Test
    public void doesNotSplitOnAPeriodThatIsNotAFullStop() {
        assertFalse("ordinal", SentenceChunkFilter.endsSentence("21."));
        assertFalse("decimal", SentenceChunkFilter.endsSentence("3.14"));
        assertFalse("one-letter abbrev", SentenceChunkFilter.endsSentence("č."));
        assertFalse("two-letter abbrev", SentenceChunkFilter.endsSentence("s."));
        assertFalse("dotted abbrev", SentenceChunkFilter.endsSentence("t.j."));
        assertFalse("dotted abbrev", SentenceChunkFilter.endsSentence("np.o."));
        assertFalse("domain", SentenceChunkFilter.endsSentence("www.example.sk"));
    }

    @Test
    public void splitsOnAGenuineFullStopIncludingShortWords() {
        assertTrue(SentenceChunkFilter.endsSentence("tak."));
        assertTrue(SentenceChunkFilter.endsSentence("áno."));
        assertTrue(SentenceChunkFilter.endsSentence("Bratislavy."));
        // longer abbreviations are indistinguishable from a word and do split — the accepted trade-off
        assertTrue("napr. splits, by design", SentenceChunkFilter.endsSentence("napr."));
    }

    /** Trailing quotes and brackets after the terminator must not hide it. */
    @Test
    public void seesThroughClosingPunctuation() {
        assertTrue(SentenceChunkFilter.endsSentence("hrad.\""));
        assertTrue(SentenceChunkFilter.endsSentence("naozaj!)"));
        assertTrue(SentenceChunkFilter.endsSentence("„citát.“"));
        assertFalse(SentenceChunkFilter.endsSentence("(napr"));
    }

    @Test
    public void handlesEmptyAndDegenerateTokens() {
        assertFalse(SentenceChunkFilter.endsSentence(""));
        assertFalse(SentenceChunkFilter.endsSentence("\"\""));
        assertTrue(SentenceChunkFilter.endsSentence("."));
        assertTrue(SentenceChunkFilter.endsSentence("..."));
    }

    @Test
    public void capIsawellAboveAnyNaturalSentence() {
        assertEquals(1000, SentenceChunkFilter.MAX_SENTENCE_TOKENS);
    }
}
