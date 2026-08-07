package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Model and dictionary file names come from index analyzer settings, so they must not be able to name
 * a file outside {@code <config>/opennlp/}. These cases are pure — nothing is opened, the rejection
 * happens while the path is still being worked out.
 */
public class ModelPathsTest {

    private static final Path CONFIG = Paths.get("/nonexistent-config");
    private static final Path MODELS = CONFIG.resolve(OpenNlpLemmatizer.MODELS_DIRECTORY);

    private static Path resolve(String fileName) {
        return ModelPaths.resolve("f", DictionaryLemmatizer.DICTIONARY_SETTING, CONFIG, fileName);
    }

    private static IllegalArgumentException rejects(String fileName) {
        return assertThrows(fileName, IllegalArgumentException.class, () -> resolve(fileName));
    }

    // --- what must keep working ---

    @Test
    public void aPlainNameLandsInTheModelsDirectory() {
        assertEquals(MODELS.resolve("sk-mte.txt"), resolve("sk-mte.txt"));
    }

    @Test
    public void subDirectoriesAreFineForPeopleWhoSortModelsByLanguage() {
        assertEquals(MODELS.resolve("sk").resolve("sk-mte.txt"), resolve("sk/sk-mte.txt"));
    }

    /**
     * The check is on the normalized path, not on whether {@code ..} appears in the string. A name that
     * steps out and back in stays inside the directory, so it is allowed — banning the characters
     * outright would reject a legitimate name for no gain.
     */
    @Test
    public void traversalThatComesBackInsideIsStillInside() {
        assertEquals(MODELS.resolve("sk-mte.txt"), resolve("sk/../sk-mte.txt"));
        assertEquals(MODELS.resolve("sk-mte.txt"), resolve("./sk-mte.txt"));
    }

    // --- what must be refused ---

    @Test
    public void relativeTraversalOutOfTheModelsDirectoryIsRefused() {
        rejects("../elasticsearch.yml");
        rejects("../../../../etc/passwd");
        rejects("sk/../../opensearch.yml");
    }

    /**
     * An absolute name is the quieter half of the same hole: {@code dir.resolve("/etc/passwd")} does not
     * extend {@code dir}, it replaces it, so no {@code ..} is needed to escape.
     */
    @Test
    public void anAbsoluteNameIsRefused() {
        rejects("/etc/passwd");
    }

    @Test
    public void theMessageNamesTheFilterAndTheSettingThatCarriedTheName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ModelPaths.resolve("my_filter", OpenNlpLemmatizer.POS_MODEL_SETTING, CONFIG, "../x.bin"));
        assertTrue(e.getMessage(), e.getMessage().contains("my_filter"));
        assertTrue(e.getMessage(), e.getMessage().contains(OpenNlpLemmatizer.POS_MODEL_SETTING));
        assertTrue(e.getMessage(), e.getMessage().contains("../x.bin"));
    }

    // --- and that every filter actually goes through it ---

    /**
     * The helper is only worth having if all three factories use it, so each is driven through its own
     * public entry point. Every one of these fails on the path, before a file is opened — which is also
     * why a config directory that does not exist is enough to run them.
     */
    @Test
    public void allThreeFiltersRefuseATraversingName() {
        String escape = "../../../../etc/passwd";

        assertRefusedForPath("dictionary_lemmatizer", DictionaryLemmatizer.DICTIONARY_SETTING,
            () -> DictionaryLemmatizer.fromConfig("f", CONFIG, escape, LemmatizerOptions.defaults()));

        assertRefusedForPath("opennlp_lemmatizer pos_model", OpenNlpLemmatizer.POS_MODEL_SETTING,
            () -> OpenNlpLemmatizer.fromConfig("f", CONFIG, escape, "lemmas.bin"));

        assertRefusedForPath("opennlp_lemmatizer lemmatizer_model", OpenNlpLemmatizer.LEMMATIZER_MODEL_SETTING,
            () -> OpenNlpLemmatizer.fromConfig("f", CONFIG, "pos.bin", escape));

        assertRefusedForPath("pos_dictionary_lemmatizer dictionary", DictionaryLemmatizer.DICTIONARY_SETTING,
            () -> OpenNlpLemmatizer.fromConfig("f", CONFIG, "pos.bin", "lemmas.bin", escape,
                LemmatizerOptions.defaults()));
    }

    /**
     * Asserts the failure is the path check and not some other validation that happens to mention the
     * same setting — {@code "requires a 'dictionary' setting"} would satisfy a bare name check.
     */
    private static void assertRefusedForPath(String what, String setting, Runnable call) {
        IllegalArgumentException e =
            assertThrows(what, IllegalArgumentException.class, call::run);
        assertTrue(what + ": " + e.getMessage(), e.getMessage().contains(setting));
        assertTrue(what + ": " + e.getMessage(), e.getMessage().contains("resolves outside it"));
    }
}
