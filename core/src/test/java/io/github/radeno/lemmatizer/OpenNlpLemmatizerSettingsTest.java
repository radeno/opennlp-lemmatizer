package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Setting parsing and validation — pure, no models needed. Every case here fails fast in the factory,
 * before any model file is touched.
 */
public class OpenNlpLemmatizerSettingsTest {

    private static final Path CONFIG = Paths.get("/nonexistent-config");

    // --- pos_format ---

    @Test
    public void pennIsTheDefaultAndTheOnlyNonNativeValue() {
        assertFalse(OpenNlpLemmatizer.isNativePosFormat("f", null));
        assertFalse(OpenNlpLemmatizer.isNativePosFormat("f", ""));
        assertFalse(OpenNlpLemmatizer.isNativePosFormat("f", "  "));
        assertFalse(OpenNlpLemmatizer.isNativePosFormat("f", "penn"));
        assertFalse(OpenNlpLemmatizer.isNativePosFormat("f", "PENN"));
    }

    @Test
    public void nativeAndCustomBothKeepTheModelsOwnTagset() {
        assertTrue(OpenNlpLemmatizer.isNativePosFormat("f", "native"));
        assertTrue(OpenNlpLemmatizer.isNativePosFormat("f", "Native"));
        assertTrue(OpenNlpLemmatizer.isNativePosFormat("f", "custom"));
        assertTrue(OpenNlpLemmatizer.isNativePosFormat("f", "CUSTOM"));
    }

    /**
     * The regression this guards: an unrecognised value used to fall through to {@code penn} silently,
     * which turns a native-tagged dictionary into 100 % model fallback. {@code ud} is the trap — it is a
     * real {@code POSTagFormat} constant, so it looks valid to anyone with a UD/UPOS model.
     */
    @Test
    public void anUnknownPosFormatFailsFastInsteadOfSilentlyMeaningPenn() {
        for (String bad : new String[] { "ud", "nativ", "penn ", "upos", "true" }) {
            IllegalArgumentException e = assertThrows(bad, IllegalArgumentException.class,
                () -> OpenNlpLemmatizer.isNativePosFormat("my_filter", bad));
            assertTrue(e.getMessage(), e.getMessage().contains("my_filter"));
            assertTrue(e.getMessage(), e.getMessage().contains(OpenNlpLemmatizer.POS_FORMAT_SETTING));
            assertTrue(e.getMessage(), e.getMessage().contains("[penn, native, custom]"));
        }
    }

    // --- required settings ---

    @Test
    public void posModelIsAlwaysRequired() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> OpenNlpLemmatizer.fromConfig("f", CONFIG, null, "lemmas.bin"));
        assertTrue(e.getMessage(), e.getMessage().contains(OpenNlpLemmatizer.POS_MODEL_SETTING));
    }

    @Test
    public void lemmatizerModelIsRequiredWhileTheModelFallbackIsOn() {
        assertThrows(IllegalArgumentException.class,
            () -> OpenNlpLemmatizer.fromConfig("f", CONFIG, "pos.bin", null));
        assertThrows(IllegalArgumentException.class,
            () -> OpenNlpLemmatizer.fromConfig("f", CONFIG, "pos.bin", null, "dict.txt", LemmatizerOptions.defaults()));
        // no dictionary to fall back on -> the model stays required even with model_fallback: false
        assertThrows(IllegalArgumentException.class,
            () -> OpenNlpLemmatizer.fromConfig("f", CONFIG, "pos.bin", null, null, LemmatizerOptions.defaults().modelFallback(false)));
    }

    @Test
    public void aLemmatizerModelIsRequiredWhenThereIsNoDictionary() {
        Path pos = Paths.get("pos.bin");
        assertThrows(IllegalArgumentException.class,
            () -> OpenNlpLemmatizer.fromModels(pos, null, null, LemmatizerOptions.defaults()));
        assertThrows(IllegalArgumentException.class,
            () -> OpenNlpLemmatizer.fromModels(pos, pos, null, LemmatizerOptions.defaults().modelFallback(false)));
    }

    @Test
    public void settingNamesAreDeclaredOnceInCore() {
        assertEquals("pos_model", OpenNlpLemmatizer.POS_MODEL_SETTING);
        assertEquals("lemmatizer_model", OpenNlpLemmatizer.LEMMATIZER_MODEL_SETTING);
        assertEquals("pos_format", OpenNlpLemmatizer.POS_FORMAT_SETTING);
        assertEquals("model_fallback", OpenNlpLemmatizer.MODEL_FALLBACK_SETTING);
    }
}
