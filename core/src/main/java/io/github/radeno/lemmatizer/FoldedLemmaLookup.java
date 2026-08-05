package io.github.radeno.lemmatizer;

/**
 * The folded half of a dictionary, kept apart from OpenNLP's {@link opennlp.tools.lemmatizer.Lemmatizer}
 * so that the order of the attempts stays visible at the call site rather than hidden inside a lookup.
 *
 * <p>That order is the whole point. Both exact attempts — {@code (form, POS)} and the POS-relaxed
 * {@code (form, *)} — must be exhausted before the folded one runs, because an exact hit is an entry the
 * dictionary really holds while a folded hit is an inference from a differently written one. Folding the
 * two into a single {@code lemmatize} call would let {@code folded(form, POS)} outrank
 * {@code exact(form, *)}, quietly preferring the inference to the fact.
 */
interface FoldedLemmaLookup {

    /**
     * The lemma for {@code (fold(word), tag)}, or OpenNLP's {@code "O"} marker when absent. Callers must
     * gate this on {@link UnicodeFolder#isFolded(String)}.
     */
    String lemmatizeFolded(String word, String tag);
}
