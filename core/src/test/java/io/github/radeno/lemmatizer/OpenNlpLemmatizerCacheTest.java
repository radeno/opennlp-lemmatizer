package io.github.radeno.lemmatizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** M1: the node-wide artifact cache loads a file once and reuses it, reloading only when it changes. */
public class OpenNlpLemmatizerCacheTest {

    @Test
    public void loadsOnceAndReusesForTheSameFile() throws Exception {
        Path file = Files.createTempFile("m1-cache", ".bin");
        Files.writeString(file, "payload");
        var cache = new ConcurrentHashMap<String, OpenNlpLemmatizer.Cached<Object>>();
        AtomicInteger loads = new AtomicInteger();

        Object first = OpenNlpLemmatizer.loadShared(cache, file, p -> { loads.incrementAndGet(); return new Object(); });
        Object second = OpenNlpLemmatizer.loadShared(cache, file, p -> { loads.incrementAndGet(); return new Object(); });

        assertEquals("loader runs once for an unchanged file", 1, loads.get());
        assertSame("the cached instance is reused (one copy on the node)", first, second);
        assertEquals(1, cache.size());
    }

    @Test
    public void reloadsWhenTheFileChanges() throws Exception {
        Path file = Files.createTempFile("m1-cache", ".bin");
        Files.writeString(file, "v1");
        var cache = new ConcurrentHashMap<String, OpenNlpLemmatizer.Cached<Object>>();
        AtomicInteger loads = new AtomicInteger();

        Object first = OpenNlpLemmatizer.loadShared(cache, file, p -> { loads.incrementAndGet(); return new Object(); });

        // change content + bump the modified time so (size, lastModified) differs
        Files.writeString(file, "v2-bigger");
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));

        Object second = OpenNlpLemmatizer.loadShared(cache, file, p -> { loads.incrementAndGet(); return new Object(); });

        assertEquals("a re-fetched file is reloaded, not served stale", 2, loads.get());
        assertEquals("the cache stays bounded to one live entry per path", 1, cache.size());
        org.junit.Assert.assertNotSame(first, second);
    }
}
