package io.johnsonlee.graphfold.compiler.javac;

import com.google.auto.service.AutoService;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Captures compiler-assigned anonymous class names and their physical source ranges during javac compilation.
 *
 * <p>This service provider uses only JDK APIs at runtime, so javac can load it without the engine's Kotlin
 * or JSON dependencies. Attach it before compilation starts; source files must be UTF-8 files inside
 * the supplied checkout root. Completed compilations write provenance JSON into the output directory.
 */
@AutoService(Plugin.class)
public final class GraphFoldOrigins implements Plugin {
    /**
     * Returns the name accepted by javac's {@code -Xplugin} option.
     *
     * @return the stable plugin name {@code GraphFoldOrigins}
     */
    @Override
    public String getName() {
        return "GraphFoldOrigins";
    }

    /**
     * Installs provenance capture on javac's compilation task.
     *
     * @param task the actual compilation task, before compilation starts
     * @param arguments three or four Base64 URL-encoded UTF-8 values: provenance directory, checkout root,
     *                  analysis run ID, and optional actual class-output directory, in that order
     * @throws IllegalArgumentException if the argument count or encoding is invalid, or the source root
     *                                  cannot be resolved
     */
    @Override
    public void init(JavacTask task, String... arguments) {
        if (arguments.length != 3 && arguments.length != 4) {
            throw new IllegalArgumentException("GraphFoldOrigins requires encoded output directory, source root, run ID and optional class-output directory");
        }
        attach(task, decode(arguments[0]), decode(arguments[1]),
            new String(Base64.getUrlDecoder().decode(arguments[2]), StandardCharsets.UTF_8),
            arguments.length == 4 ? decode(arguments[3]) : null);
    }

    /**
     * Registers capture on the actual compilation task with a new random analysis run ID.
     * No second compilation or parsing pass is performed.
     *
     * @param task the compilation task to observe, before compilation starts
     * @param outputDirectory the directory where provenance JSON will be written
     * @param sourceRoot the checkout root containing all compiled sources
     * @throws IllegalArgumentException if the source root cannot be resolved
     */
    public static void attach(JavacTask task, Path outputDirectory, Path sourceRoot) {
        attach(task, outputDirectory, sourceRoot, java.util.UUID.randomUUID().toString());
    }

    /**
     * Registers capture using the run ID shared with the consuming analysis invocation.
     * The listener observes attribution and class generation, then writes the compilation's provenance.
     *
     * @param task the compilation task to observe, before compilation starts
     * @param outputDirectory the directory where provenance JSON will be written
     * @param sourceRoot the checkout root containing all compiled sources
     * @param runId the identity used by the engine to validate these records
     * @throws IllegalArgumentException if the source root cannot be resolved
     */
    public static void attach(JavacTask task, Path outputDirectory, Path sourceRoot, String runId) {
        attach(task, outputDirectory, sourceRoot, runId, null);
    }

    /**
     * Captures origins and hashes the final compiler-emitted classes at compilation completion.
     * The class-output directory must be the actual destination supplied to javac; no directory is inferred.
     * Older overloads omit class hashes and cannot satisfy strict prepared-origin validation.
     *
     * @param task the actual compilation task
     * @param outputDirectory provenance output directory
     * @param sourceRoot checkout root containing compiled sources
     * @param runId compilation identity
     * @param classesDirectory actual javac class-output directory, or null for legacy unsealed evidence
     */
    public static void attach(JavacTask task, Path outputDirectory, Path sourceRoot, String runId, Path classesDirectory) {
        task.addTaskListener(new OriginListener(task, outputDirectory, sourceRoot, runId, classesDirectory));
    }

    private static Path decode(String value) {
        return Path.of(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
    }
}
