package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unchanged packaged siblings do not obscure controlled-source findings in another application. */
class GradleScopeIsolationIntegrationTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["war", "boot-2.7.18", "boot-3.0.13", "boot-3.1.12", "boot-war"])
    fun `supported application findings survive independent packaged archive scopes`(kind: String) {
        val root = Files.createDirectory(temporary.resolve("repository"))
        configure(root, kind)
        write(root, "app/src/main/java/sample/App.java", application(1))
        write(root, "other/src/main/java/other/Main.java", """
            package other;
            public class Main { public static void main(String[] args) { System.out.print("other"); } }
        """.trimIndent())
        Git.init().setDirectory(root.toFile()).call().close()
        val base = commit(root)
        write(root, "app/src/main/java/sample/App.java", application(2))
        val head = commit(root)
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(root.toString(), base, head, """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.App, callee_name: enabled}}
                    value: true
            """.trimIndent()))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.findings.isNotEmpty(), "The supported application's concrete change must still be reported: $result")
        assertTrue(result.findings.flatMap { it.locations }.all { it.path == "app/src/main/java/sample/App.java" })
        val appAudits = result.ruleAudits.filter { "[scope :app:jar]" in it.scenario }
        assertEquals(2, appAudits.size)
        assertTrue(appAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
        val otherAudits = result.ruleAudits.filter { "[scope :other:jar]" in it.scenario }
        assertTrue(otherAudits.isEmpty(), result.toString())
        assertTrue(result.metadata.obligations > result.metadata.provedObligations)
    }

    private fun configure(root: Path, kind: String) {
        val workspace = Path.of(System.getProperty("graphfold.test.repository"))
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { name ->
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(workspace.resolve(name), target)
        }
        write(root, ".gitignore", ".gradle/\n**/build/\n")
        write(root, "settings.gradle", "rootProject.name='isolation'; include 'app', 'other'")
        write(root, "build.gradle", "allprojects { repositories { mavenCentral() } }")
        write(root, "app/build.gradle", "plugins { id 'application' }; application { mainClass='sample.App' }")
        val plugin = if (kind == "war") "id 'war'" else {
            val version = if (kind == "boot-war") "3.5.7" else kind.removePrefix("boot-")
            "id '${if (kind == "boot-war") "war" else "java"}'; id 'org.springframework.boot' version '$version'"
        }
        write(root, "other/build.gradle", "plugins { $plugin }\n" +
            if (kind == "war") "" else "springBoot { mainClass='other.Main' }\n")
    }

    private fun application(price: Int): String = """
        package sample;
        public class App {
            public static boolean gate;
            public static boolean enabled() { return gate; }
            public static int value() { return enabled() ? $price : 0; }
            public static void main(String[] args) { value(); }
        }
    """.trimIndent()

    private fun write(root: Path, name: String, value: String) {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
    }

    private fun commit(root: Path): String = Git.open(root.toFile()).use { git ->
        ensureSnapshotPlugin(git.repository.workTree.toPath())
        git.add().addFilepattern(".").call()
        git.commit().setMessage("archive isolation fixture").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }
}
