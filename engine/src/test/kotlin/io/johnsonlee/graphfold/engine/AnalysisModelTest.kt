package io.johnsonlee.graphfold.engine

import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalysisModelTest {
    @Test
    fun `gate passes only when every obligation is proved and no blocker remains`() {
        val complete = AnalysisMetadata("base", "head", listOf("control"), 2, 2)
        assertEquals(Verdict.PASS, AnalysisResult(emptyList(), emptyList(), complete).verdict)
        assertEquals(Verdict.FAIL, AnalysisResult(emptyList(), emptyList(), complete.copy(provedObligations = 1)).verdict)
        val warning = Diagnostic(AnalysisStage.BUILD, "NOTE", "information", blocking = false)
        assertEquals(Verdict.PASS, AnalysisResult(emptyList(), listOf(warning), complete).verdict)
        assertEquals(Verdict.FAIL, AnalysisResult(emptyList(), listOf(warning.copy(blocking = true)), complete).verdict)
        val finding = Finding("id", FindingKind.UNCONTROLLED_CHANGE, "rule", "remaining change", listOf(location()))
        assertEquals(Verdict.FAIL, AnalysisResult(listOf(finding), emptyList(), complete).verdict)
    }

    @Test
    fun `request requires all four analysis inputs`() {
        val request = AnalysisRequest("repo", "base", "head", "folds: []")
        assertFailsWith<IllegalArgumentException> { request.copy(repository = " ") }
        assertFailsWith<IllegalArgumentException> { request.copy(base = "") }
        assertFailsWith<IllegalArgumentException> { request.copy(head = "") }
        assertFailsWith<IllegalArgumentException> { request.copy(fold = "") }
    }

    @Test
    fun `positions enforce relative paths and ordered one based UTF16 coordinates`() {
        val position = location()
        assertFailsWith<IllegalArgumentException> { SourceRange(0, 1) }
        assertFailsWith<IllegalArgumentException> { SourceRange(1, 0) }
        assertFailsWith<IllegalArgumentException> { position.copy(path = "") }
        assertFailsWith<IllegalArgumentException> { position.copy(path = "/tmp/source.java") }
        assertFailsWith<IllegalArgumentException> { position.copy(path = "../source.java") }
        assertFailsWith<IllegalArgumentException> { position.copy(line = SourceRange(3, 2)) }
        assertFailsWith<IllegalArgumentException> { position.copy(column = SourceRange(5, 4)) }
        assertEquals(SourceRange(5, 1), position.copy(line = SourceRange(1, 2), column = SourceRange(5, 1)).column)
    }

    @Test
    fun `findings cannot invent an empty source location or identity`() {
        val finding = Finding("id", FindingKind.UNVERIFIABLE_CHANGE, "rule", "cannot prove", listOf(location()))
        assertFailsWith<IllegalArgumentException> { finding.copy(id = "") }
        assertFailsWith<IllegalArgumentException> { finding.copy(ruleId = "") }
        assertFailsWith<IllegalArgumentException> { finding.copy(message = "") }
        assertFailsWith<IllegalArgumentException> { finding.copy(locations = emptyList()) }
    }

    @Test
    fun `configuration cannot disable analysis with zero budgets`() {
        val config = EngineConfiguration(Path.of("cache"))
        assertFailsWith<IllegalArgumentException> { config.copy(buildTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { config.copy(buildTimeout = Duration.ofSeconds(-1)) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumScenarios = 0) }
    }

    private fun location() = SourceLocation(Revision.HEAD, "src/Example.java", SourceRange(1, 1), SourceRange(1, 2))
}
