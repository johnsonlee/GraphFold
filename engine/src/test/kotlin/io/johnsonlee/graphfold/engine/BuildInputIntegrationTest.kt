package io.johnsonlee.graphfold.engine

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildInputIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `README CI and unpackaged translations pass from actual unchanged production outputs`() {
        val repository = repository()
        val paths = listOf("README.md", ".github/workflows/ci.yml", "translations/messages.properties")
        paths.forEach { write(repository, it, "before") }
        val base = commit(repository)
        paths.forEach { write(repository, it, "after") }
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(paths.toSet(), result.reasons.mapNotNull { it.headPath }.toSet())
        assertTrue(result.reasons.all { it.code == ReasonCode.BUILD_OUTPUT_UNCHANGED }, result.toString())
        assertEquals(paths.size, result.metadata.provedObligations)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.NOT_EXECUTED })
    }

    @Test
    fun `Kotlin README change passes without matching a fold despite checkout specific provenance markers`() {
        val repository = repository()
        Files.delete(repository.resolve(SOURCE))
        Files.delete(repository.resolve("src/main/java/sample/Ab.java"))
        write(repository, "build.gradle", """
            plugins { id 'org.jetbrains.kotlin.jvm' version '2.1.20' }
            repositories { mavenCentral() }
        """.trimIndent())
        write(repository, "src/main/kotlin/sample/Example.kt", """
            package sample
            class Example {
                fun callback(): Runnable = object : Runnable {
                    override fun run() {}
                }
                fun callbackName(): String = callback().javaClass.name
            }
        """.trimIndent())
        write(repository, "README.md", "before")
        val base = commit(repository)
        write(repository, "README.md", "after")
        // The configured sample.Ab.enabled selector cannot match this pure Kotlin program.
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertEquals(ReasonCode.BUILD_OUTPUT_UNCHANGED, result.reasons.single().code)
        assertEquals("README.md", result.reasons.single().headPath)
        assertEquals(1, result.metadata.provedObligations)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.NOT_EXECUTED }, result.toString())
        // An identical application cannot use this shortcut with corrupted compiler ownership evidence.
        val build = repository.resolve("build.gradle")
        Files.writeString(build, Files.readString(build) + "\n" + """
            gradle.projectsEvaluated {
                tasks.named('graphfoldExportCompiledInputs') {
                    doLast {
                        fileTree(System.getProperty('graphfold.origin.output')).matching { include '**/*.json' }.each {
                            it.text = '{}'
                        }
                    }
                }
            }
        """.trimIndent())
        val invalid = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, invalid.verdict, invalid.toString())
        assertTrue(invalid.diagnostics.any { it.code == DiagnosticCode.UNVERIFIED_ORIGIN }, invalid.toString())
        assertTrue(invalid.findings.isEmpty(), invalid.toString())
    }

    @Test
    fun `packaged translation changes block with production output evidence`() {
        val repository = repository()
        val path = "src/main/resources/messages.properties"
        write(repository, path, "greeting=Hello")
        write(repository, SOURCE, program("return java.util.ResourceBundle.getBundle(\"messages\").getString(\"greeting\").length();"))
        val base = commit(repository)
        write(repository, path, "greeting=Bonjour")
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(listOf(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED), result.diagnostics.map { it.code })
        assertTrue(result.findings.isEmpty(), "A resource gap must not invent a source-code violation")
        assertEquals(path, result.reasons.single().headPath)
        assertEquals(ReasonCode.ANALYSIS_INCOMPLETE, result.reasons.single().code)
    }

    @Test
    fun `mixed README and source changes require folded whole program proof`() {
        val repository = repository()
        write(repository, "README.md", "before")
        val base = commit(repository)
        write(repository, "README.md", "after")
        write(repository, SOURCE, program("return x;", treatment = 43))
        val controlled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, controlled.verdict, controlled.toString())
        assertTrue(controlled.diagnostics.isEmpty(), controlled.toString())
        assertTrue(controlled.findings.isEmpty(), controlled.toString())
        assertEquals(ReasonCode.FOLDED_EQUIVALENT, controlled.reasons.single { it.headPath == "README.md" }.code)
        write(repository, SOURCE, program("return x + 1;", treatment = 43))
        val uncontrolled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, uncontrolled.verdict, uncontrolled.toString())
        assertTrue(uncontrolled.diagnostics.any { it.code == DiagnosticCode.UNVERIFIED_BUILD_INPUT }, uncontrolled.toString())
        assertTrue(uncontrolled.findings.isNotEmpty(), uncontrolled.toString())
        assertTrue(uncontrolled.findings.flatMap { it.locations }.all { it.path == SOURCE }, uncontrolled.toString())
        assertEquals(ReasonCode.ANALYSIS_INCOMPLETE, uncontrolled.reasons.single { it.headPath == "README.md" }.code)
    }

    @Test
    fun `README used by a source generator cannot pass as an unrelated document`() {
        val repository = repository()
        write(repository, SOURCE, program("return Generated.value();"))
        write(repository, "README.md", "1")
        write(repository, "build.gradle", "plugins { id 'java' }\n" + generatorScript())
        val base = commit(repository)
        write(repository, "README.md", "2")
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.UNVERIFIED_BUILD_INPUT }, result.toString())
        assertTrue(result.diagnostics.any { "Generated" in it.message }, result.toString())
        assertTrue(result.findings.isEmpty(), "Generated source outside the Git diff cannot become a source-code finding")
        assertEquals("README.md", result.reasons.single().headPath)
        assertEquals(ReasonCode.ANALYSIS_INCOMPLETE, result.reasons.single().code)
        assertEquals(0, result.metadata.provedObligations)
    }

    @Test
    fun `excluding a compiled application class from the production jar cannot pass through graph equivalence`() {
        val repository = repository()
        val base = commit(repository)
        write(repository, "build.gradle", "plugins { id 'java' }\njar { exclude('sample/Example.class') }\n")
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(listOf(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED), result.diagnostics.map { it.code })
        assertTrue(result.diagnostics.single().message.contains("classPlacement/sample/Example.class"), result.toString())
        assertTrue(result.findings.isEmpty(), "Packaging differences must not invent a Java diff location")
        assertEquals("build.gradle", result.reasons.single().headPath)
        assertEquals(ReasonCode.ANALYSIS_INCOMPLETE, result.reasons.single().code)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "return bundle.getString(\"old\");",
        "System.out.println(\"hello\"); return bundle.getString(\"old\");",
        "return bundle.getString(\"old\") + \"!\";",
    ])
    fun `new properties key read only in folded branch passes while live key edit fails`(output: String) {
        val repository = repository()
        val path = "src/main/resources/messages.properties"
        write(repository, path, "old=Hello")
        write(repository, SOURCE, """
            package sample;
            public class Example {
                public String value() {
                    java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("messages");
                    if (Ab.enabled("feature")) return bundle.getString("new");
                    $output
                }
            }
        """.trimIndent())
        val base = commit(repository)
        write(repository, path, "old=Hello\nnew=Bonjour")
        val controlled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, controlled.verdict, controlled.toString())
        assertTrue(controlled.diagnostics.isEmpty(), controlled.toString())
        assertEquals(1, controlled.metadata.provedObligations)
        write(repository, path, "old=Changed\nnew=Bonjour")
        val uncontrolled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, uncontrolled.verdict, uncontrolled.toString())
        assertTrue(uncontrolled.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, uncontrolled.toString())
    }

    @Test
    fun `mixed opaque resource and source changes retain source findings despite resource gap`() {
        val repository = repository()
        val path = "src/main/resources/data.txt"
        write(repository, path, "before")
        val base = commit(repository)
        write(repository, path, "after")
        write(repository, SOURCE, program("return x + 1;"))
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        assertTrue(result.findings.isNotEmpty(), result.toString())
        assertTrue(result.findings.flatMap { it.locations }.all { it.path == SOURCE }, result.toString())
        assertTrue(result.reasons.any { it.headPath == path && it.code == ReasonCode.ANALYSIS_INCOMPLETE }, result.toString())
        assertTrue(result.reasons.any { it.headPath == SOURCE && it.code == ReasonCode.SURVIVING_DIFFERENCE }, result.toString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `resource analysis includes exposed static fields without external dependencies or callable constructors`(exposed: Boolean) {
        val repository = repository()
        val path = "src/main/resources/messages.properties"
        val visibility = if (exposed) "public" else "private"
        write(repository, "src/main/java/sample/Extra.java", """
            package sample;
            public final class Extra {
                $visibility static final String VALUE = java.util.ResourceBundle.getBundle("messages").getString("greeting");
                private Extra() {}
            }
        """.trimIndent())
        write(repository, path, "greeting=Hello")
        val base = commit(repository)
        write(repository, path, "greeting=Bonjour")
        val result = analyze(repository, base, commit(repository))
        assertEquals(if (exposed) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), "Resource-only changes must not invent source findings")
        if (exposed) {
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        } else {
            assertTrue(result.diagnostics.isEmpty(), result.toString())
            assertEquals(ReasonCode.FOLDED_EQUIVALENT, result.reasons.single().code)
        }
    }

    private fun repository(): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val project = Path.of(System.getProperty("user.dir")).let { if (Files.exists(it.resolve("gradlew"))) it else it.parent }
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { path ->
            val target = root.resolve(path)
            Files.createDirectories(target.parent)
            Files.copy(project.resolve(path), target)
        }
        write(root, ".gitignore", ".gradle/\nbuild/\n")
        write(root, "settings.gradle", "rootProject.name='build-input-proof'")
        write(root, "build.gradle", "plugins { id 'java' }")
        write(root, SOURCE, program("return x;"))
        write(root, "src/main/java/sample/Ab.java",
            "package sample; public class Ab { public static boolean enabled(String key) { return true; } }")
        Git.init().setDirectory(root.toFile()).call().close()
        return root
    }

    private fun analyze(repository: Path, base: String, head: String): AnalysisResult =
        GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"), buildTimeout = Duration.ofMinutes(2)))
            .analyze(AnalysisRequest(repository.toString(), base, head, """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.Ab, callee_name: enabled}}
                    args: {0: feature}
                    value: false
            """.trimIndent()))

    private fun commit(root: Path): String = Git.open(root.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("fixture").setAuthor("GraphFold", "test@example.invalid")
            .setCommitter("GraphFold", "test@example.invalid").call().name
    }

    private fun write(root: Path, path: String, content: String) {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun program(body: String, treatment: Int = 42): String = """
        package sample;
        public class Example {
          public int run(int x) {
            if (Ab.enabled("feature")) return $treatment;
            $body
          }
        }
    """.trimIndent()

    private fun generatorScript(): String = """
        def generated = layout.buildDirectory.dir('generated/source')
        tasks.register('generateSource') {
            inputs.file('README.md')
            outputs.dir(generated)
            doLast {
                def source = new File(generated.get().asFile, 'sample/Generated.java')
                source.parentFile.mkdirs()
                source.text = 'package sample; public class Generated { public static int value() { return ' +
                    file('README.md').text + '; } }'
            }
        }
        sourceSets.main.java.srcDir(generated)
        compileJava.dependsOn('generateSource')
    """.trimIndent()

    private companion object {
        const val SOURCE = "src/main/java/sample/Example.java"
    }
}
