package io.github.radeno.lemmatizer;

import static org.junit.Assume.assumeTrue;

import java.io.BufferedInputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import eu.hlavki.text.lemmagen.LemmatizerFactory;
import eu.hlavki.text.lemmagen.api.Lemmatizer;

import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.opennlp.OpenNLPLemmatizerFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPPOSFilter;
import org.apache.lucene.analysis.opennlp.tools.NLPLemmatizerOp;
import org.apache.lucene.analysis.opennlp.tools.NLPPOSTaggerOp;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import org.junit.Test;

/**
 * Throughput benchmark: jLemmaGen vs OpenNLP on the same Czech corpus. Reports tokens/sec.
 * Ops are built once and reset() per sentence (the steady state OpenSearch/Elasticsearch use).
 */
public class LemmatizerBenchmarkTest {

    private static final String DIR = System.getProperty("opennlp.models.dir", "models");

    private static final String[] CORPUS = {
        "Děkuji že jsi přišel",
        "Ženu ženu na pole",
        "Tři ženy nesly tři jablka",
        "Praha je krásné město",
        "Byli jsme v lese a viděli jsme stromy"
    };

    @Test
    public void benchmarkCzech() throws Exception {
        Path pos = Paths.get(DIR, "cs-pos.bin");
        Path lem = Paths.get(DIR, "cs-lemmas.bin");
        Path lex = Paths.get(DIR, "cs.lem");
        assumeTrue("need cs OpenNLP models + cs.lem under " + DIR,
            Files.isReadable(pos) && Files.isReadable(lem) && Files.isReadable(lex));

        String[][] words = new String[CORPUS.length][];
        int tokens = 0;
        for (int i = 0; i < CORPUS.length; i++) {
            words[i] = CORPUS[i].split("\\s+");
            tokens += words[i].length;
        }
        final int tokensPerPass = tokens;

        // jLemmaGen
        Lemmatizer jlemma = LemmatizerFactory.read(new BufferedInputStream(Files.newInputStream(lex)));
        for (int it = 0; it < 2000; it++) {
            for (String[] s : words) for (String w : s) jlemma.lemmatize(w);
        }
        int jlIters = 20000;
        long t0 = System.nanoTime();
        for (int it = 0; it < jlIters; it++) {
            for (String[] s : words) for (String w : s) jlemma.lemmatize(w);
        }
        long jlNanos = System.nanoTime() - t0;

        // OpenNLP (POS + lemmatizer), ops built once, reset() per sentence
        POSModel posModel = new POSModel(new BufferedInputStream(Files.newInputStream(pos)));
        LemmatizerModel lemModel = new LemmatizerModel(new BufferedInputStream(Files.newInputStream(lem)));
        var posOp = new NLPPOSTaggerOp(posModel);
        var lemOp = new NLPLemmatizerOp(null, lemModel);
        var tokenizer = new WhitespaceTokenizer();
        TokenStream chain = new OpenNLPLemmatizerFilter(new OpenNLPPOSFilter(tokenizer, posOp), lemOp);
        CharTermAttribute term = chain.addAttribute(CharTermAttribute.class);

        for (int it = 0; it < 200; it++) {
            for (String s : CORPUS) consume(chain, tokenizer, term, s);
        }
        int onIters = 2000;
        t0 = System.nanoTime();
        for (int it = 0; it < onIters; it++) {
            for (String s : CORPUS) consume(chain, tokenizer, term, s);
        }
        long onNanos = System.nanoTime() - t0;

        double jlRate = (double) jlIters * tokensPerPass / (jlNanos / 1e9);
        double onRate = (double) onIters * tokensPerPass / (onNanos / 1e9);
        System.out.println("\n==================== THROUGHPUT (Czech) ====================");
        System.out.printf("  jLemmaGen : %,10.0f tokens/sec   (%.3f us/token)%n", jlRate, 1e6 / jlRate);
        System.out.printf("  OpenNLP   : %,10.0f tokens/sec   (%.3f us/token)%n", onRate, 1e6 / onRate);
        System.out.printf("  -> jLemmaGen is %.0fx faster%n", jlRate / onRate);
        System.out.println("============================================================\n");
    }

    private static void consume(TokenStream chain, WhitespaceTokenizer tokenizer, CharTermAttribute term, String sentence)
            throws Exception {
        tokenizer.setReader(new StringReader(sentence));
        chain.reset();
        while (chain.incrementToken()) {
            term.length();
        }
        chain.end();
        chain.close();
    }
}
