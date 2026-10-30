package io.johnsonlee.graphfold.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `arithmetic finding points to the changed character not the whole method`() {
        val result = analyze("return x + 1;", "return x - 1;")
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        val finding = result.findings.single()
        assertEquals(listOf(Revision.BASE, Revision.HEAD), finding.locations.map { it.revision })
        finding.locations.forEach {
            assertEquals("src/main/java/sample/Example.java", it.path)
            assertEquals(SourceRange(4, 4), it.line)
            assertEquals(1, it.column.end - it.column.start)
        }
    }

    @Test
    fun `invalid fold is input failure rather than empty successful report`() {
        val result = engine().analyze(AnalysisRequest("missing", "a", "b", "folds: []"))
        assertEquals(Verdict.FAIL, result.verdict)
        assertEquals(AnalysisStage.INPUT, result.diagnostics.single().stage)
    }

    @Test
    fun `failed compilation blocks and still cleans detached worktrees`() {
        val result = analyze("return x;", "return thisDoesNotCompile;")
        assertEquals(Verdict.FAIL, result.verdict)
        assertEquals(AnalysisStage.BUILD, result.diagnostics.single().stage)
        assertEquals(0, result.metadata.provedObligations)
    }

    @Test
    fun `ambiguous anonymous source origins block without inventing diff findings`() {
        val result = analyze("new Object() {}; new Object() {}; return x;",
            "new Object() {}; new Object() {}; return x + 1;")
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.diagnostics.any { it.blocking && it.code == "UNVERIFIED_ORIGIN" }, result.toString())
        assertTrue(result.diagnostics.any { it.message.contains("Ambiguous") }, result.toString())
        assertTrue(result.findings.isEmpty(), "An analysis gap is not a confirmed diff violation")
        assertEquals(0, result.metadata.provedObligations)
    }

    @Test
    fun `unmatched rule on both sides blocks even an equivalent source change`() {
        val result = analyze("return x + 1;", "return x + 1; // comment", "missing")
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.diagnostics.any { it.code == "UNMATCHED_RULE" })
    }

    @Test
    fun `configuration change remains an explicit unresolved obligation`() {
        val repository = repository("return x;")
        val base = commit(repository)
        Files.writeString(repository.resolve("application.properties"), "feature.value=changed\n")
        val head = commit(repository)
        val result = engine().analyze(AnalysisRequest(repository.toString(), base, head, rules()))
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.diagnostics.any { it.code == "UNMAPPED_CHANGE" })
    }

    @Test
    fun `multiple scenarios must all pass and retain the failing scenario`() {
        val repository = repository("return x;")
        val base = commit(repository)
        Files.writeString(repository.resolve("src/main/java/sample/Example.java"), program("return x + 1;"))
        val head = commit(repository)
        val document = """
            scenarios:
              - name: "off"
                folds:
                  - match: {CallSite: {callee_class: sample.Ab, callee_name: enabled}}
                    args: {0: feature}
                    value: false
              - name: "on"
                folds:
                  - match: {CallSite: {callee_class: sample.Ab, callee_name: enabled}}
                    args: {0: feature}
                    value: true
        """.trimIndent()
        val result = engine().analyze(AnalysisRequest(repository.toString(), base, head, document))
        assertEquals(Verdict.FAIL, result.verdict)
        assertEquals(listOf("off", "on"), result.metadata.scenarios)
        assertEquals(listOf(listOf("off")), result.findings.map { it.scenarios }, result.toString())
    }

    @Test
    fun `reflection ambiguity cannot make a private helper modification pass`() {
        val before = "return Example.class.getDeclaredMethods().length;"
        val result = analyze(before, "return Example.class.getDeclaredMethods().length + 1;")
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.diagnostics.any { it.code == "INCOMPLETE_GRAPH" }, result.toString())
    }

    @Test
    fun `identical revisions pass without requiring compilation or fold matches`() {
        val repository = repository("return doesNotCompile;")
        val revision = commit(repository)
        val result = engine().analyze(AnalysisRequest(repository.toString(), revision, revision, rules()))
        assertEquals(Verdict.PASS, result.verdict)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(0, result.metadata.provedObligations)
    }

    @Test
    fun `interrupted analysis blocks and preserves caller cancellation`() {
        val repository = repository("return x;")
        val revision = commit(repository)
        try {
            Thread.currentThread().interrupt()
            val result = engine().analyze(AnalysisRequest(repository.toString(), revision, revision, rules()))
            assertEquals(Verdict.FAIL, result.verdict)
            assertEquals("ANALYSIS_CANCELLED", result.diagnostics.single().code)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `changed source without a declaration remains unresolved`() {
        val repository = repository("return x;")
        val source = repository.resolve("src/main/java/sample/Marker.java")
        Files.writeString(source, "// before\n")
        val base = commit(repository)
        Files.writeString(source, "// after\n")
        val head = commit(repository)
        val result = engine().analyze(AnalysisRequest(repository.toString(), base, head, rules()))
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.diagnostics.any { it.code == "UNMAPPED_CHANGE" }, result.toString())
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `deleting an existing public entry remains a base revision finding`() {
        val repository = repository("return x;")
        val base = commit(repository)
        Files.delete(repository.resolve("src/main/java/sample/Example.java"))
        val head = commit(repository)
        val result = engine().analyze(AnalysisRequest(repository.toString(), base, head, rules()))
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.flatMap { it.locations }.all { it.revision == Revision.BASE })
    }

    @Test
    fun `deleted configuration identifies its base path`() {
        val repository = repository("return x;")
        val configuration = repository.resolve("application.properties")
        Files.writeString(configuration, "feature.value=before\n")
        val base = commit(repository)
        Files.delete(configuration)
        val head = commit(repository)
        val result = engine().analyze(AnalysisRequest(repository.toString(), base, head, rules()))
        assertEquals(Verdict.FAIL, result.verdict)
        val diagnostic = result.diagnostics.single { it.code == "UNMAPPED_CHANGE" }
        assertTrue(diagnostic.message.contains("application.properties"))
    }

    @Test
    fun `binary classified Java cannot silently escape source obligations`() {
        val repository = repository("return x;")
        val source = repository.resolve("src/main/java/sample/Example.java")
        Files.writeString(source, program("return x;") + "\n/*\u0000*/")
        val base = commit(repository)
        Files.writeString(source, program("return x + 1;") + "\n/*\u0000*/")
        val head = commit(repository)
        val result = engine().analyze(AnalysisRequest(repository.toString(), base, head, rules()))
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.diagnostics.any { it.code == "UNMAPPED_CHANGE" }, result.toString())
        assertTrue(result.findings.isEmpty())
    }

    private fun analyze(before: String, after: String, key: String = "feature"): AnalysisResult {
        val repository = repository(before)
        val base = commit(repository)
        Files.writeString(repository.resolve("src/main/java/sample/Example.java"), program(after))
        val head = commit(repository)
        return engine().analyze(AnalysisRequest(repository.toString(), base, head, rules(key)))
    }

    private fun engine() = GraphFoldEngine(EngineConfiguration(temporary.resolve("cache")))

    private fun repository(body: String): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val sources = Files.createDirectories(root.resolve("src/main/java/sample"))
        Files.writeString(sources.resolve("Example.java"), program(body))
        Files.writeString(
            sources.resolve("Ab.java"),
            "package sample; public class Ab { public static boolean enabled(String k) {return true;} }",
        )
        git(root, "init", "--quiet")
        git(root, "config", "user.name", "GraphFold")
        git(root, "config", "user.email", "test@example.invalid")
        return root
    }

    private fun commit(root: Path): String {
        git(root, "add", ".")
        git(root, "commit", "--quiet", "-m", "fixture")
        return git(root, "rev-parse", "HEAD").trim()
    }

    private fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root.toFile()).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { text }
        return text
    }

    private fun program(body: String) = """
        package sample;
        public class Example {
          public int run(int x) { if (Ab.enabled("feature")) return 42;
            $body
          }
        }
    """.trimIndent()

    private fun rules(key: String = "feature") = """
        version: 1
        folds:
          - match: {CallSite: {callee_class: sample.Ab, callee_name: enabled}}
            args: {0: $key}
            value: false
    """.trimIndent()
}
