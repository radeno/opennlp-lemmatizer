package io.github.radeno.lemmatizer;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.SentenceAttribute;

/**
 * Marks sentence boundaries on the token stream so the OpenNLP POS tagger tags one sentence at a time.
 *
 * <p>Lucene's {@code OpenNLPPOSFilter} buffers tokens up to a change in {@link SentenceAttribute}, and
 * only {@code OpenNLPTokenizer} ever sets that attribute. Behind any other tokenizer the attribute stays
 * at 0, so the whole field arrives at the tagger as a single enormous sentence: the tagger is asked for
 * something it was never trained on, and every token of the field is held in memory at once.
 *
 * <p>Splitting the stream restores per-sentence tagging — measured on Slovak, it takes the POS tags back
 * to exactly what the tagger produces for each sentence in isolation, which removes lemma errors such as
 * the copula {@code je} being read as a form of {@code jesť} — and bounds the buffer to one sentence.
 *
 * <p>{@link #MAX_SENTENCE_TOKENS} is a safety valve, not a policy: a field whose tokenizer strips
 * punctuation (or a genuinely unbroken wall of text) has no boundary to find, and would otherwise buffer
 * without limit. Cutting a real sentence in half costs tagging accuracy, so the cap is set far above any
 * natural sentence length and should not be relied on for chunking.
 */
final class SentenceChunkFilter extends TokenFilter {

    /** Buffering guard for a stream with no sentence-ending punctuation at all. Far above any sentence. */
    static final int MAX_SENTENCE_TOKENS = 1000;

    /** Trailing characters that may follow the terminator, e.g. {@code hrad."} or {@code „naozaj!“}. */
    private static final String CLOSING = "\"'”’“»)]}"; // includes U+201C, the Slovak closing quote

    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final SentenceAttribute sentenceAttr = addAttribute(SentenceAttribute.class);

    private int sentenceIndex = 0;
    private int tokensInSentence = 0;

    SentenceChunkFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!input.incrementToken()) {
            return false;
        }
        sentenceAttr.setSentenceIndex(sentenceIndex);
        tokensInSentence++;
        if (endsSentence(termAttr) || tokensInSentence >= MAX_SENTENCE_TOKENS) {
            sentenceIndex++;
            tokensInSentence = 0;
        }
        return true;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        sentenceIndex = 0;
        tokensInSentence = 0;
    }

    /**
     * Whether {@code token} ends a sentence. A whitespace tokenizer leaves the terminator glued to the
     * last word ({@code hrad.}), so this looks at the token's tail rather than for a standalone mark.
     *
     * <p>{@code !}, {@code ?} and {@code …} are unambiguous. A final {@code .} is not: it also ends an
     * ordinal ({@code 21.}), a one- or two-letter abbreviation ({@code č.}) and a dotted one
     * ({@code t.j.}), none of which end a sentence. Longer abbreviations ({@code napr.}) are
     * indistinguishable from a short word without a sentence model, and do get split — one extra boundary
     * costs the tagger far less than a missing one.
     */
    static boolean endsSentence(CharSequence token) {
        int end = token.length();
        while (end > 0 && CLOSING.indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        if (end == 0) {
            return false;
        }
        char last = token.charAt(end - 1);
        if (last == '!' || last == '?' || last == '…') {
            return true;
        }
        if (last != '.') {
            return false;
        }
        int core = end - 1; // length of the token without its final period
        if (core == 0) {
            return true; // a bare "." (or "..." below), which only a punctuation-keeping tokenizer emits
        }
        if (Character.isDigit(token.charAt(core - 1))) {
            return false; // "21." — an ordinal, not a full stop
        }
        for (int i = 0; i < core; i++) {
            if (token.charAt(i) == '.') {
                return isAllDots(token, core); // "t.j." keeps going; "..." ends
            }
        }
        return core > 2; // "č." and "s." abbreviate; "áno." and "tak." conclude
    }

    private static boolean isAllDots(CharSequence token, int core) {
        for (int i = 0; i < core; i++) {
            if (token.charAt(i) != '.') {
                return false;
            }
        }
        return true;
    }
}
