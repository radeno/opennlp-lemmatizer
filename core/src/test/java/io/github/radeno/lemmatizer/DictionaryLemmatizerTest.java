package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertArrayEquals;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import org.junit.Test;

public class DictionaryLemmatizerTest {

    @Test
    public void looksUpCaseInsensitivelyAndLeavesUnknownUnchanged() throws Exception {
        Path dict = Files.createTempFile("dict", ".txt");
        Files.writeString(dict, "je\tbyť\nlese\tles\nstromy\tstrom\n");

        DictionaryLemmatizer lemmatizer = DictionaryLemmatizer.fromFile(dict);
        // "Je" matches case-insensitively; "v" and "xyz" are unknown -> unchanged
        List<String> out = lemmatize(lemmatizer, "Je v lese stromy xyz");
        assertArrayEquals(new String[] { "byť", "v", "les", "strom", "xyz" }, out.toArray(new String[0]));
    }

    private static List<String> lemmatize(DictionaryLemmatizer lemmatizer, String text) throws Exception {
        Tokenizer tokenizer = new WhitespaceTokenizer();
        tokenizer.setReader(new StringReader(text));
        TokenStream ts = lemmatizer.apply(tokenizer);
        CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
        List<String> result = new ArrayList<>();
        ts.reset();
        while (ts.incrementToken()) {
            result.add(term.toString());
        }
        ts.end();
        ts.close();
        return result;
    }
}
