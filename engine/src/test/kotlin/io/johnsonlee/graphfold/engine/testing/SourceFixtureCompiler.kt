package io.johnsonlee.graphfold.engine.testing

import com.sun.source.util.JavacTask
import io.johnsonlee.graphfold.compiler.javac.GraphFoldOrigins
import io.johnsonlee.graphfold.engine.inputs.archive.InputProject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.JavaCompiler
import javax.tools.ToolProvider
import kotlin.io.path.extension

/** Test-only source compiler: fixtures never select or execute Gradle or Maven builds. */
internal class SourceFixtureCompiler(private val timeout: Duration) {
    fun compile(checkout: Path, output: Path): InputProject {
        val roots = productionRoots(checkout)
        val files = roots.flatMap(::regularFiles)
        val sources = files.filter { it.extension == "java" }
        val kotlin = files.filter { it.extension == "kt" }
        checkBuild(sources.isNotEmpty() || kotlin.isNotEmpty(), "No supported production JVM sources found")
        checkBuild(sources.none { it.fileName.toString() == "module-info.java" },
            "Java module compilation requires a build-system adapter")
        val classes = Files.createDirectories(output.resolve("classes"))
        val dependencies = if (kotlin.isEmpty()) emptyList() else {
            KotlinFixtureCompiler(timeout, emptySet()).compile(checkout, output, classes, kotlin + sources)
        }
        if (sources.isNotEmpty()) {
            compileJava(sources, classes, dependencies, output.resolve("origins"), checkout, CompilerOriginPlugin.runId(output))
        }
        return InputProject(classes, roots, dependencies, output.resolve("origins"), CompilerOriginPlugin.runId(output),
            roots.associateWith { listOf(classes) })
    }
}

/** Runs javac with provenance; absence of a system compiler is an explicit build failure. */
internal fun compileJava(
    sources: List<Path>, classes: Path, dependencies: List<Path>, origins: Path, root: Path, runId: String,
    systemCompiler: JavaCompiler? = ToolProvider.getSystemJavaCompiler(),
) {
    val compiler = checkNotNull(systemCompiler) { "A JDK with javac is required" }
    val diagnostics = DiagnosticCollector<JavaFileObject>()
    compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
        val units = manager.getJavaFileObjectsFromPaths(sources)
        val options = listOf("-g", "-proc:none", "-d", classes.toString(), "-classpath",
            (listOf(classes) + dependencies).joinToString(java.io.File.pathSeparator))
        val compilationTask: JavaCompiler.CompilationTask =
            compiler.getTask(null, manager, diagnostics, options, null, units)
        val task = compilationTask as JavacTask
        GraphFoldOrigins.attach(task, origins, root, runId, classes)
        check(task.call()) {
            "Java compilation failed: ${diagnostics.diagnostics.joinToString("; ") { it.getMessage(null) }}"
        }
    }
}

internal fun regularFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
    paths.filter { Files.isRegularFile(it) }.sorted().toList()
}

/** Source-only projects have one root; build adapters enumerate their declared production modules. */
internal fun productionRoots(module: Path): List<Path> = listOf("src/main/java", "src/main/kotlin")
    .map(module::resolve).filter(Files::isDirectory)

internal fun checkBuild(condition: Boolean, message: String) {
    check(condition) { message }
}
