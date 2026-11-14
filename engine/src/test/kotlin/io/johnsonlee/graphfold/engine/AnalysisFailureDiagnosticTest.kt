package io.johnsonlee.graphfold.engine

import java.io.IOException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
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

    @Test fun `missing source evidence is diagnosed but cancellation reaches the analysis boundary`() {
        val missing = sourceFailureDiagnostic(Revision.HEAD, "src/App.java", IOException("source unavailable"))
        assertEquals(DiagnosticCode.SOURCE_RESOLUTION_FAILED, missing.code)
        assertEquals(AnalysisStage.SOURCE, missing.stage)
        assertTrue(missing.blocking)
        assertTrue("HEAD source src/App.java" in missing.message && "source unavailable" in missing.message)
        val interruption = InterruptedException("caller cancelled")
        val propagated = assertFailsWith<InterruptedException> {
            sourceFailureDiagnostic(Revision.HEAD, "src/App.java", interruption)
        }
        assertSame(interruption, propagated)
        try {
            val cancelled = analysisFailureDiagnostic(AnalysisStage.SOURCE, propagated)
            assertEquals(DiagnosticCode.ANALYSIS_CANCELLED, cancelled.code)
            assertEquals(AnalysisStage.SOURCE, cancelled.stage)
            assertTrue(cancelled.blocking)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun `analysis failures retain stage and message or exception type without claiming success`() {
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
