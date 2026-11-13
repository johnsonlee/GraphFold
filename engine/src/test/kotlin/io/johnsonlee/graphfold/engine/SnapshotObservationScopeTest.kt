package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotObservationScopeTest {
    @Test
    fun `unrelated opaque escape does not invalidate equivalent reachable method bodies`() {
        val base = snapshot(method(TARGET), method(OBSERVER))
        val comparison = SnapshotComparison(base, base)
        assertTrue(comparison.differences(obligation()).isEmpty())
        assertTrue(comparison.analysisGaps(obligation()).isEmpty())
    }

    @Test
    fun `unrelated opaque escape does not hide a real reachable body difference`() {
        val base = snapshot(method(TARGET, "return 1"), method(OBSERVER))
        val head = snapshot(method(TARGET, "return 2"), method(OBSERVER))
        val comparison = SnapshotComparison(base, head)
        assertEquals(listOf(TARGET), comparison.differences(obligation()))
        assertTrue(comparison.analysisGaps(obligation()).isEmpty())
    }

    @Test
    fun `opaque gap in direct caller remains relevant`() {
        val base = snapshot(method(TARGET), method(OBSERVER, calls = setOf(TARGET)))
        assertEquals(listOf(GAP), SnapshotComparison(base, base).analysisGaps(obligation()))
    }

    @Test
    fun `opaque gap in called dependency remains relevant`() {
        val base = snapshot(method(TARGET, calls = setOf(OBSERVER)), method(OBSERVER))
        assertEquals(listOf(GAP), SnapshotComparison(base, base).analysisGaps(obligation()))
    }

    @Test
    fun `bounded observation of target owner remains relevant without call edges`() {
        val base = snapshot(method(TARGET), method(OBSERVER)).copy(methodDiagnostics = mapOf(
            OBSERVER to listOf(MethodAnalysisGap(GAP, observedOwners = setOf("Target"))),
        ))
        assertEquals(listOf(GAP), SnapshotComparison(base, base).analysisGaps(obligation()))
    }

    @Test
    fun `unrelated bounded observation stays out of ordinary method proof`() {
        val base = snapshot(method(TARGET), method(OBSERVER)).copy(methodDiagnostics = mapOf(
            OBSERVER to listOf(MethodAnalysisGap(GAP, observedOwners = setOf("Other"))),
        ))
        assertTrue(SnapshotComparison(base, base).analysisGaps(obligation()).isEmpty())
    }

    @Test
    fun `private callback without graph callers keeps global opaque access`() {
        val callback = "Target.callback()V"
        val base = snapshot(method(TARGET), method(OBSERVER), method(callback, isPrivate = true))
        val comparison = SnapshotComparison(base, base)
        assertTrue(!comparison.isUnreachable(obligation(callback)))
        assertEquals(listOf(GAP), comparison.analysisGaps(obligation(callback)))
    }

    @Test
    fun `field and class obligations retain global observations of hidden state`() {
        val base = snapshot(method(TARGET), method(OBSERVER))
        val comparison = SnapshotComparison(base, base)
        val field = ProofObligation("Target.hidden:I", SymbolKind.FIELD, "Target", emptyList())
        val type = ProofObligation("Target", SymbolKind.CLASS, "Target", emptyList())
        assertEquals(listOf(GAP), comparison.analysisGaps(field))
        assertEquals(listOf(GAP), comparison.analysisGaps(type))
    }

    @Test
    fun `whole program resource proof retains global observations`() {
        val base = snapshot(method(TARGET), method(OBSERVER))
        assertEquals(listOf(GAP), SnapshotComparison(base, base).analysisGaps())
    }

    @Test
    fun `private member rename retains opaque name observation`() {
        val old = method("Target.before()V", isPrivate = true)
        val renamed = method("Target.after()V", isPrivate = true)
        val base = snapshot(method(TARGET, calls = setOf(old.identity)), method(OBSERVER), old)
        val head = snapshot(method(TARGET, calls = setOf(renamed.identity)), method(OBSERVER), renamed)
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation()).isEmpty())
        assertEquals(listOf(GAP), comparison.analysisGaps(obligation()))
    }

    @Test
    fun `raw anonymous renumbering retains observation without invocation edges to its owner`() {
        val base = snapshot(method(TARGET), method(OBSERVER)).copy(
            originalOwners = mapOf("Target\$origin" to "Target\$1"),
        )
        val head = base.copy(originalOwners = mapOf("Target\$origin" to "Target\$2"))
        assertEquals(listOf(GAP), SnapshotComparison(base, head).analysisGaps(obligation()))
    }

    @Test
    fun `added anonymous owner retains observable nest metadata outside folded call graph`() {
        val base = snapshot(method(TARGET), method(OBSERVER))
        val head = base.copy(originalOwners = mapOf("Target\$origin" to "Target\$1"))
        assertEquals(listOf(GAP), SnapshotComparison(base, head).analysisGaps(obligation()))
    }

    @Test
    fun `removed anonymous owner retains observable nest metadata outside folded call graph`() {
        val head = snapshot(method(TARGET), method(OBSERVER))
        val base = head.copy(originalOwners = mapOf("Target\$origin" to "Target\$1"))
        assertEquals(listOf(GAP), SnapshotComparison(base, head).analysisGaps(obligation()))
    }

    @Test
    fun `stable anonymous owner mapping does not poison ordinary method proof`() {
        val base = snapshot(method(TARGET), method(OBSERVER)).copy(
            originalOwners = mapOf("Target\$origin" to "Target\$1"),
        )
        assertTrue(SnapshotComparison(base, base).analysisGaps(obligation()).isEmpty())
    }

    @Test
    fun `erased unread constructor assignment keeps global state observation`() {
        val constructor = "Target.<init>()V"
        val before = method(constructor, "this.hidden = 1; return").copy(referenceCanonical = "return")
        val after = before.copy(canonical = "this.hidden = 2; return")
        val base = snapshot(before, method(OBSERVER)).copy(ignoredFields = setOf("Target.hidden:I"))
        val head = snapshot(after, method(OBSERVER)).copy(ignoredFields = base.ignoredFields)
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation(constructor)).isEmpty())
        assertEquals(listOf(GAP), comparison.analysisGaps(obligation(constructor)))
    }

    @Test
    fun `unchanged ignored initializer state does not poison ordinary method proof`() {
        val constructor = method("Target.<init>()V", "this.hidden = 1; return")
            .copy(referenceCanonical = "return")
        val base = snapshot(method(TARGET), constructor, method(OBSERVER))
            .copy(ignoredFields = setOf("Target.hidden:I"))
        assertTrue(SnapshotComparison(base, base).analysisGaps(obligation()).isEmpty())
    }

    @Test
    fun `folded away opaque call stays inactive even for global field proof`() {
        val base = snapshot(method(TARGET), method(OBSERVER)).copy(methodDiagnostics = mapOf(
            OBSERVER to listOf(MethodAnalysisGap(GAP, callee = "Opaque.inspect()V", globalObservation = true)),
        ))
        val field = ProofObligation("Target.hidden:I", SymbolKind.FIELD, "Target", emptyList())
        assertTrue(SnapshotComparison(base, base).analysisGaps(field).isEmpty())
    }

    private fun obligation(identity: String = TARGET) = ProofObligation(
        identity, SymbolKind.METHOD, identity.substringBefore('(').substringBeforeLast('.'), emptyList(),
    )

    private fun method(
        identity: String, body: String = "return", calls: Set<String> = emptySet(), isPrivate: Boolean = false,
    ) = MethodSnapshot(identity, body, calls,
        setOf(if (isPrivate) MethodModifier.PRIVATE else MethodModifier.PUBLIC), emptySet())

    private fun snapshot(vararg methods: MethodSnapshot) = ProgramSnapshot(
        methods.associateBy { it.identity },
        methods.associate { it.identity.substringBefore('(').substringBeforeLast('.') to "class" },
        emptyList(), emptyList(), methodDiagnostics = mapOf(OBSERVER to listOf(
            MethodAnalysisGap(GAP, globalObservation = true),
        )),
    )

    private companion object {
        const val TARGET = "Target.run()V"
        const val OBSERVER = "Telemetry.record()V"
        const val GAP = "Opaque object graph inspection"
    }
}
