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

class InterfaceInitializationWitnessTest {
    private val entry = "Application.run()V"
    private val method = "Marker.value()I"
    private val other = "Marker.other()I"
    private val gap = MethodAnalysisGap("unresolved output", globalObservation = true, dispatchOnly = true)
    private val obligation = ProofObligation(method, SymbolKind.METHOD, "Marker", emptyList())

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `sole first default addition or removal witnesses exact initialization difference`(removed: Boolean) {
        val empty = snapshot(emptySet(), emptySet())
        val activated = snapshot(setOf(method), setOf("Marker"))
        val comparison = if (removed) SnapshotComparison(activated, empty) else SnapshotComparison(empty, activated)
        assertTrue(!comparison.isUnreachable(obligation), "An uninvoked added declaration remains production code")
        assertTrue(comparison.differences(obligation).any { it.contains("Class initialization dependencies") })
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
        assertEquals(listOf(gap.message), comparison.resourceAnalysisGaps())
        val unrelated = obligation.copy(identity = other)
        assertEquals(listOf(gap.message), comparison.analysisGaps(unrelated))
        assertEquals(listOf(gap.message), comparison.analysisGaps(obligation.copy(kind = SymbolKind.FIELD)))
    }

    @Test fun `abstract to default transition uses actual declarations without requiring a method addition`() {
        val empty = snapshot(emptySet(), emptySet())
        val before = empty.copy(methods = empty.methods + (method to body(method).copy(modifiers = setOf(MethodModifier.ABSTRACT))))
        val after = snapshot(setOf(method), setOf("Marker"))
        assertTrue(SnapshotComparison(before, after).analysisGaps(obligation).isEmpty())
    }

    @Test fun `unchanged hierarchy and one exact causal default are required`() {
        val empty = snapshot(emptySet(), emptySet())
        val activated = snapshot(setOf(method), setOf("Marker"))
        val second = snapshot(setOf(method, other), setOf("Marker"))
        for ((before, after) in listOf(
            empty to second,
            snapshot(setOf(other), setOf("Marker")) to second,
            empty to activated.copy(classShapes = activated.classShapes + ("Application" to "changed interfaces")),
            empty to snapshot(setOf(method), setOf("OtherMarker")),
            empty to snapshot(setOf(method), emptySet()),
            empty to activated.copy(initializationTriggerMethods = emptySet()),
            snapshot(emptySet(), setOf("Marker")) to activated,
            empty.copy(initializationDependencies = emptyMap()) to activated,
        )) {
            val unobserved = "OtherMarker.uncontrolled()V"
            val changed = after.copy(methods = after.methods + (unobserved to body(unobserved)))
            assertEquals(listOf(gap.message), SnapshotComparison(before, changed).analysisGaps(obligation))
        }
        val metadata = activated.copy(declarations = activated.declarations + (method to "changed method annotation"),
            methods = activated.methods + ("OtherMarker.uncontrolled()V" to body("OtherMarker.uncontrolled()V")))
        assertEquals(listOf(gap.message), SnapshotComparison(activated, metadata).analysisGaps(obligation))
    }

    @Test fun `an unrelated default body cannot borrow activation caused by another interface`() {
        val before = snapshot(setOf(other), setOf("Marker"))
        val added = "OtherMarker.first()I"
        val after = snapshot(setOf(other, added), setOf("Marker", "OtherMarker"))
        val changed = after.methods.getValue(other).copy(canonical = "changed", referenceCanonical = "changed")
        val head = after.copy(methods = after.methods + (other to changed))
        assertEquals(listOf(gap.message), SnapshotComparison(before, head).analysisGaps(obligation.copy(identity = other)))
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `initializing class must be independently reached in the revision carrying the interface edge`(removed: Boolean) {
        val hidden = "Hidden.run()V"
        val original = snapshot(emptySet(), emptySet())
        val before = original.copy(methods = original.methods + (hidden to body(hidden)),
            initializationDependencies = mapOf("Hidden" to emptySet()))
        val active = snapshot(setOf(method), emptySet())
        val after = active.copy(methods = active.methods + (hidden to body(hidden)),
            initializationDependencies = mapOf("Hidden" to setOf("Marker")))
        for (calls in listOf(emptySet(), setOf(hidden))) {
            // The second case exposes a speculative target beneath the unresolved dispatch.
            val old = before.copy(methods = before.methods + (entry to before.methods.getValue(entry).copy(calls = calls)))
            val head = after.copy(methods = after.methods + (entry to after.methods.getValue(entry).copy(calls = calls)))
            val comparison = if (removed) SnapshotComparison(head, old) else SnapshotComparison(old, head)
            assertEquals(listOf(gap.message), comparison.analysisGaps(obligation))
        }
        val reachedBefore = before.copy(methods = before.methods + (entry to before.methods.getValue(entry).copy(calls = setOf(hidden))),
            methodDiagnostics = mapOf(hidden to listOf(gap)))
        val unreachableAfter = after.copy(methodDiagnostics = mapOf(entry to listOf(gap)))
        val wrongSide = if (removed) SnapshotComparison(unreachableAfter, reachedBefore)
            else SnapshotComparison(reachedBefore, unreachableAfter)
        assertEquals(listOf(gap.message), wrongSide.analysisGaps(obligation))
    }

    @Test fun `renaming a private interface trigger preserves initialization instead of inventing activation`() {
        val original = snapshot(setOf(method), setOf("Marker"))
        val before = original.copy(methods = original.methods +
            (method to original.methods.getValue(method).copy(modifiers = setOf(MethodModifier.PRIVATE))))
        val renamed = "Marker.renamed()I"
        val after = before.copy(methods = before.methods - method +
            (renamed to before.methods.getValue(method).copy(identity = renamed)), initializationTriggerMethods = setOf(renamed))
        val aligned = PrivateMemberAlignment(before, after).aligned()
        assertEquals(before.initializationTriggerMethods, aligned.initializationTriggerMethods)
        assertEquals(before.initializationDependencies, aligned.initializationDependencies)
        val comparison = SnapshotComparison(before, after)
        assertTrue(comparison.differences(obligation.copy(identity = renamed)).isEmpty())
        assertEquals(listOf(gap.message), comparison.analysisGaps(obligation.copy(identity = renamed)))
    }

    @Test fun `initialization witness retains body and provenance blockers`() {
        val before = snapshot(emptySet(), emptySet())
        val activated = snapshot(setOf(method), setOf("Marker"))
        val bodyGap = MethodAnalysisGap("missing body")
        val initializer = body("Marker.<clinit>()V").copy(modifiers = setOf(MethodModifier.STATIC))
        val after = activated.copy(methods = activated.methods + (initializer.identity to initializer),
            methodDiagnostics = mapOf(entry to listOf(gap), initializer.identity to listOf(bodyGap)))
        assertEquals(listOf(bodyGap.message), SnapshotComparison(before, after).analysisGaps(obligation))
        val unverified = SnapshotComparison(before, activated, originsVerified = false).analysisGaps(obligation)
        assertTrue(gap.message in unverified)
    }

    private fun snapshot(defaults: Set<String>, dependencies: Set<String>): ProgramSnapshot {
        val methods = (defaults + entry).associateWith(::body)
        return ProgramSnapshot(methods, mapOf("Application" to "class", "Marker" to "interface", "OtherMarker" to "interface"),
            emptyList(), emptyList(), entryClasses = setOf("Application"),
            classShapes = mapOf("Application" to "implements Marker and OtherMarker",
                "Marker" to "interface", "OtherMarker" to "interface"),
            initializationDependencies = mapOf("Application" to dependencies),
            methodDiagnostics = mapOf(entry to listOf(gap)), initializationTriggerMethods = defaults)
    }

    private fun body(identity: String) = MethodSnapshot(identity, "body", emptySet(), setOf(MethodModifier.PUBLIC), emptySet())
}
