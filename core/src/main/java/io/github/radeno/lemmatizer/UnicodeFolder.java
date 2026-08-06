package io.github.radeno.lemmatizer;

import java.util.Locale;

import com.ibm.icu.text.Normalizer2;

import org.apache.lucene.analysis.icu.ICUFoldingFilter;

/**
 * Unicode folding (<a href="https://www.unicode.org/reports/tr30/tr30-4.html">UTR#30</a>) behind the
 * {@code unicode_folding} setting: the single normalisation used both when folded dictionary keys are
 * built and when a token is looked up against them.
 *
 * <p>It delegates to {@link ICUFoldingFilter#NORMALIZER} — the very normaliser behind the
 * {@code icu_folding} token filter — so a folded key is bit-for-bit what a user would expect from the
 * filter they already know. Folding is deliberately <b>not</b> limited to diacritics; UTR#30 also folds
 * case, ligatures, width, and compatibility forms:
 *
 * <pre>
 *   Ružomberok Αθήνα Пётр  -&gt;  ruzomberok αθηνα петр      (diacritics)
 *   KOŠICE                 -&gt;  kosice                     (case)
 *   ﬁnance œuvre           -&gt;  finance oeuvre             (ligatures)
 *   Ⅻ ½ ㎏                  -&gt;  xii 1/2 kg                 (compatibility)
 *   ποιός                  -&gt;  ποιοσ                      (Greek final sigma)
 * </pre>
 *
 * <p>Folding must never be done with {@code ASCIIFoldingFilter} here: its output is ASCII, so on a
 * non-Latin dictionary (Greek, Cyrillic) it leaves every form untouched, producing an empty folded
 * automaton and a setting that silently does nothing.
 */
final class UnicodeFolder {

    private static final Normalizer2 NORMALIZER = ICUFoldingFilter.NORMALIZER;

    private UnicodeFolder() {
    }

    /** The folded form of {@code s} — lower-cased, accents and compatibility forms resolved. */
    static String fold(String s) {
        return NORMALIZER.normalize(s);
    }

    /**
     * Whether {@code token} is already written in folded shape, ignoring case — the guard deciding
     * whether the folded lookup may fire at all.
     *
     * <p>The point is to tell "the user typed the plain form" ({@code ruzomberok}, {@code Petr},
     * {@code ΠΟΙΟΣ}) from "the user typed the rich form" ({@code Ružomberok}, {@code Пётр},
     * {@code ποιός}, {@code straße}). Only the former may reach the folded automaton: if a richly
     * written token missed the exact lookup, folding it is speculation, and speculation that outranks
     * the model fallback is how a correctly spelled unknown word gets a confidently wrong lemma.
     *
     * <p>Case is excluded on purpose, so the setting still works when no {@code lowercase} filter is
     * chained ahead — {@code Ruzomberok} is a plain form that merely kept its capital. The length test
     * catches the foldings that expand ({@code straße -> strasse}, {@code ﬁ -> fi}), which
     * {@code equalsIgnoreCase} alone would miss.
     */
    static boolean isFolded(String token) {
        return foldedKey(token) != null;
    }

    /**
     * The folded form to look {@code token} up by, or {@code null} when the guard refuses it.
     *
     * <p>Answering both questions at once is deliberate: deciding whether a token is plainly written
     * requires folding it, so a caller that asks {@link #isFolded} and then folds again pays the ICU
     * normaliser twice for one token — three times in the POS filter, which then folds once per POS
     * attempt. That is the whole of what {@code unicode_folding} used to cost on text where the folded
     * automaton is almost never reached.
     */
    static String foldedKey(String token) {
        String ascii = asciiFolded(token);
        if (ascii != null) {
            return ascii; // plain by construction, so the guard cannot refuse it
        }
        String folded = fold(token);
        return folded.length() == token.length() && folded.equalsIgnoreCase(token) ? folded : null;
    }

    /**
     * The folded form of an all-ASCII token, or {@code null} when this shortcut does not apply.
     *
     * <p>UTR#30 over ASCII is plain lower-casing, so the normaliser has nothing to contribute and can be
     * skipped — which matters because {@link #foldedKey} runs on <em>every</em> token the dictionary
     * misses, and diacritic-less input, the case the whole setting exists for, is all-ASCII by
     * definition. {@link String#toLowerCase} returns the same instance when nothing changes, so an
     * already-lower-case token costs one scan and no allocation.
     *
     * <p>Two ASCII code points are not lower-cased but <b>deleted</b> by UTR#30, being spacing accents:
     * {@code ^} (U+005E) and {@code `} (U+0060). Verified over all 128 code points, they are the only
     * two, and a token containing either falls through to the normaliser rather than being mis-folded.
     */
    private static String asciiFolded(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c > 127 || c == '^' || c == '`') {
                return null;
            }
        }
        return token.toLowerCase(Locale.ROOT);
    }
}
