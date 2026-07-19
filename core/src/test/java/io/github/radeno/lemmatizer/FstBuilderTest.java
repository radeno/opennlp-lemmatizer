package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.Util;

import org.junit.Test;

/** The shared FST builder: streaming vs. buffered fallback, first-wins dedup, BOM stripping. */
public class FstBuilderTest {

    /** A minimal {@code key<TAB>output} parser, independent of any dictionary format. */
    private static final FstBuilder.LineParser PARSER = raw -> {
        String line = FstBuilder.stripBom(raw);
        int tab = line.indexOf('\t');
        if (tab <= 0) {
            return null;
        }
        return new FstBuilder.Entry(
            line.substring(0, tab).getBytes(StandardCharsets.UTF_8),
            line.substring(tab + 1).getBytes(StandardCharsets.UTF_8));
    };

    private static String get(FstBuilder.Result r, String key) throws IOException {
        BytesRef out = Util.get(r.fst(), new BytesRef(key));
        return out == null ? null : out.utf8ToString();
    }

    @Test
    public void streamsAnAlreadySortedFileKeepingTheFirstOfDuplicateKeys() throws Exception {
        // unsigned-byte order: '*'(0x2a) < 'a' < 'j' < 't'; the "je" key repeats -> first wins
        Path f = Files.createTempFile("fstbuilder-sorted", ".txt");
        Files.writeString(f, "*\tstar\nauto\tcar\nje\tfirst\nje\tSECOND\ntri\tthree\n");

        FstBuilder.Result r = FstBuilder.streamBuild(f, PARSER);
        assertEquals(4, r.size());
        assertEquals("car", get(r, "auto"));
        assertEquals("first", get(r, "je"));
        assertNull(get(r, "missing"));
    }

    @Test
    public void streamBuildRejectsAnOutOfOrderFile() throws Exception {
        Path f = Files.createTempFile("fstbuilder-unsorted", ".txt");
        Files.writeString(f, "tri\tthree\nauto\tcar\n"); // 't' before 'a'
        assertThrows(FstBuilder.UnsortedException.class, () -> FstBuilder.streamBuild(f, PARSER));
    }

    @Test
    public void buildFallsBackToBufferedForAnUnsortedFile() throws Exception {
        Path f = Files.createTempFile("fstbuilder-fallback", ".txt");
        Files.writeString(f, "tri\tthree\nauto\tcar\nauto\tSECOND\n"); // unsorted + duplicate

        FstBuilder.Result r = FstBuilder.build(f, PARSER);
        assertEquals(2, r.size());
        assertEquals("car", get(r, "auto")); // first wins even through the buffered path
        assertEquals("three", get(r, "tri"));
    }

    @Test
    public void streamAndBufferedAgreeOnSortedInput() throws Exception {
        Path f = Files.createTempFile("fstbuilder-agree", ".txt");
        Files.writeString(f, "auto\tcar\nbrno\tBrno\nje\tfirst\ntri\tthree\n");

        FstBuilder.Result stream = FstBuilder.streamBuild(f, PARSER);
        FstBuilder.Result buffered = FstBuilder.bufferedBuild(f, PARSER);
        assertEquals(stream.size(), buffered.size());
        for (String k : new String[] { "auto", "brno", "je", "tri" }) {
            assertEquals(get(stream, k), get(buffered, k));
        }
    }

    @Test
    public void stripsALeadingBom() throws Exception {
        Path f = Files.createTempFile("fstbuilder-bom", ".txt");
        Files.writeString(f, "﻿auto\tcar\n"); // BOM before the first key

        FstBuilder.Result r = FstBuilder.build(f, PARSER);
        assertEquals("car", get(r, "auto")); // BOM stripped, not part of the key
    }

    @Test
    public void skipsBlankAndMalformedLines() throws Exception {
        Path f = Files.createTempFile("fstbuilder-skip", ".txt");
        Files.writeString(f, "auto\tcar\n\nno-tab-here\ntri\tthree\n");

        FstBuilder.Result r = FstBuilder.build(f, PARSER);
        assertEquals(2, r.size());
    }
}
