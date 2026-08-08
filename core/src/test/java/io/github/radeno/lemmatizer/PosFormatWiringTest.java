package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

/**
 * {@code pos_format} reaching {@code opennlp_lemmatizer}, the filter with no dictionary.
 *
 * <p>It used to be read only on the way to {@code pos_dictionary_lemmatizer}, so on this filter the
 * setting sat there inert and an unknown value was not even rejected — the exact failure mode
 * {@link OpenNlpLemmatizerSettingsTest} guards against everywhere else. The setting is meaningful here
 * because it swaps the tagger, and the tagger's output is both the POS half of every
 * {@code (word, POS)} pair the MaxEnt lemmatizer receives and the token's {@code type}.
 */
public class PosFormatWiringTest {

    private static final String DIR = System.getProperty("opennlp.models.dir", "models");
    private static final Path POS = Paths.get(DIR, "sk-pos.bin");
    private static final Path LEMMAS = Paths.get(DIR, "sk-lemmas.bin");

    private static final LemmatizerOptions.BooleanSettings NO_FLAGS = (name, fallback) -> fallback;

    /** Settings reader over the three values these cases need. */
    private static UnaryOperator<String> settings(String posFormat) {
        return key -> switch (key) {
            case OpenNlpLemmatizer.POS_MODEL_SETTING -> "sk-pos.bin";
            case OpenNlpLemmatizer.LEMMATIZER_MODEL_SETTING -> "sk-lemmas.bin";
            case OpenNlpLemmatizer.POS_FORMAT_SETTING -> posFormat;
            default -> null;
        };
    }

    @Test
    public void anUnknownPosFormatIsRefusedOnTheModelOnlyFilterToo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> LemmatizerFilters.opennlp("my_filter", Paths.get("/nonexistent-config"),
                settings("ud"), NO_FLAGS));
        assertTrue(e.getMessage(), e.getMessage().contains("my_filter"));
        assertTrue(e.getMessage(), e.getMessage().contains(OpenNlpLemmatizer.POS_FORMAT_SETTING));
        assertTrue(e.getMessage(), e.getMessage().contains("[penn, native, custom]"));
    }

    /**
     * Rejecting a bad value is not enough — a good one has to actually arrive. Penn coercion and the
     * model's own tagset disagree on Slovak, so the {@code type} attribute is where the difference shows.
     */
    @Test
    public void nativeReachesTheTaggerAndChangesTheTagsItEmits() throws Exception {
        assumeTrue("need sk-pos.bin + sk-lemmas.bin under " + DIR,
            Files.isReadable(POS) && Files.isReadable(LEMMAS));
        Path configDir = configDirOverModels();

        List<String> penn = types(LemmatizerFilters.opennlp("f", configDir, settings(null), NO_FLAGS));
        List<String> nativeTags = types(LemmatizerFilters.opennlp("f", configDir, settings("native"), NO_FLAGS));

        assertNotEquals("native must not collapse to the Penn tagset", penn, nativeTags);
    }

    /**
     * The filters read models from {@code <configDir>/opennlp/}, while the test models live in their own
     * directory — so point a config dir at them with a symlink. That the path check accepts it is the
     * point of resolving on the normalized path rather than the real one; see {@link ModelPathsTest}.
     */
    private static Path configDirOverModels() throws Exception {
        Path configDir = Files.createTempDirectory("config");
        Files.createSymbolicLink(configDir.resolve(OpenNlpLemmatizer.MODELS_DIRECTORY),
            Paths.get(DIR).toAbsolutePath());
        return configDir;
    }

    private static List<String> types(LemmatizerFilter lemmatizer) throws Exception {
        Tokenizer source = new WhitespaceTokenizer();
        source.setReader(new StringReader("Hostia prišli do Bratislavy"));
        TokenStream stream = lemmatizer.apply(source);
        TypeAttribute type = stream.addAttribute(TypeAttribute.class);
        List<String> types = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            types.add(type.type());
        }
        stream.end();
        stream.close();
        return types;
    }
}
