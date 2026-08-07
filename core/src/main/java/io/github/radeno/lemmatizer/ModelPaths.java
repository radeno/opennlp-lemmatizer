package io.github.radeno.lemmatizer;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Resolves the model and dictionary files named in a token filter's settings, refusing any name that
 * would land outside {@code <config>/opennlp/}.
 *
 * <p>The file names arrive from index analyzer settings, so they are attacker-controlled in the sense
 * that anyone who may create an index or update its settings chooses them. Plain
 * {@code dir.resolve(name)} honours both {@code ../} segments and absolute names, so
 * {@code dictionary: "../../../../etc/passwd"} reads a file the node's account can reach but the
 * operator never meant to expose — and since a dictionary is parsed as {@code form<TAB>lemma} and its
 * contents come back out through {@code _analyze}, that is disclosure, not just a failed load.
 *
 * <p>OpenSearch closed exactly this hole in its own {@code resolveAnalyzerPath} in 3.8.0
 * (opensearch-project/OpenSearch#22094). This plugin never went through that method — each wrapper
 * hands {@code core} its node's config directory and the resolving happens here — so the fix has to
 * live here too.
 * Keeping it in {@code core} rather than in either wrapper is deliberate: Elasticsearch offers no
 * equivalent guarantee for plugin-resolved paths, and a node still on OpenSearch 3.7 does not have the
 * engine-side fix, so the check has to be ours to hold on every node either plugin runs on.
 *
 * <p>The comparison is made on the {@link Path#normalize() normalized} path and deliberately does not
 * call {@code toRealPath()}. Resolving symlinks would reject the legitimate and common setup where
 * {@code config/opennlp/} — or a file in it — is a link to models kept on a separate volume. What is
 * being rejected is traversal spelled out in the setting itself, which is the actual attack.
 */
final class ModelPaths {

    private ModelPaths() {
    }

    /**
     * Resolves {@code fileName} inside {@code <configDir>/opennlp/}.
     *
     * @param filterName the token-filter name, used in the error message
     * @param setting    the setting the name came from, e.g. {@code dictionary}
     * @param configDir  the node's config directory
     * @param fileName   the file name from the setting; must not be blank
     * @return the resolved, normalized path, guaranteed to sit inside {@code <configDir>/opennlp/}
     * @throws IllegalArgumentException if the name escapes that directory or is not a valid path
     */
    static Path resolve(String filterName, String setting, Path configDir, String fileName) {
        Path modelsDir = configDir.resolve(OpenNlpLemmatizer.MODELS_DIRECTORY).normalize();
        Path resolved;
        try {
            resolved = modelsDir.resolve(fileName).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("[" + filterName + "] '" + setting + "' is not a valid file"
                + " name: '" + fileName + "'", e);
        }
        // An absolute fileName replaces modelsDir outright rather than extending it, so this one check
        // covers both "../" traversal and an absolute path.
        if (!resolved.startsWith(modelsDir)) {
            throw new IllegalArgumentException("[" + filterName + "] '" + setting + "' must name a file"
                + " inside the node's '" + OpenNlpLemmatizer.MODELS_DIRECTORY + "' config directory;"
                + " '" + fileName + "' resolves outside it");
        }
        return resolved;
    }
}
