package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedInputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.opennlp.OpenNLPPOSFilter;
import org.apache.lucene.analysis.opennlp.tools.NLPPOSTaggerOp;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import org.junit.Test;

/**
 * The point of {@link SentenceChunkFilter}: a multi-sentence field must lemmatise the same way whether
 * it arrives as one string or one sentence at a time. Without chunking the POS tagger sees the whole
 * field as a single sentence and mis-tags across the seams; with it, the two agree exactly.
 *
 * <p>Needs {@code sk-pos.bin}, {@code sk-lemmas.bin}, {@code sk-mte-pos.txt}; self-skips without them.
 */
public class SentenceChunkingGoldenTest {

    private static final String DIR = System.getProperty("opennlp.models.dir", "models");
    private static final Path POS = Paths.get(DIR, "sk-pos.bin");
    private static final Path LEMMAS = Paths.get(DIR, "sk-lemmas.bin");
    private static final Path DICT = Paths.get(DIR, "sk-mte-pos.txt");

    /**
     * Chosen so tagging the joined field as one sentence genuinely differs from per-sentence: verified
     * that {@code Majestátne} lemmatises to {@code majestátny} on its own but drifts to {@code majestátne}
     * when the preceding sentence runs into it. {@code withoutChunkingTheJoinedFieldWouldDiffer} pins that.
     */
    private static final String[] SENTENCES = {
        "Rieka Dunaj tečie cez mesto.",
        "Je to naozaj krásne.",
        "Majestátne hory sa týčili nad údolím.",
        "Kniha leží na stole.",
    };

    private static void assume() {
        assumeTrue("need sk-pos.bin + sk-lemmas.bin + sk-mte-pos.txt under " + DIR,
            Files.isReadable(POS) && Files.isReadable(LEMMAS) && Files.isReadable(DICT));
    }

    private static List<String> lemmas(OpenNlpLemmatizer lemmatizer, String text) throws Exception {
        Tokenizer source = new WhitespaceTokenizer();
        source.setReader(new StringReader(text));
        TokenStream stream = lemmatizer.apply(new LowerCaseFilter(source));
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        List<String> out = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            out.add(term.toString());
        }
        stream.end();
        stream.close();
        return out;
    }

    private static List<String> perSentence(OpenNlpLemmatizer lemmatizer) throws Exception {
        List<String> out = new ArrayList<>();
        for (String sentence : SENTENCES) {
            out.addAll(lemmas(lemmatizer, sentence));
        }
        return out;
    }

    @Test
    public void wholeFieldMatchesPerSentenceForThePosDictionary() throws Exception {
        assume();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, DICT);
        assertEquals(perSentence(lemmatizer), lemmas(lemmatizer, String.join(" ", SENTENCES)));
    }

    @Test
    public void wholeFieldMatchesPerSentenceForTheModelOnlyPath() throws Exception {
        assume();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS);
        assertEquals(perSentence(lemmatizer), lemmas(lemmatizer, String.join(" ", SENTENCES)));
    }

    /**
     * The regression guard proper: prove chunking is doing the work by removing it. Building the same
     * dictionary pipeline over the raw stream — the way {@code apply()} did before this filter existed —
     * tags the joined field as one sentence and must produce a different result, otherwise the tests
     * above prove nothing. The models are loaded directly so the comparison needs no chunk filter at all.
     */
    @Test
    public void withoutChunkingTheJoinedFieldWouldDiffer() throws Exception {
        assume();
        OpenNlpLemmatizer lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, DICT);
        POSModel posModel = load(POS, POSModel::new);
        LemmatizerModel lemmaModel = load(LEMMAS, LemmatizerModel::new);
        FstPosDictionaryLemmatizer dictionary = FstPosDictionaryLemmatizer.fromFile(DICT);

        List<String> unchunked = lemmasWithoutChunking(posModel, lemmaModel, dictionary,
            String.join(" ", SENTENCES));
        assertNotEquals(perSentence(lemmatizer), unchunked);
    }

    /** Reproduces the pre-fix pipeline: POS tagger over the raw stream, no sentence boundaries. */
    private static List<String> lemmasWithoutChunking(POSModel posModel, LemmatizerModel lemmaModel,
            FstPosDictionaryLemmatizer dictionary, String text) throws Exception {
        Tokenizer source = new WhitespaceTokenizer();
        source.setReader(new StringReader(text));
        var tagged = new OpenNLPPOSFilter(new LowerCaseFilter(source), new NLPPOSTaggerOp(posModel));
        TokenStream stream = new OpenNlpPosLemmatizerFilter(tagged, dictionary, lemmaModel);
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        List<String> out = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            out.add(term.toString());
        }
        stream.end();
        stream.close();
        return out;
    }

    @FunctionalInterface
    private interface ModelFactory<M> {
        M create(java.io.InputStream in) throws java.io.IOException;
    }

    private static <M> M load(Path path, ModelFactory<M> factory) throws Exception {
        try (var in = new BufferedInputStream(Files.newInputStream(path))) {
            return factory.create(in);
        }
    }
}
