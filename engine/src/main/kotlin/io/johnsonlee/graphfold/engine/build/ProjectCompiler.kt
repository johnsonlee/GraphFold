package io.johnsonlee.graphfold.engine.build

import com.sun.source.util.JavacTask
import io.johnsonlee.graphfold.engine.origin.javac.GraphFoldOrigins
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.extension

/** Classes are copied outside the checkout; dependency paths refer to resolved build inputs. */
data class CompiledProject(
    val classes: Path, val sourceRoots: List<Path>, val dependencies: List<Path>, val originDirectory: Path? = null,
    val originRunId: String? = null, val sourceOutputs: Map<Path, List<Path>> = emptyMap(),
)

class BuildFailure(message: String) : IllegalStateException(message)

/** Build adapters export actual outputs and dependencies instead of guessing global cache entries. */
class ProjectCompiler(
    private val timeout: Duration,
    private val buildEnvironmentAllowlist: Set<String> = io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT,
) {
    init {
        require(!timeout.isZero && !timeout.isNegative) { "Build timeout must be positive" }
    }

    fun compile(checkout: Path, output: Path): CompiledProject {
        Files.createDirectories(output)
        checkBuild(Files.list(output).use { it.findAny().isEmpty }, "Build output directory must be empty: $output")
        return when {
            Files.exists(checkout.resolve("gradlew")) -> GradleBuildAdapter(timeout, buildEnvironmentAllowlist).compile(checkout, output)
            Files.exists(checkout.resolve("pom.xml")) -> MavenBuildAdapter(timeout, buildEnvironmentAllowlist).compile(checkout, output)
            Files.exists(checkout.resolve("build.gradle")) || Files.exists(checkout.resolve("build.gradle.kts")) ->
                throw BuildFailure("Gradle projects require a checked-in wrapper")
            else -> compileSources(checkout, output)
        }
    }

    private fun compileSources(checkout: Path, output: Path): CompiledProject {
        val roots = productionRoots(checkout)
        val files = roots.flatMap(::regularFiles)
        val sources = files.filter { it.extension == "java" }
        val kotlin = files.filter { it.extension == "kt" }
        checkBuild(sources.isNotEmpty() || kotlin.isNotEmpty(), "No supported production JVM sources found")
        checkBuild(sources.none { it.fileName.toString() == "module-info.java" },
            "Java module compilation requires a build-system adapter")
        val classes = Files.createDirectories(output.resolve("classes"))
        val dependencies = if (kotlin.isEmpty()) emptyList() else {
            KotlinBuildAdapter(timeout, buildEnvironmentAllowlist).compile(checkout, output, classes, kotlin + sources)
        }
        if (sources.isNotEmpty()) {
            compileJava(sources, classes, dependencies, output.resolve("origins"), checkout, CompilerOriginPlugin.runId(output))
        }
        return CompiledProject(classes, roots, dependencies, output.resolve("origins"), CompilerOriginPlugin.runId(output),
            roots.associateWith { listOf(classes) })
    }

    private fun compileJava(
        sources: List<Path>, classes: Path, dependencies: List<Path>, origins: Path, root: Path, runId: String,
    ) {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: throw BuildFailure("A JDK with javac is required")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
            val units = manager.getJavaFileObjectsFromPaths(sources)
            val options = listOf("-g", "-proc:none", "-d", classes.toString(), "-classpath",
                (listOf(classes) + dependencies).joinToString(java.io.File.pathSeparator))
            val task = compiler.getTask(null, manager, diagnostics, options, null, units) as JavacTask
            GraphFoldOrigins.attach(task, origins, root, runId)
            if (!task.call()) {
                throw BuildFailure("Java compilation failed: ${diagnostics.diagnostics.joinToString("; ") { it.getMessage(null) }}")
            }
        }
    }
}

internal fun regularFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
    paths.filter { Files.isRegularFile(it) }.sorted().toList()
}

/** Source-only projects have one root; build adapters enumerate their declared production modules. */
internal fun productionRoots(module: Path): List<Path> = listOf("src/main/java", "src/main/kotlin")
    .map(module::resolve).filter(Files::isDirectory)

internal fun collectClasses(output: Path, directories: List<Path>): Path {
    val classes = Files.createDirectories(output.resolve("classes"))
    var count = 0
    directories.distinct().filter(Files::isDirectory).forEach { root ->
        regularFiles(root).filter { it.extension == "class" }.forEach { file ->
            val relative = root.relativize(file)
            val target = classes.resolve(relative)
            if (Files.exists(target)) throw BuildFailure("Duplicate class in project outputs: $relative")
            Files.createDirectories(target.parent)
            Files.copy(file, target)
            count++
        }
    }
    if (count == 0) throw BuildFailure("Build produced no supported classes")
    return classes
}


internal fun checkBuild(condition: Boolean, message: String) {
    if (!condition) throw BuildFailure(message)
}
