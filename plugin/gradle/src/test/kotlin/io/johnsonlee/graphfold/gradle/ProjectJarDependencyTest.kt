package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.DiagnosticCode
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectJarDependencyTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @EnumSource(Variant::class)
    fun `real Gradle jar variant remains inventoried but does not duplicate graph classes`(variant: Variant) {
        val repository = repository(resource = true, variant)
        val revision = commit(repository)
        val cache = temporary.resolve("cache")
        val analyzer = PackagedModelAnalyzer(cache)
        val result = analyzer.analyze(AnalysisRequest(repository.toString(), revision, revision,
            "folds:\n  - select: 'MATCH (cs:CallSite) RETURN cs'\n    value: false\n"))
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        val manifest = Files.walk(cache.resolve("snapshots")).use { paths ->
            paths.filter { it.fileName.toString() == "inputs.json" }.findFirst().orElseThrow()
        }
        val scopes = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(manifest.toFile())["scopes"]
        val expectedScopes = if (variant == Variant.APPLICATION) setOf(":app:jar") else setOf(":app:jar", ":library:jar")
        assertEquals(expectedScopes, scopes.map { it["id"].asText() }.toSet())
        scopes.forEach { exported ->
            assertTrue(exported["sourceOutputs"].properties().any {
                it.value.any { output -> Files.isDirectory(Path.of(output.asText())) }
            })
            assertTrue(Files.isRegularFile(Path.of(exported["archive"].asText())))
        }
        val scope = scopes.single { it["id"].asText() == ":app:jar" }
        val producers = scope["projectArtifacts"].properties().associate { it.key to it.value }
        val library = producers.keys.single { java.nio.file.Path.of(it).fileName.toString().startsWith("library") }
        val outputs = producers.getValue(library).map { java.nio.file.Path.of(it.asText()) }
        java.util.jar.JarFile(library).use { jar ->
            val compilerClass = outputs.map { it.resolve("sample/Library.class") }.single(Files::isRegularFile)
            assertTrue(jar.getInputStream(jar.getJarEntry("sample/Library.class")).use { it.readBytes() }
                .contentEquals(Files.readAllBytes(compilerClass)))
            assertTrue(jar.getJarEntry("messages.properties") != null)
        }
        val prepared = StaticInputSnapshots.prepare(manifest, manifest, repository, repository,
            temporary.resolve("prepared")).first
        assertEquals(expectedScopes, prepared.scopes.keys)
        prepared.scopes.forEach { (id, input) ->
            val expectedClasses = if (id == ":app:jar")
                setOf("sample/App.class", "sample/Gate.class", "sample/Library.class")
            else setOf("sample/Gate.class", "sample/Library.class")
            val actualClasses = Files.walk(input.classes).use { paths ->
                paths.filter { it.toString().endsWith(".class") }.map { input.classes.relativize(it).toString() }.toList()
            }
            assertEquals(expectedClasses, actualClasses.toSet())
            assertEquals(expectedClasses.size, actualClasses.size)
            // Resource-only normalized JARs remain; no producer class may be reintroduced as a dependency.
            val dependencyEntries = input.dependencies.flatMap { dependency ->
                assertTrue(Files.isRegularFile(dependency), dependency.toString())
                java.util.jar.JarFile(dependency.toFile()).use { jar ->
                    jar.entries().asSequence().map { it.name }.toList()
                }
            }
            assertTrue(dependencyEntries.none { it.endsWith(".class") }, dependencyEntries.toString())
            assertTrue("messages.properties" in dependencyEntries)
        }
    }

    @ParameterizedTest
    @MethodSource("changes")
    fun `jar variant projects retain folded source and resource proofs`(variant: Variant, change: Change) {
        val repository = repository(change.resource, variant)
        val base = commit(repository)
        if (change.resource) {
            write(repository, RESOURCE, if (change == Change.FOLDED_RESOURCE) "old=Hello\nnew=Bonjour" else "old=Changed")
        } else {
            val file = repository.resolve(SOURCE)
            val before = Files.readString(file)
            Files.writeString(file, if (change == Change.FOLDED_SOURCE) before.replace("return 42;", "return 43;")
                else before.replace("return 1;", "return 2;"))
        }
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_class: "sample.Gate", callee_name: "enabled"})
                      RETURN cs
                    value: false
            """.trimIndent()),
        )
        assertEquals(change.verdict, result.verdict, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED || it.code == DiagnosticCode.UNVERIFIED_ORIGIN },
            result.toString())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED }, result.toString())
        assertTrue(result.metadata.obligations > 0)
        if (change.verdict == Verdict.PASS) {
            assertTrue(result.diagnostics.isEmpty(), result.toString())
            assertEquals(result.metadata.obligations, result.metadata.provedObligations)
        } else if (change.resource) {
            assertTrue(result.findings.isEmpty())
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        } else {
            assertTrue(result.findings.isNotEmpty(), result.toString())
            assertTrue(result.findings.flatMap { it.locations }.all { it.path == SOURCE })
        }
    }

    private fun repository(resource: Boolean, variant: Variant): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val project = Path.of(System.getProperty("graphfold.test.repository"))
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { name ->
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(project.resolve(name), target)
        }
        write(root, ".gitignore", ".gradle/\nbuild/\n")
        write(root, "settings.gradle", "rootProject.name='project-jar'; include 'app', 'library'")
        Files.copy(project.resolve("fixtures/project-jar/${variant.buildFile}"), root.resolve("build.gradle"))
        write(root, "library/src/main/java/sample/Gate.java",
            "package sample; public class Gate { public static boolean enabled() { return true; } }")
        val body = if (resource) """
            public static String value() {
                java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("messages");
                if (Gate.enabled()) return bundle.getString("new");
                return bundle.getString("old");
            }
        """.trimIndent() else "public static int value() { if (Gate.enabled()) return 42; return 1; }"
        write(root, SOURCE, "package sample; public class Library { $body }")
        val type = if (resource) "String" else "int"
        write(root, "app/src/main/java/sample/App.java",
            "package sample; public class App { " +
                (if (variant == Variant.APPLICATION) "public static void main(String[] args) { new App().value(); } " else "") +
                "public $type value() { return Library.value(); } }")
        if (resource) write(root, RESOURCE, "old=Hello")
        Git.init().setDirectory(root.toFile()).call().close()
        return root
    }

    private fun commit(repository: Path): String = Git.open(repository.toFile()).use { git ->
        ensureSnapshotPlugin(git.repository.workTree.toPath())
        git.add().addFilepattern(".").call()
        git.commit().setMessage("fixture").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }

    private fun write(root: Path, relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    enum class Variant(val buildFile: String) {
        APPLICATION("build.gradle"), LIBRARY("library-only.gradle"),
    }

    enum class Change(val resource: Boolean, val verdict: Verdict) {
        FOLDED_SOURCE(false, Verdict.PASS), LIVE_SOURCE(false, Verdict.FAIL),
        FOLDED_RESOURCE(true, Verdict.PASS), LIVE_RESOURCE(true, Verdict.FAIL),
    }

    companion object {
        @JvmStatic
        fun changes() = Variant.entries.flatMap { variant -> Change.entries.map { Arguments.of(variant, it) } }

        const val SOURCE = "library/src/main/java/sample/Library.java"
        const val RESOURCE = "library/src/main/resources/messages.properties"
    }
}
