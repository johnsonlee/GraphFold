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

class LambdaDifferenceWitnessTest {
    private val entry = "Application.quote()V"
    private val target = "Application.generated()V"
    private val other = "Other.changed()V"
    private val obligation = ProofObligation(entry, SymbolKind.METHOD, "Application", emptyList())
    private val dispatch = MethodAnalysisGap("unresolved stream", dispatchOnly = true)
    private val generated = setOf(MethodModifier.PRIVATE, MethodModifier.SYNTHETIC)

    @Test fun `owned independently observed lambda witnesses only its source obligation`() {
        val base = snapshot()
        val comparison = SnapshotComparison(base, changed(base))
        assertTrue(comparison.differences(obligation).contains(target))
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
        assertEquals(listOf(dispatch.message), comparison.resourceAnalysisGaps())
    }

    @Test fun `unrelated synthetic methods and ordinary references cannot borrow lambda ownership`() {
        val cases = listOf(
            snapshot(associated = false),
            snapshot(modifiers = setOf(MethodModifier.PRIVATE)),
            snapshot(modifiers = generated + MethodModifier.BRIDGE),
            snapshot(modifiers = setOf(MethodModifier.PUBLIC, MethodModifier.SYNTHETIC)),
        )
        cases.forEach { base ->
            assertEquals(listOf(dispatch.message), SnapshotComparison(base, changed(base)).analysisGaps(obligation))
        }
    }

    @Test fun `factory creation and speculative outgoing call do not establish lambda invocation`() {
        val base = snapshot(witnessed = false)
        assertEquals(listOf(dispatch.message), SnapshotComparison(base, changed(base)).analysisGaps(obligation))
    }

    @Test fun `association cannot borrow independent reachability from the other revision`() {
        val base = snapshot(witnessed = false)
        val head = changed(snapshot(associated = false))
        assertEquals(listOf(dispatch.message), SnapshotComparison(base, head).analysisGaps(obligation))
    }

    @Test fun `head observation can establish its own lambda difference without borrowing a base invocation`() {
        val base = snapshot(witnessed = false)
        val head = changed(snapshot(witnessed = true))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation).contains(target))
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
        assertEquals(listOf(dispatch.message), comparison.resourceAnalysisGaps())
    }

    @Test fun `a changed foreign synthetic implementation cannot establish ownership of the enclosing source method`() {
        val foreign = "Other.generated()V"
        val original = snapshot()
        val base = original.copy(
            methods = (original.methods - target) + mapOf(
                entry to original.methods.getValue(entry).copy(calls = setOf(foreign), lambdaTargets = setOf(foreign)),
                foreign to MethodSnapshot(foreign, "old foreign lambda", emptySet(), generated, emptySet()),
            ),
            externalEntries = setOf(foreign),
        )
        val changed = changed(base)
        val head = changed.copy(methods = changed.methods +
            (foreign to base.methods.getValue(foreign).copy(canonical = "changed foreign", referenceCanonical = "changed foreign")))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation).contains(foreign))
        assertEquals(listOf(dispatch.message), comparison.analysisGaps(obligation))
    }

    @Test fun `an unchanged owned lambda cannot borrow a change in an unrelated observed method`() {
        val base = snapshot()
        val head = base.copy(methods = base.methods +
            (other to base.methods.getValue(other).copy(canonical = "changed unrelated", referenceCanonical = "changed unrelated")))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation).isEmpty())
        assertEquals(listOf(other), comparison.wholeProgramDifferences())
        assertEquals(listOf(dispatch.message), comparison.analysisGaps(obligation))
    }

    @Test fun `a library method reference without an application body cannot supply an owned lambda difference`() {
        // String::trim is a real LambdaMetafactory implementation handle, but its library body need not be expanded.
        val library = "java.lang.String.trim()Ljava/lang/String;"
        val original = snapshot()
        val base = original.copy(methods = (original.methods - target) +
            (entry to original.methods.getValue(entry).copy(calls = setOf(library), lambdaTargets = setOf(library))),
            externalEntries = emptySet())
        val head = changed(base)
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation).isEmpty())
        assertEquals(listOf(other), comparison.wholeProgramDifferences())
        assertEquals(listOf(dispatch.message), comparison.analysisGaps(obligation))
    }

    @Test fun `lambda witness keeps extraction and provenance failures`() {
        val bodyGap = MethodAnalysisGap("missing body")
        val base = snapshot().copy(methodDiagnostics = mapOf(entry to listOf(dispatch, bodyGap)))
        assertEquals(listOf(bodyGap.message), SnapshotComparison(base, changed(base)).analysisGaps(obligation))
        val gaps = SnapshotComparison(base, changed(base), originsVerified = false).analysisGaps(obligation)
        assertTrue(dispatch.message in gaps && bodyGap.message in gaps)
    }

    private fun snapshot(
        associated: Boolean = true, witnessed: Boolean = true, modifiers: Set<MethodModifier> = generated,
    ): ProgramSnapshot {
        val methods = listOf(
            MethodSnapshot(entry, "factory", setOf(target), setOf(MethodModifier.PUBLIC), emptySet(),
                lambdaTargets = if (associated) setOf(target) else emptySet()),
            MethodSnapshot(target, "old lambda", emptySet(), modifiers, emptySet()),
            MethodSnapshot(other, "old unrelated", emptySet(), setOf(MethodModifier.PUBLIC), emptySet()),
        ).associateBy { it.identity }
        return ProgramSnapshot(methods, mapOf("Application" to "class", "Other" to "class"), emptyList(), emptyList(),
            entryClasses = setOf("Application", "Other"), externalEntries = if (witnessed) setOf(target) else emptySet(),
            methodDiagnostics = mapOf(entry to listOf(dispatch)))
    }

    private fun changed(base: ProgramSnapshot): ProgramSnapshot = base.copy(methods = base.methods.mapValues { (key, method) ->
        if (key == target || key == other) method.copy(canonical = "changed", referenceCanonical = "changed") else method
    })
}
