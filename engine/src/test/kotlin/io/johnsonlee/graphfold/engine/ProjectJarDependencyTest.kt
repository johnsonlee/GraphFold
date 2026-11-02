package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.build.GradleInputKind
import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import io.johnsonlee.graphfold.engine.build.parseGradleInputs
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectJarDependencyTest {
    @TempDir lateinit var temporary: Path

    @Test fun `real Gradle jar variant remains inventoried but does not duplicate graph classes`() {
        val repository = repository(resource = true)
        val output = temporary.resolve("compiled")
        val compiled = ProjectCompiler(Duration.ofMinutes(2)).compile(repository, output)
        val entries = parseGradleInputs(Files.readAllLines(output.resolve("gradle-inputs.txt")))
        val artifacts = entries.filter { it.kind == GradleInputKind.ARTIFACT }.flatMap { it.paths }.toSet()
        val dependencies = entries.filter { it.kind == GradleInputKind.DEPENDENCY }.flatMap { it.paths }
        val overlapping = dependencies.filter { it in artifacts }
        assertTrue(overlapping.any { it.fileName.toString().startsWith("library") }, entries.toString())
        assertTrue(compiled.dependencies.none { it in overlapping }, compiled.dependencies.toString())
        val production = requireNotNull(compiled.production)
        assertTrue(production.resourceDependencies.none { it in overlapping }, production.toString())
        assertTrue(production.entries.keys.any { it.startsWith("artifact/") && it.endsWith("messages.properties") })
        assertTrue(production.classPlacements.getValue("sample/Library.class").any { it.startsWith("artifact/") })
    }

    @ParameterizedTest
    @EnumSource(Change::class)
    fun `jar variant projects retain folded source and resource proofs`(change: Change) {
        val repository = repository(change.resource)
        val base = commit(repository)
        if (change.resource) {
            write(repository, RESOURCE, if (change == Change.FOLDED_RESOURCE) "old=Hello\nnew=Bonjour" else "old=Changed")
        } else {
            val file = repository.resolve(SOURCE)
            val before = Files.readString(file)
            Files.writeString(file, if (change == Change.FOLDED_SOURCE) before.replace("return 42;", "return 43;")
                else before.replace("return 1;", "return 2;"))
        }
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"))).analyze(
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

    private fun repository(resource: Boolean): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val project = Path.of(System.getProperty("user.dir")).let { if (Files.exists(it.resolve("gradlew"))) it else it.parent }
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { name ->
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(project.resolve(name), target)
        }
        write(root, ".gitignore", ".gradle/\nbuild/\n")
        write(root, "settings.gradle", "rootProject.name='project-jar'; include 'app', 'library'")
        Files.copy(project.resolve("fixtures/project-jar/build.gradle"), root.resolve("build.gradle"))
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
            "package sample; public class App { public $type value() { return Library.value(); } }")
        if (resource) write(root, RESOURCE, "old=Hello")
        Git.init().setDirectory(root.toFile()).call().close()
        return root
    }

    private fun commit(repository: Path): String = Git.open(repository.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("fixture").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }

    private fun write(root: Path, relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    enum class Change(val resource: Boolean, val verdict: Verdict) {
        FOLDED_SOURCE(false, Verdict.PASS), LIVE_SOURCE(false, Verdict.FAIL),
        FOLDED_RESOURCE(true, Verdict.PASS), LIVE_RESOURCE(true, Verdict.FAIL),
    }

    private companion object {
        const val SOURCE = "library/src/main/java/sample/Library.java"
        const val RESOURCE = "library/src/main/resources/messages.properties"
    }
}
