package io.johnsonlee.graphfold.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisFailureDiagnosticTest {
    @Test fun `inventory diagnostics preserve revision while source diagnostics retain every unresolved declaration`() {
        val graphs = graphDiagnostics(listOf("missing receipt", "duplicate owner"), listOf("recovery"))
        assertEquals(listOf("base: missing receipt", "base: duplicate owner", "head: recovery"), graphs.map { it.message })
        assertTrue(graphs.all { it.blocking && it.stage == AnalysisStage.GRAPH && it.code == DiagnosticCode.INCOMPLETE_GRAPH })
        assertTrue(graphDiagnostics(emptyList(), emptyList()).isEmpty())
        val source = io.johnsonlee.graphfold.engine.source.IndexedSource("Example.java", "", emptyList(), listOf("unresolved type"))
        for ((base, head) in listOf(source to null, null to source)) {
            val diagnostic = sourceDiagnostics(base, head).single()
            assertEquals("unresolved type", diagnostic.message)
            assertEquals(AnalysisStage.SOURCE, diagnostic.stage)
            assertEquals(DiagnosticCode.SOURCE_RESOLUTION_FAILED, diagnostic.code)
            assertTrue(diagnostic.blocking)
        }
        assertEquals(2, sourceDiagnostics(source, source).size)
        assertTrue(sourceDiagnostics(null, null).isEmpty())
    }

    @Test fun `failed adapters retain stage and message or exception type without claiming success`() {
        for ((failure, message) in listOf(IllegalStateException("broken") to "broken",
            IllegalStateException() to "IllegalStateException")) {
            val diagnostic = analysisFailureDiagnostic(AnalysisStage.GRAPH, failure)
            assertEquals(AnalysisStage.GRAPH, diagnostic.stage)
            assertEquals(DiagnosticCode.ANALYSIS_FAILED, diagnostic.code)
            assertEquals(message, diagnostic.message)
            assertTrue(diagnostic.blocking)
        }
    }
}
