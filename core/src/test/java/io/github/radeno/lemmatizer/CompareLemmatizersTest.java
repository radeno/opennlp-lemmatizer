package io.github.radeno.lemmatizer;

import static org.junit.Assume.assumeTrue;

import java.io.BufferedInputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import eu.hlavki.text.lemmagen.LemmatizerFactory;
import eu.hlavki.text.lemmagen.api.Lemmatizer;

import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.opennlp.OpenNLPLemmatizerFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPPOSFilter;
import org.apache.lucene.analysis.opennlp.tools.NLPLemmatizerOp;
import org.apache.lucene.analysis.opennlp.tools.NLPPOSTaggerOp;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import org.junit.Test;

/**
 * Side-by-side demo: jLemmaGen (.lem, context-free) vs Apache OpenNLP (POS-aware),
 * for Czech (cs) and Slovak (sk). Prints a comparison table — no hard assertions.
 * Needs models/{cs,sk}-pos.bin, models/{cs,sk}-lemmas.bin and models/{cs,sk}.lem.
 */
public class CompareLemmatizersTest {

    private static final String DIR = System.getProperty("opennlp.models.dir", "models");

    private static final String[] CS = {
        "Děkuji že jsi přišel",
        "Ženu ženu na pole",
        "Tři ženy nesly tři jablka",
        "Praha je krásné město",
        "Byli jsme v lese a viděli jsme stromy"
    };

    private static final String[] SK = {
        "Ďakujem že si prišiel",
        "Ženu ženu na pole",
        "Traja muži niesli tri jablká",
        "Bratislava je krásne mesto",
        "Boli sme v lese a videli sme stromy"
    };

    @Test
    public void compareCzech() throws Exception {
        runComparison("cs", CS);
    }

    @Test
    public void compareSlovak() throws Exception {
        runComparison("sk", SK);
    }

    private void runComparison(String lang, String[] sentences) throws Exception {
        Path pos = Paths.get(DIR, lang + "-pos.bin");
        Path lem = Paths.get(DIR, lang + "-lemmas.bin");
        Path lex = Paths.get(DIR, lang + ".lem");
        assumeTrue("need " + lang + " OpenNLP models + " + lang + ".lem under " + DIR,
            Files.isReadable(pos) && Files.isReadable(lem) && Files.isReadable(lex));

        Lemmatizer jlemma = LemmatizerFactory.read(new BufferedInputStream(Files.newInputStream(lex)));
        POSModel posModel = new POSModel(new BufferedInputStream(Files.newInputStream(pos)));
        LemmatizerModel lemModel = new LemmatizerModel(new BufferedInputStream(Files.newInputStream(lem)));

        System.out.println("\n============== " + lang.toUpperCase() + ":  jLemmaGen  vs  OpenNLP ==============");
        for (String sentence : sentences) {
            String[] words = sentence.split("\\s+");
            List<String> openNlp = openNlpLemmas(sentence, posModel, lemModel);

            System.out.println("\n# " + sentence);
            System.out.printf("  %-14s %-14s %-14s %-14s%n", "TOKEN", "jLemmaGen", "jLemma+lc", "OpenNLP");
            for (int i = 0; i < words.length; i++) {
                String w = words[i];
                String jl = jlemma.lemmatize(w).toString();
                String jlLc = jlemma.lemmatize(w.toLowerCase()).toString();
                String on = i < openNlp.size() ? openNlp.get(i) : "?";
                System.out.printf("  %-14s %-14s %-14s %-14s%n", w, jl, jlLc, on);
            }
        }
        System.out.println("\n=====================================================================\n");
    }

    static List<String> openNlpLemmas(String sentence, POSModel posModel, LemmatizerModel lemModel) throws Exception {
        Tokenizer tokenizer = new WhitespaceTokenizer();
        tokenizer.setReader(new StringReader(sentence));
        TokenStream ts = new OpenNLPLemmatizerFilter(
            new OpenNLPPOSFilter(tokenizer, new NLPPOSTaggerOp(posModel)),
            new NLPLemmatizerOp(null, lemModel));

        List<String> out = new ArrayList<>();
        CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
        ts.reset();
        while (ts.incrementToken()) {
            out.add(term.toString());
        }
        ts.end();
        ts.close();
        return out;
    }
}
