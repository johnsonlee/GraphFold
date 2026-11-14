package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.DiagnosticCode
import io.johnsonlee.graphfold.engine.ReasonCode
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagedSourceEqualityIntegrationTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `only a class excluded from the actual archive has identical packaged evidence`(included: Boolean) {
        val repository = Files.createDirectory(temporary.resolve("repository"))
        val project = Path.of(System.getProperty("graphfold.test.repository"))
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { name ->
            val target = repository.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(project.resolve(name), target)
        }
        write(repository, ".gitignore", ".gradle/\nbuild/\n")
        write(repository, "settings.gradle", "rootProject.name='excluded-source'")
        write(repository, "build.gradle", """
            plugins { id 'application' }
            application { mainClass = 'sample.App' }
            ${if (included) "" else "jar { exclude 'sample/Discarded*' }"}
        """.trimIndent())
        write(repository, "src/main/java/sample/App.java", """
            package sample;
            public class App {
                public static boolean enabled() { return true; }
                public static int value() { return enabled() ? 2 : 1; }
                public static void main(String[] args) { System.out.println(value()); }
            }
        """.trimIndent())
        val excluded = "src/main/java/sample/Discarded.java"
        write(repository, excluded, "package sample; public class Discarded { public int value() { return 1; } }")
        Git.init().setDirectory(repository.toFile()).call().close()
        val base = commit(repository)
        write(repository, excluded, "package sample; public class Discarded { public int value() { return 2; } }")
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_class: "sample.App", callee_name: "enabled"}) RETURN cs
                    value: false
            """.trimIndent()),
        )
        assertEquals(if (included) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        assertEquals(1, result.metadata.obligations)
        assertEquals(if (included) 0 else 1, result.metadata.provedObligations)
        val reason = if (included) ReasonCode.SURVIVING_DIFFERENCE else ReasonCode.BUILD_OUTPUT_UNCHANGED
        assertTrue(result.reasons.all { it.code == reason && it.headPath == excluded }, result.toString())
        if (included) {
            assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 },
                result.toString())
            assertTrue(result.diagnostics.none { it.code != DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
            assertTrue(result.findings.isNotEmpty() || result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED },
                result.toString())
            assertTrue(result.findings.flatMap { it.locations }.all { it.path == excluded }, result.toString())
        } else {
            assertTrue(result.diagnostics.isEmpty(), result.toString())
            assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.NOT_EXECUTED }, result.toString())
            assertTrue(result.findings.isEmpty(), result.toString())
        }
    }

    private fun write(root: Path, name: String, text: String) {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun commit(root: Path): String = Git.open(root.toFile()).use { git ->
        ensureSnapshotPlugin(git.repository.workTree.toPath())
        git.add().addFilepattern(".").call()
        git.commit().setMessage("packaged source equality").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }
}
