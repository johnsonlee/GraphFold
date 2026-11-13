package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DispatchGapComparisonTest {
    private val entry = "Application.run()V"
    private val hidden = "Application.unseen()V"
    private val obligation = ProofObligation(entry, SymbolKind.METHOD, "Application", emptyList())
    private val dispatch = MethodAnalysisGap("unresolved callback dispatch", globalObservation = true, dispatchOnly = true)

    @Test fun `unchanged implementations outside the compared closure discharge only dispatch uncertainty`() {
        val snapshot = snapshot()
        val comparison = SnapshotComparison(snapshot, snapshot)
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
        assertTrue(comparison.analysisGaps().isEmpty())
        assertTrue(comparison.differences(obligation).isEmpty())
        assertTrue(comparison.wholeProgramDifferences().isEmpty())
        assertEquals(listOf(dispatch.message), comparison.resourceAnalysisGaps())
    }

    @Test fun `an unseen changed implementation outside the closure preserves the dispatch gap`() {
        val base = snapshot()
        val head = changed(base, hidden)
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation).isEmpty(), "The ordinary graph closure cannot see the changed callback")
        assertEquals(listOf(dispatch.message), comparison.analysisGaps(obligation))
        assertEquals(listOf(dispatch.message), comparison.analysisGaps())
    }

    @Test fun `discharging dispatch uncertainty does not erase a changed implementation within the closure`() {
        val base = snapshot()
        val head = changed(base, entry)
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
        assertEquals(listOf(entry), comparison.differences(obligation))
        assertEquals(listOf(entry), comparison.wholeProgramDifferences())
    }

    @Test fun `known dispatch edges can cover the changed private callback while still reporting its difference`() {
        val original = snapshot()
        val method = original.methods.getValue(entry)
        val base = original.copy(methods = original.methods + (entry to method.copy(calls = method.calls + hidden)))
        val comparison = SnapshotComparison(base, changed(base, hidden))
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
        assertEquals(listOf(hidden), comparison.differences(obligation))
    }

    @Test fun `added or removed unseen implementation prevents dispatch closure proof`() {
        val full = snapshot()
        val missing = full.copy(methods = full.methods - hidden)
        listOf(full to missing, missing to full).forEach { (base, head) ->
            val comparison = SnapshotComparison(base, head)
            assertEquals(listOf(dispatch.message), comparison.analysisGaps(obligation))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["incomplete method body", "inventory cannot resolve owner", "recovered interception"])
    fun `independent body inventory and recovery gaps survive a complete dispatch closure`(message: String) {
        val snapshot = snapshot(listOf(dispatch, MethodAnalysisGap(message)))
        val comparison = SnapshotComparison(snapshot, snapshot)
        assertEquals(listOf(message), comparison.analysisGaps(obligation))
        assertEquals(listOf(message), comparison.analysisGaps())
    }

    @Test fun `changed metadata prevents closure proof even when all method bodies are equal`() {
        val base = snapshot()
        val head = base.copy(declarations = base.declarations + ("Application" to "different class metadata"))
        assertEquals(listOf(dispatch.message), SnapshotComparison(base, head).analysisGaps(obligation))
    }

    @Test fun `unverified origins cannot justify a complete dispatch closure`() {
        val snapshot = snapshot()
        val gaps = SnapshotComparison(snapshot, snapshot, originsVerified = false).analysisGaps(obligation)
        assertEquals(1, gaps.size)
        assertTrue(dispatch.message in gaps)
    }

    @Test fun `normalized equal bodies cannot hide raw differences behind dispatch closure`() {
        val base = snapshot()
        val before = base.methods.getValue(hidden)
        val head = base.copy(methods = base.methods + (hidden to before.copy(canonical = "different raw body")))
        assertEquals(listOf(dispatch.message), SnapshotComparison(base, head).analysisGaps(obligation))
    }

    @Test fun `global inventory failure prevents dispatch closure proof`() {
        val base = snapshot()
        val head = base.copy(diagnostics = listOf("incomplete application inventory"))
        assertEquals(listOf(dispatch.message), SnapshotComparison(base, head).analysisGaps(obligation))
    }

    @Test fun `opaque caller preserves a changed private callback in another owner despite missing edges`() {
        val base = crossOwnerSnapshot()
        val identity = "Hidden.callback()V"
        val obligation = ProofObligation(identity, SymbolKind.METHOD, "Hidden", emptyList())
        val comparison = SnapshotComparison(base, changed(base, identity))
        assertTrue(!comparison.isUnreachable(obligation), "No call edge is needed to compare an explicit production declaration")
        assertEquals(listOf(identity), comparison.differences(obligation))
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
    }

    @Test fun `unchanged private callback in another owner permits an independent closure proof`() {
        val snapshot = crossOwnerSnapshot()
        val obligation = ProofObligation("Hidden.callback()V", SymbolKind.METHOD, "Hidden", emptyList())
        val comparison = SnapshotComparison(snapshot, snapshot)
        assertTrue(!comparison.isUnreachable(obligation))
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
    }

    @Test fun `known method difference remains a failure when another changed method lies outside its closure`() {
        val base = snapshot()
        val head = changed(changed(base, entry), hidden)
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
        assertEquals(listOf(entry), comparison.differences(obligation))
        val unseen = ProofObligation(hidden, SymbolKind.METHOD, "Application", emptyList())
        assertEquals(listOf(hidden), comparison.differences(unseen))
        assertEquals(listOf(dispatch.message), comparison.analysisGaps(unseen),
            "The other obligation's difference must never prove the unseen change controlled")
        assertEquals(listOf(dispatch.message), comparison.resourceAnalysisGaps())
    }

    @Test fun `known method witness does not discharge extraction gaps or unverified provenance`() {
        val bodyGap = MethodAnalysisGap("missing body")
        val base = snapshot(listOf(dispatch, bodyGap))
        val head = changed(changed(base, entry), hidden)
        assertEquals(listOf(bodyGap.message), SnapshotComparison(base, head).analysisGaps(obligation))
        val unverified = SnapshotComparison(base, head, originsVerified = false).analysisGaps(obligation)
        assertTrue(dispatch.message in unverified)
        assertTrue(bodyGap.message in unverified)
    }

    @Test fun `a changed initialization dependency is an independent surviving witness`() {
        val base = snapshot().copy(initializationDependencies = mapOf("Application" to setOf("java.lang.Object")))
        val head = base.copy(initializationDependencies = mapOf("Application" to setOf("java.lang.Object", "DefaultApi")))
        val comparison = SnapshotComparison(base, head)
        val type = ProofObligation("Application", SymbolKind.CLASS, "Application", emptyList())
        assertTrue(comparison.analysisGaps(type).isEmpty())
        assertTrue(comparison.differences(type).single().contains("Class initialization dependencies"))
        assertEquals(listOf(dispatch.message), comparison.resourceAnalysisGaps())
    }

    @Test fun `changed speculative dispatch target is not an independent difference witness`() {
        val original = crossOwnerSnapshot()
        val target = "Hidden.callback()V"
        val entryBody = original.methods.getValue(entry)
        val other = "Hidden.other()V"
        val otherBody = original.methods.getValue(target).copy(identity = other)
        for (unresolved in listOf(target, "Function.run()V")) {
            val base = original.copy(
                methods = original.methods + (entry to entryBody.copy(calls = setOf(target, unresolved))) + (other to otherBody),
                methodDiagnostics = mapOf(entry to listOf(dispatch.copy(callee = unresolved))))
            val head = changed(changed(base, target), other)
            val targetChange = ProofObligation(target, SymbolKind.METHOD, "Hidden", emptyList())
            val comparison = SnapshotComparison(base, head)
            assertEquals(listOf(target), comparison.differences(targetChange), "Ordinary reachability includes the declared target")
            assertEquals(listOf(dispatch.message), comparison.analysisGaps(targetChange),
                "Neither a declared target nor a derived target may borrow an incomplete dispatch edge: $unresolved")
        }
    }

    @Test fun `initialization changes reached only through unresolved dispatch are not independent witnesses`() {
        val original = crossOwnerSnapshot()
        val target = "Hidden.callback()V"
        val body = original.methods.getValue(entry)
        val base = original.copy(methods = original.methods + (entry to body.copy(calls = setOf(target))),
            initializationDependencies = mapOf("Hidden" to setOf("java.lang.Object")))
        val head = base.copy(initializationDependencies = mapOf("Hidden" to setOf("java.lang.Object", "UnknownDefault")))
        val targetChange = ProofObligation("Hidden", SymbolKind.CLASS, "Hidden", emptyList())
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(targetChange).single().contains("Class initialization dependencies"))
        assertEquals(listOf(dispatch.message), comparison.analysisGaps(targetChange))
    }

    @Test fun `an ancestor obligation cannot borrow its child's changed initialization edge`() {
        val base = crossOwnerSnapshot().copy(externalEntries = setOf("Hidden.callback()V"),
            initializationDependencies = mapOf("Application" to setOf("java.lang.Object")))
        val head = base.copy(initializationDependencies = mapOf("Application" to setOf("java.lang.Object", "Hidden")))
        val ancestor = ProofObligation("Hidden", SymbolKind.CLASS, "Hidden", emptyList())
        assertTrue(SnapshotComparison(base, head).differences(ancestor).isNotEmpty())
        assertEquals(listOf(dispatch.message), SnapshotComparison(base, head).analysisGaps(ancestor))
    }

    @Test fun `initialization edge witnesses require independent reachability on the changed edge's revision`() {
        val original = crossOwnerSnapshot()
        val target = "Hidden.callback()V"
        val entryBody = original.methods.getValue(entry)
        val base = original.copy(methods = original.methods + (entry to entryBody.copy(calls = setOf(target))),
            methodDiagnostics = mapOf(target to listOf(dispatch)),
            initializationDependencies = mapOf("Hidden" to setOf("java.lang.Object")))
        val head = base.copy(methods = base.methods + (entry to entryBody.copy(calls = emptySet(),
            canonical = "removed call", referenceCanonical = "removed call")),
            initializationDependencies = mapOf("Hidden" to setOf("java.lang.Object", "DefaultApi")))
        val owner = ProofObligation("Hidden", SymbolKind.CLASS, "Hidden", emptyList())
        for ((before, after) in listOf(base to head, head to base)) {
            assertEquals(listOf(dispatch.message), SnapshotComparison(before, after).analysisGaps(owner))
        }
    }

    @Test fun `removing an independently reached owner's initialization edge is still a witness`() {
        val base = snapshot().copy(initializationDependencies = mapOf("Application" to setOf("java.lang.Object", "DefaultApi")))
        val head = base.copy(initializationDependencies = mapOf("Application" to setOf("java.lang.Object")))
        val type = ProofObligation("Application", SymbolKind.CLASS, "Application", emptyList())
        assertTrue(SnapshotComparison(base, head).analysisGaps(type).isEmpty())
    }

    @Test fun `a method or field cannot borrow an unrelated initialization edge change of the same owner`() {
        val base = snapshot().copy(initializationDependencies = mapOf("Application" to setOf("java.lang.Object")))
        val head = base.copy(initializationDependencies = mapOf("Application" to setOf("java.lang.Object", "DefaultApi")))
        val field = ProofObligation("Application.value", SymbolKind.FIELD, "Application", emptyList())
        for (member in listOf(obligation, field)) {
            assertEquals(listOf(dispatch.message), SnapshotComparison(base, head).analysisGaps(member))
        }
    }

    private fun crossOwnerSnapshot(): ProgramSnapshot {
        val original = snapshot()
        val identity = "Hidden.callback()V"
        val callback = original.methods.getValue(hidden).copy(identity = identity)
        return original.copy(methods = original.methods - hidden + (identity to callback),
            declarations = original.declarations + ("Hidden" to "private class"))
    }

    private fun snapshot(gaps: List<MethodAnalysisGap> = listOf(dispatch)): ProgramSnapshot {
        val methods = listOf(
            MethodSnapshot(entry, "entry body", setOf("Callback.run()V"), setOf(MethodModifier.PUBLIC), emptySet()),
            MethodSnapshot(hidden, "hidden body", emptySet(), setOf(MethodModifier.PRIVATE), emptySet()),
        ).associateBy { it.identity }
        return ProgramSnapshot(methods, mapOf("Application" to "public class"), emptyList(), emptyList(),
            entryClasses = setOf("Application"), methodDiagnostics = mapOf(entry to gaps))
    }

    private fun changed(snapshot: ProgramSnapshot, identity: String): ProgramSnapshot {
        val method = snapshot.methods.getValue(identity).copy(canonical = "changed body", referenceCanonical = "changed body")
        return snapshot.copy(methods = snapshot.methods + (identity to method))
    }
}
