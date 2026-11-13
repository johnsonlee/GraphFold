package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.DiagnosticCode
import io.johnsonlee.graphfold.engine.ReasonCode
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Nested archive bytes remain static packaged-resource evidence in an ordinary JAR. */
class NestedArchiveIntegrationTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["custom", "Rsrc-Main-Class", "One-Jar-Main-Class"])
    fun `changed nested archive is blocked by packaged input evidence even when its classes are absent from the graph`(kind: String) {
        val repository = repository()
        write(repository, "settings.gradle", "rootProject.name='nested-launch-proof'; include 'payload'")
        write(repository, "payload/build.gradle", "plugins { id 'java' }")
        val marker = if (kind == "custom") "" else "manifest { attributes '$kind': 'payload.App' }"
        write(repository, "build.gradle", buildScript() + """
            evaluationDependsOn(':payload')
            jar {
                dependsOn ':payload:jar'
                from(project(':payload').tasks.named('jar')) { into 'lib'; rename { 'app.jar' } }
                $marker
            }
        """.trimIndent())
        write(repository, "src/main/java/sample/Launcher.java", """
            package sample;
            public class Launcher {
                public static void main(String[] args) throws Exception {
                    if (Gate.enabled()) System.out.print("unused");
                    var extracted = java.nio.file.Files.createTempFile("nested-app", ".jar");
                    try {
                        try (var input = Launcher.class.getResourceAsStream("/lib/app.jar")) {
                            java.nio.file.Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{extracted.toUri().toURL()}, null)) {
                            System.out.print(loader.loadClass("payload.App").getMethod("run").invoke(null));
                        }
                    } finally { java.nio.file.Files.deleteIfExists(extracted); }
                }
            }
        """.trimIndent())
        write(repository, "payload/src/main/java/payload/App.java", payload("before"))
        val base = commit(repository)
        write(repository, "payload/src/main/java/payload/App.java", payload("after"))
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED && "lib/app.jar" in it.message },
            "This is an unproved changed packaged input, not a semantic detection true positive: $result")
        assertTrue(result.reasons.any { it.code == ReasonCode.ANALYSIS_INCOMPLETE }, result.toString())
        assertTrue(result.findings.isEmpty(), "Nested code has no declaration in the ordinary application graph")
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.UNMATCHED_RULE }, result.toString())
    }

    @Test fun `ordinary primary jar with inert zip data retains complete folded source proof`() {
        val repository = repository()
        write(repository, "build.gradle", buildScript() + "\njar { from('payload.zip') }")
        java.util.zip.ZipOutputStream(Files.newOutputStream(repository.resolve("payload.zip"))).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("data.txt")); zip.write("data".toByteArray()); zip.closeEntry()
        }
        write(repository, "src/main/java/sample/Launcher.java", """
            package sample;
            public class Launcher {
                public static void main(String[] args) { System.out.print(App.run()); }
            }
        """.trimIndent())
        write(repository, "src/main/java/sample/App.java", ordinary(42))
        val base = commit(repository)
        write(repository, "src/main/java/sample/App.java", ordinary(43))
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertTrue(result.reasons.any { it.code == ReasonCode.FOLDED_EQUIVALENT })
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
    }

    private fun repository(): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val project = Path.of(System.getProperty("graphfold.test.repository"))
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { name ->
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(project.resolve(name), target)
        }
        write(root, ".gitignore", ".gradle/\nbuild/\n")
        write(root, "settings.gradle", "rootProject.name='nested-launch-proof'")
        write(root, "src/main/java/sample/Gate.java", """
            package sample;
            public class Gate { public static boolean enabled() { return false; } }
        """.trimIndent())
        Git.init().setDirectory(root.toFile()).call().close()
        return root
    }

    private fun analyze(repository: Path, base: String, head: String): AnalysisResult =
        PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, head, """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.Gate, callee_name: enabled}}
                    value: false
            """.trimIndent()))

    private fun buildScript(): String = """
        plugins { id 'application' }
        application { mainClass = 'sample.Launcher' }
        jar { manifest { attributes 'Main-Class': 'sample.Launcher' } }
    """.trimIndent() + "\n"

    private fun ordinary(value: Int): String = """
        package sample;
        public class App { public static int run() { if (Gate.enabled()) return $value; return 7; } }
    """.trimIndent()

    private fun payload(value: String): String = """
        package payload;
        public class App { public static String run() { return "$value"; } }
    """.trimIndent()

    private fun write(root: Path, name: String, text: String) {
        val target = root.resolve(name)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    private fun commit(root: Path): String = Git.open(root.toFile()).use { git ->
        ensureSnapshotPlugin(git.repository.workTree.toPath())
        git.add().addFilepattern(".").call()
        git.commit().setMessage("nested archive fixture").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }
}
