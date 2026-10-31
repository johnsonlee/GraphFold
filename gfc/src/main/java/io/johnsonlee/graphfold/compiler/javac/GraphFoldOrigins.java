package io.johnsonlee.graphfold.compiler.javac;

import com.google.auto.service.AutoService;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/** JDK-only javac plugin; isolated from the engine's Kotlin and JSON runtime dependencies. */
@AutoService(Plugin.class)
public final class GraphFoldOrigins implements Plugin {
    @Override
    public String getName() {
        return "GraphFoldOrigins";
    }

    /** Arguments are Base64 URL-encoded UTF-8 output directory, checkout root and run ID, in that order. */
    @Override
    public void init(JavacTask task, String... arguments) {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("GraphFoldOrigins requires encoded output directory, source root and run ID");
        }
        attach(task, decode(arguments[0]), decode(arguments[1]), new String(Base64.getUrlDecoder().decode(arguments[2]), StandardCharsets.UTF_8));
    }

    /** Register on the actual compilation task, before calling it; no second compilation or parsing pass. */
    public static void attach(JavacTask task, Path outputDirectory, Path sourceRoot) {
        attach(task, outputDirectory, sourceRoot, java.util.UUID.randomUUID().toString());
    }

    public static void attach(JavacTask task, Path outputDirectory, Path sourceRoot, String runId) {
        task.addTaskListener(new OriginListener(task, outputDirectory, sourceRoot, runId));
    }

    private static Path decode(String value) {
        return Path.of(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
    }
}
