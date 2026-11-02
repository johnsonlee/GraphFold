package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResourceEntryAnalysisTest {
    private val unknown = ResourceRead(null, null)

    @Test fun `missing inherited or exposed field inventory cannot prove entry resources absent`() {
        val analysis = DependencyResourceAnalysis(DefaultGraph.Builder().build(), emptyMap(), emptyMap(), emptyMap(),
            inheritedMethods = mapOf("MissingHierarchy" to null, "MissingFields" to emptySet()),
            entryInitializers = mapOf("MissingHierarchy" to emptySet(), "MissingFields" to null))
        listOf("MissingHierarchy", "MissingFields", "Unconfigured").forEach {
            assertEquals(listOf(unknown), analysis.entryReads(it), it)
        }
    }

    @Test fun `exposed static fields require known declaration initialization and ancestor effects`() {
        val analysis = DependencyResourceAnalysis(DefaultGraph.Builder().build(), emptyMap(), emptyMap(),
            mapOf("Known" to emptySet(), "Child" to setOf("UnknownParent")),
            inheritedMethods = listOf("Unknown", "Child", "Known", "Platform").associateWith { emptySet() },
            entryInitializers = mapOf("Unknown" to setOf("Unknown"), "Child" to setOf("Child"),
                "Known" to setOf("Known"), "Platform" to setOf("java.lang.System")))
        assertEquals(listOf(unknown), analysis.entryReads("Unknown"))
        assertEquals(listOf(unknown), analysis.entryReads("Child"))
        assertTrue(analysis.entryReads("Known").isEmpty())
        assertTrue(analysis.entryReads("Platform").isEmpty())
    }
}
