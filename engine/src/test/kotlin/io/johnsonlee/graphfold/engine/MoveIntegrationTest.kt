package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Java directory move preserves identity and reports paths without invented source ranges`() {
        val before = "src/main/java/sample/Example.java"
        val after = "src/main/java/relocated/Example.java"
        val result = analyzeFixture("java_directory_move")
        assertEquivalentMove(result, before, after, ChangeKind.FILE_MOVED)
    }

    @Test
    fun `Kotlin directory move preserves class identity and reports actual paths`() {
        val before = "src/main/kotlin/sample/Example.kt"
        val after = "src/main/kotlin/relocated/Example.kt"
        val result = analyzeFixture("kotlin_directory_move")
        assertEquivalentMove(result, before, after, ChangeKind.FILE_MOVED)
    }

    @Test
    fun `Kotlin top level filename change removes existing JVM facade API`() {
        val before = "src/main/kotlin/sample/Example.kt"
        val after = "src/main/kotlin/sample/Renamed.kt"
        val result = analyzeFixture("kotlin_facade_rename")
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), "Content-identical rename has no changed source coordinates")
        assertTrue(result.diagnostics.any { it.code == "UNLOCATED_MOVE_DIFFERENCE" }, result.toString())
        assertTrue(result.reasons.any {
            ChangeKind.FILE_RENAMED in it.changes && it.code == ReasonCode.SURVIVING_DIFFERENCE &&
                it.basePath == before && it.headPath == after && it.locations.isEmpty()
        }, result.toString())
    }

    @Test
    fun `explicit Kotlin JVM facade name makes source filename rename equivalent`() {
        val before = "src/main/kotlin/sample/Example.kt"
        val after = "src/main/kotlin/sample/Renamed.kt"
        val result = analyzeFixture("kotlin_stable_facade_rename")
        assertEquivalentMove(result, before, after, ChangeKind.FILE_RENAMED)
    }

    private fun assertEquivalentMove(result: AnalysisResult, before: String, after: String, kind: ChangeKind) {
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.metadata.obligations > 0, "A pure move must still produce a proof obligation")
        assertEquals(result.metadata.obligations, result.metadata.provedObligations)
        assertTrue(result.reasons.isNotEmpty(), result.toString())
        assertTrue(result.reasons.all {
            kind in it.changes && it.code == ReasonCode.FOLDED_EQUIVALENT &&
                it.basePath == before && it.headPath == after && it.locations.isEmpty()
        }, result.toString())
    }

    @Test
    fun `directory move with arithmetic change reports only actual changed characters`() {
        val result = analyzeFixture("java_move_behavior_change")
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        val expected = listOf(
            SourceLocation(Revision.BASE, "src/main/java/sample/Example.java", SourceRange(4, 4), SourceRange(40, 41)),
            SourceLocation(Revision.HEAD, "src/main/java/relocated/Example.java", SourceRange(4, 4), SourceRange(40, 41)),
        )
        assertEquals(expected, result.findings.single().locations)
        assertTrue(result.reasons.any { ChangeKind.FILE_MOVED in it.changes }, result.toString())
    }

    @Test
    fun `Kotlin directory move with changed behavior retains actual changed file provenance`() {
        val result = analyzeFixture("kotlin_move_behavior_change")
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        val expected = listOf(
            SourceLocation(Revision.BASE, "src/main/kotlin/sample/Example.kt", SourceRange(4, 4), SourceRange(39, 40)),
            SourceLocation(Revision.HEAD, "src/main/kotlin/relocated/Example.kt", SourceRange(4, 4), SourceRange(39, 40)),
        )
        assertEquals(expected, result.findings.single().locations)
    }

    private fun analyzeFixture(id: String): AnalysisResult {
        val fixtures = Path.of("../fixtures/moves")
        val metadata = jacksonObjectMapper().readValue<List<MoveCase>>(fixtures.resolve("manifest.json").toFile())
            .single { it.id == id }
        val repository = Files.createDirectory(temporary.resolve("repository"))
        git(repository, "init", "--quiet")
        git(repository, "config", "user.name", "GraphFold Test")
        git(repository, "config", "user.email", "test@example.invalid")
        copyTree(fixtures.resolve("cases/$id/base"), repository)
        val base = commit(repository)
        Files.delete(repository.resolve(metadata.before))
        copyTree(fixtures.resolve("cases/$id/head"), repository)
        val head = commit(repository)
        val fold = """
            version: 1
            folds:
              - match: {CallSite: {callee_class: sample.Ab, callee_name: enabled}}
                args: {0: feature}
                value: false
        """.trimIndent()
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), base, head, fold),
        )
        assertEquals(metadata.verdict, result.verdict, id)
        assertTrue(result.reasons.any {
            metadata.kind in it.changes && it.basePath == metadata.before && it.headPath == metadata.after
        }, result.toString())
        metadata.diagnostic?.let { code ->
            assertTrue(result.diagnostics.any { it.code == code }, result.toString())
        }
        return result
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val destination = target.resolve(source.relativize(path))
                Files.createDirectories(destination.parent)
                Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun commit(repository: Path): String {
        git(repository, "add", ".")
        git(repository, "commit", "--quiet", "-m", "fixture")
        return git(repository, "rev-parse", "HEAD").trim()
    }

    private fun git(repository: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(repository.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output
    }

    data class MoveCase(
        val id: String,
        val before: String,
        val after: String,
        val verdict: Verdict,
        val kind: ChangeKind,
        val diagnostic: String? = null,
    )
}
