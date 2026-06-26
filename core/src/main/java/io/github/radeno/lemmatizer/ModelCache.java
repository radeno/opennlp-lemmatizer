package io.github.radeno.lemmatizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Node-wide cache for the heavy, immutable artifacts a token-filter factory loads — OpenNLP models, the
 * FST dictionary, the flat {@link DictionaryLemmatizer} {@code CharArrayMap}.
 *
 * <p>A token-filter factory is instantiated per <em>(index, filter)</em>, so without sharing the same
 * files are parsed into a fresh copy each time (the same dictionary easily ends up loaded several times
 * on one node). The plugin is loaded once per node in its own classloader, so the caches the callers
 * hold in static fields are shared across every index on the node. Each artifact is keyed by absolute
 * path and revalidated on {@code (size, lastModified)}, so a re-fetched file is reloaded rather than
 * served stale, and the cache stays bounded to one live entry per path. The cached values are immutable
 * and safe to share; per-stream wrappers (e.g. {@code LemmatizerME}) are created later, per stream.
 */
final class ModelCache {

    private ModelCache() {
    }

    /** A cached value tagged with the {@code (size, lastModified)} it was loaded from, for revalidation. */
    record Cached<V>(long size, long lastModified, V value) {
    }

    /**
     * Return the cached artifact for {@code path}, loading it via {@code loader} on a miss or when the
     * file's {@code (size, lastModified)} changed since it was cached. A cold race may load twice; that
     * is wasted work only — the loaded artifacts are equivalent and the map converges.
     */
    static <V> V loadShared(ConcurrentHashMap<String, Cached<V>> cache, Path path, Function<Path, V> loader) {
        String key = path.toAbsolutePath().normalize().toString();
        long size;
        long lastModified;
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            size = attrs.size();
            lastModified = attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stat " + path, e);
        }
        Cached<V> hit = cache.get(key);
        if (hit != null && hit.size() == size && hit.lastModified() == lastModified) {
            return hit.value();
        }
        V value = loader.apply(path);
        cache.put(key, new Cached<>(size, lastModified, value));
        return value;
    }
}
