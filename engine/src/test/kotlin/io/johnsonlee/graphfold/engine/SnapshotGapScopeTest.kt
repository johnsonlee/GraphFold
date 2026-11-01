package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.graphite.MethodSnapshot
import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotGapScopeTest {
    @Test fun `unrelated bounded external gap does not invalidate the changed method`() {
        val snapshot = snapshot(method("Changed.run()V"), method("Other.run()V", "Opaque.read()V"))
            .copy(methodDiagnostics = mapOf("Other.run()V" to listOf(gap)))
        val comparison = SnapshotComparison(snapshot, snapshot)
        assertTrue(comparison.analysisGaps(obligation("Changed.run()V")).isEmpty())
        assertEquals(listOf(gap.message), comparison.analysisGaps())
    }

    @Test fun `gaps in transitive dependencies and reachable callers block`() {
        val snapshot = snapshot(method("Changed.run()V", "Helper.work()V"),
            method("Helper.work()V", "Opaque.read()V"), method("Caller.run()V", "Changed.run()V", "Opaque.read()V"))
            .copy(methodDiagnostics = mapOf("Helper.work()V" to listOf(gap),
                "Caller.run()V" to listOf(gap.copy(message = "caller"))))
        assertEquals(listOf("caller", gap.message), SnapshotComparison(snapshot, snapshot).analysisGaps(obligation("Changed.run()V")))
    }

    @Test fun `dead methods and folded away opaque calls do not invalidate proof`() {
        val hidden = method("Other.dead()V", "Opaque.read()V").copy(modifiers = setOf(MethodModifier.PRIVATE))
        val snapshot = snapshot(method("Changed.run()V"), hidden)
            .copy(methodDiagnostics = mapOf(hidden.identity to listOf(gap.copy(globalObservation = true)),
                "Changed.run()V" to listOf(gap.copy(globalObservation = true))))
        assertTrue(SnapshotComparison(snapshot, snapshot).analysisGaps().isEmpty())
    }

    @Test fun `base-only opaque calls remain active after removal in head`() {
        val base = snapshot(method("Changed.run()V", "Opaque.read()V"))
            .copy(methodDiagnostics = mapOf("Changed.run()V" to listOf(gap)))
        val head = base.copy(methods = mapOf("Changed.run()V" to method("Changed.run()V")))
        assertEquals(listOf(gap.message), SnapshotComparison(base, head).analysisGaps(obligation("Changed.run()V")))
    }

    @Test fun `unbounded dynamic reflection can discover changed private code in another owner`() {
        val snapshot = snapshot(method("Other.inspect()V", "Opaque.read()V"),
            method("Changed.hidden()V").copy(modifiers = setOf(MethodModifier.PRIVATE)))
            .copy(methodDiagnostics = mapOf("Other.inspect()V" to listOf(gap.copy(globalObservation = true))))
        assertEquals(listOf(gap.message), SnapshotComparison(snapshot, snapshot).analysisGaps(obligation("Changed.hidden()V")))
    }

    @Test fun `bounded cross-owner consumer observes private declarations without an application call edge`() {
        val hidden = method("Changed.hidden()V").copy(modifiers = setOf(MethodModifier.PRIVATE))
        val snapshot = snapshot(method("Other.inspect()V", "Opaque.read()V"), hidden)
            .copy(methodDiagnostics = mapOf("Other.inspect()V" to listOf(gap.copy(observedOwners = setOf("Changed")))))
        assertEquals(listOf(gap.message), SnapshotComparison(snapshot, snapshot).analysisGaps(obligation(hidden.identity)))
    }

    @Test fun `opaque malformed body without a callee remains a relevant gap`() {
        val snapshot = snapshot(method("Changed.run()V"))
            .copy(methodDiagnostics = mapOf("Changed.run()V" to listOf(gap.copy(callee = null))))
        assertEquals(listOf(gap.message), SnapshotComparison(snapshot, snapshot).analysisGaps())
    }

    @Test fun `same-owner private state reaches the caller of a public bridge`() {
        val snapshot = snapshot(method("Changed.hidden()V").copy(modifiers = setOf(MethodModifier.PRIVATE)),
            method("Changed.bridge()V"), method("Caller.run()V", "Changed.bridge()V", "Opaque.read()V"))
            .copy(methodDiagnostics = mapOf("Caller.run()V" to listOf(gap)))
        assertEquals(listOf(gap.message), SnapshotComparison(snapshot, snapshot).analysisGaps(obligation("Changed.hidden()V")))
    }

    private val gap = MethodAnalysisGap("opaque access", "Opaque.read()V")
    private fun obligation(identity: String) = ProofObligation(identity, SymbolKind.METHOD,
        identity.substringBefore('(').substringBeforeLast('.'), emptyList())
    private fun method(identity: String, vararg calls: String) =
        MethodSnapshot(identity, "body", calls.toSet(), setOf(MethodModifier.PUBLIC), emptySet())
    private fun snapshot(vararg methods: MethodSnapshot) = ProgramSnapshot(methods.associateBy { it.identity },
        methods.associate { it.identity.substringBefore('(').substringBeforeLast('.') to "class" }, emptyList(), emptyList())
}
