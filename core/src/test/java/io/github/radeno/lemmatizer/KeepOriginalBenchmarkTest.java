package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import org.junit.Test;

/**
 * What {@code keep_original} actually costs and buys, measured on real Slovak text (the 6 articles in
 * {@code experiments/sentence-segmentation/articles}, ~1020 tokens, 2 sources, 4 styles).
 *
 * <p>The setting does not change lemmatization: it only adds the surface form beside a rewritten token.
 * So the interesting numbers are (1) proof that every lemma is byte-identical with the setting on —
 * quality cannot regress — (2) how many extra postings that costs, and (3) what it costs in throughput.
 * Self-skips when the models or the corpus are absent; prints a report and asserts the invariants.
 */
public class KeepOriginalBenchmarkTest {

    private static final String DIR = System.getProperty("opennlp.models.dir", "models");
    private static final Path POS = Paths.get(DIR, "sk-pos.bin");
    private static final Path LEMMAS = Paths.get(DIR, "sk-lemmas.bin");
    private static final Path POS_DICT = Paths.get(DIR, "sk-mte-pos.txt");
    private static final Path FLAT_DICT = Paths.get(DIR, "sk-mte.txt");
    // the corpus lives beside models/ in the repo, one sentence per line
    private static final Path ARTICLES =
        Paths.get(DIR).toAbsolutePath().getParent().resolve("experiments/sentence-segmentation/articles");

    /** One analysed field: the tokens fed in, and what came out with the setting off / on. */
    private record Run(List<String> input, List<String> off, List<Token> on) {
    }

    private record Token(String term, int positionIncrement) {
    }

    @Test
    public void reportCostAndBenefitOnRealText() throws Exception {
        assumeTrue("need sk-pos.bin + sk-lemmas.bin + sk-mte-pos.txt + sk-mte.txt under " + DIR,
            Files.isReadable(POS) && Files.isReadable(LEMMAS)
                && Files.isReadable(POS_DICT) && Files.isReadable(FLAT_DICT));
        assumeTrue("need the article corpus at " + ARTICLES, Files.isDirectory(ARTICLES));

        List<String> fields = readArticles();
        assumeTrue("no articles found in " + ARTICLES, !fields.isEmpty());

        System.out.println("\n===================== keep_original on real Slovak text =====================");
        System.out.printf("corpus: %d fields, %d tokens (%s)%n%n",
            fields.size(), countTokens(fields), ARTICLES.getFileName());

        var posDictOff = OpenNlpLemmatizer.fromModels(POS, LEMMAS, POS_DICT);
        var posDictOn = OpenNlpLemmatizer.fromModels(POS, LEMMAS, POS_DICT, LemmatizerOptions.defaults().keepOriginal(true));
        measure("pos_dictionary_lemmatizer", fields,
            in -> posDictOff.apply(new LowerCaseFilter(in)), in -> posDictOn.apply(new LowerCaseFilter(in)));

        var flatOff = DictionaryLemmatizer.fromFile(FLAT_DICT);
        var flatOn = DictionaryLemmatizer.fromFile(FLAT_DICT, LemmatizerOptions.defaults().keepOriginal(true));
        measure("dictionary_lemmatizer", fields,
            in -> flatOff.apply(new LowerCaseFilter(in)), in -> flatOn.apply(new LowerCaseFilter(in)));

        var modelOff = OpenNlpLemmatizer.fromModels(POS, LEMMAS);
        var modelOn = OpenNlpLemmatizer.fromModels(POS, LEMMAS, null, LemmatizerOptions.defaults().keepOriginal(true));
        measure("opennlp_lemmatizer", fields,
            in -> modelOff.apply(new LowerCaseFilter(in)), in -> modelOn.apply(new LowerCaseFilter(in)));

        documentedFailureCases();
        System.out.println("=============================================================================\n");
    }

    /**
     * The cases this repo documents as producing a wrong or domain-dependent lemma (S3, S5 and the
     * homonym/gender experiments). {@code keep_original} cannot fix any of them — it never changes a
     * lemma — but it does stop them from being unfindable, which is the whole point of the setting.
     * The lemma column is computed, not hard-coded, so this keeps telling the truth as the dictionary
     * improves.
     */
    private void documentedFailureCases() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("Tatry", "S3: MTE knows it only as a common noun");
        cases.put("Karpaty", "S3: not in MTE at all -> model fallback");
        cases.put("hrady", "S5: hrad(m)/hrada(f), corpus frequency 18:19");
        cases.put("angínu", "S5: out-of-dictionary gender homonym");
        cases.put("plese", "S5: ples(dance) vs pleso(lake) — domain-dependent");

        var lemmatizer = OpenNlpLemmatizer.fromModels(POS, LEMMAS, POS_DICT, LemmatizerOptions.defaults().keepOriginal(true));
        System.out.println("documented failure cases (recommended lowercase chain, keep_original: true)");
        System.out.printf("  %-10s %-12s %-9s %s%n", "written", "lemma", "findable", "why it is a known case");
        for (var entry : cases.entrySet()) {
            String written = entry.getKey();
            List<List<String>> groups =
                groupByPosition(tokens(lemmatizer.apply(new LowerCaseFilter(whitespace(written)))));
            List<String> group = groups.get(0);
            String lemma = group.get(group.size() - 1);
            // the surface form is indexed either because it was kept, or because it is its own lemma
            boolean findable = group.contains(written.toLowerCase(java.util.Locale.ROOT));
            assertTrue("the written form must stay searchable: " + written, findable);
            System.out.printf("  %-10s %-12s %-9s %s%n", written, lemma, "yes", entry.getValue());
        }
        System.out.println();
    }

    /**
     * Run one filter over the corpus with the setting off and on, assert the invariants, and print the
     * index cost plus the throughput of both.
     */
    private void measure(String label, List<String> fields,
                         Analyzer off, Analyzer on) throws Exception {
        List<Run> runs = new ArrayList<>();
        for (String field : fields) {
            runs.add(new Run(tokenize(field), terms(off.apply(whitespace(field))), tokens(on.apply(whitespace(field)))));
        }

        int inputTokens = 0;
        int emittedOff = 0;
        int emittedOn = 0;
        int rewritten = 0;
        List<String> rescuedSample = new ArrayList<>();
        for (Run run : runs) {
            inputTokens += run.input().size();
            emittedOff += run.off().size();
            emittedOn += run.on().size();

            // group the keep_original output by position: [original, lemma] for a rewrite, [lemma] otherwise
            List<List<String>> groups = groupByPosition(run.on());
            assertEquals(label + ": one position group per emitted token", run.off().size(), groups.size());
            for (int i = 0; i < groups.size(); i++) {
                List<String> group = groups.get(i);
                // (1) the lemma is unchanged by the setting — it is always the last token of its group
                assertEquals(label + ": lemma must not change", run.off().get(i), group.get(group.size() - 1));
                assertTrue(label + ": a position holds the lemma, plus the surface form when rewritten",
                    group.size() == 1 || group.size() == 2);
                if (group.size() == 2) {
                    rewritten++;
                    // (2) the extra token is the word as written, so a surface-form query still matches
                    assertEquals(label + ": the kept token must be the input form",
                        run.input().get(i), group.get(0));
                    if (rescuedSample.size() < 6) {
                        rescuedSample.add(group.get(0) + "→" + group.get(1));
                    }
                }
            }
        }
        // (3) exactly one extra posting per rewritten token, nothing more
        assertEquals(label + ": extra postings must equal rewrites", rewritten, emittedOn - emittedOff);

        Rates rates = throughput(fields, off, on, inputTokens);

        System.out.printf("%s%n", label);
        System.out.printf("  tokens in / out(off) / out(on) : %d / %d / %d%n", inputTokens, emittedOff, emittedOn);
        System.out.printf("  rewritten (doubled)            : %d  (%.1f%% of tokens)%n",
            rewritten, 100.0 * rewritten / inputTokens);
        System.out.printf("  index cost                     : +%.1f%% postings%n",
            100.0 * (emittedOn - emittedOff) / emittedOff);
        System.out.printf("  now also searchable as written : %s …%n", String.join(", ", rescuedSample));
        double delta = 100.0 * (rates.on() - rates.off()) / rates.off();
        String verdict = Math.abs(delta) <= rates.noiseFloor()
            ? String.format("within noise (±%.1f%%)", rates.noiseFloor())
            : String.format("%+.1f%%", delta);
        System.out.printf("  throughput off / on            : %,.0f / %,.0f tok/s  (%s)%n%n",
            rates.off(), rates.on(), verdict);
    }

    /** Best observed rate for each setting, plus how much the rounds disagreed with themselves. */
    private record Rates(double off, double on, double noiseFloor) {
    }

    /**
     * Steady-state tokens/sec for both settings, as {@code {off, on}}. The two are measured in
     * alternating rounds and each keeps its best round: measuring one fully and then the other lets JIT
     * and model-cache warmth drift between them, which showed up as the "on" variant timing *faster*
     * than "off" despite doing strictly more work.
     */
    private static Rates throughput(List<String> fields, Analyzer off, Analyzer on, int tokensPerPass)
            throws Exception {
        double bestOff = 0;
        double bestOn = 0;
        double worstOff = Double.MAX_VALUE;
        double worstOn = Double.MAX_VALUE;
        for (int round = 0; round < 6; round++) {
            double rateOff = onePass(fields, off, tokensPerPass);
            double rateOn = onePass(fields, on, tokensPerPass);
            if (round == 0) {
                continue;                                   // round 0 is warmup for both
            }
            bestOff = Math.max(bestOff, rateOff);
            bestOn = Math.max(bestOn, rateOn);
            worstOff = Math.min(worstOff, rateOff);
            worstOn = Math.min(worstOn, rateOn);
        }
        // how far a single setting's own rounds spread: a difference smaller than this says nothing
        double spread = Math.max(100.0 * (bestOff - worstOff) / bestOff, 100.0 * (bestOn - worstOn) / bestOn);
        return new Rates(bestOff, bestOn, spread);
    }

    /**
     * One timed measurement. The corpus is re-analysed until the window is long enough to be worth
     * timing: a fixed pass count would give the flat filter a ~10 ms window (all timer noise) and the
     * POS-aware one a much longer window, which is what made the earlier rounds disagree by ±29 %.
     */
    private static double onePass(List<String> fields, Analyzer analyzer, int tokensPerPass) throws Exception {
        long minNanos = 300_000_000L;
        long start = System.nanoTime();
        long elapsed;
        int passes = 0;
        do {
            for (String field : fields) {
                drain(analyzer.apply(whitespace(field)));
            }
            passes++;
            elapsed = System.nanoTime() - start;
        } while (elapsed < minNanos);
        return (double) passes * tokensPerPass / (elapsed / 1e9);
    }

    // --- helpers ---

    @FunctionalInterface
    private interface Analyzer {
        TokenStream apply(Tokenizer input);
    }

    private static List<String> readArticles() throws Exception {
        List<String> fields = new ArrayList<>();
        try (var paths = Files.list(ARTICLES)) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                // index the article as one field, the way a document body is analysed
                fields.add(String.join(" ", Files.readAllLines(p, StandardCharsets.UTF_8)).strip());
            }
        }
        return fields;
    }

    private static int countTokens(List<String> fields) {
        int n = 0;
        for (String f : fields) {
            n += f.split("\\s+").length;
        }
        return n;
    }

    /** The tokens as the filter sees them: whitespace-split and lower-cased, matching the analysed chain. */
    private static List<String> tokenize(String field) throws Exception {
        return terms(new LowerCaseFilter(whitespace(field)));
    }

    private static Tokenizer whitespace(String text) {
        Tokenizer tokenizer = new WhitespaceTokenizer();
        tokenizer.setReader(new StringReader(text));
        return tokenizer;
    }

    private static List<String> terms(TokenStream stream) throws Exception {
        List<String> out = new ArrayList<>();
        for (Token t : tokens(stream)) {
            out.add(t.term());
        }
        return out;
    }

    private static List<Token> tokens(TokenStream stream) throws Exception {
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        PositionIncrementAttribute posInc = stream.addAttribute(PositionIncrementAttribute.class);
        List<Token> out = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            out.add(new Token(term.toString(), posInc.getPositionIncrement()));
        }
        stream.end();
        stream.close();
        return out;
    }

    /** Split a token list into position groups: a {@code +0} token joins the group before it. */
    private static List<List<String>> groupByPosition(List<Token> tokens) {
        List<List<String>> groups = new ArrayList<>();
        for (Token t : tokens) {
            if (t.positionIncrement() == 0 && !groups.isEmpty()) {
                groups.get(groups.size() - 1).add(t.term());
            } else {
                List<String> group = new ArrayList<>();
                group.add(t.term());
                groups.add(group);
            }
        }
        return groups;
    }

    private static void drain(TokenStream stream) throws Exception {
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        stream.reset();
        while (stream.incrementToken()) {
            term.length();
        }
        stream.end();
        stream.close();
    }
}
