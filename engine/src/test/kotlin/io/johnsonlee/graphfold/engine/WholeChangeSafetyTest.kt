package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import io.johnsonlee.graphfold.engine.safety.PropertyProof
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comparator mechanics only: these hand-built summaries are not engine-produced safety evidence. */
class WholeChangeSafetyTest {
    private val identity = "Example.value()I"
    private val safe = summary(identity)

    @Test
    fun `complete relational proof accepts while retaining uncontrolled classification and cited assumptions`() {
        val result = assess(safe, safe)
        assertEquals(ControlClassification.UNCONTROLLED, result.control)
        assertEquals(AcceptanceDecision.ACCEPTED, result.decision)
        assertEquals(AcceptanceBasis.STATIC_SAFETY, result.basis)
        assertEquals(7, result.safety.size)
        for (revision in listOf("BASE", "HEAD")) {
            val properties = result.safety.filter { it.subject == "$revision $identity" }
            assertEquals(setOf(SafetyClaim.EFFECT_FREE, SafetyClaim.NONTHROWING, SafetyClaim.TERMINATING),
                properties.map { it.claim }.toSet())
            properties.forEach {
                assertEquals(SafetyStatus.PROVEN, it.status)
                assertEquals(listOf("fixture evidence"), it.evidence)
                assertEquals(listOf("fixture assumption"), it.assumptions)
            }
        }
        val whole = whole(result)
        assertEquals(SafetyStatus.PROVEN, whole.status)
        assertEquals(listOf("fixture assumption"), whole.assumptions)
        assertTrue(whole.evidence.single().contains(identity))
        assertTrue(whole.evidence.single().contains("return:1"))
    }

    @Test
    fun `every property status in either revision participates independently in acceptance`() {
        for (revision in listOf("BASE", "HEAD")) {
            for (property in listOf(SafetyClaim.EFFECT_FREE, SafetyClaim.NONTHROWING, SafetyClaim.TERMINATING)) {
                ProofStatus.entries.forEach { status ->
                    assertPropertyStatus(revision, property, status)
                }
            }
        }
    }

    @Test
    fun `different pure total return values are not whole change equivalence or a demonstrated hazard`() {
        val result = assess(safe, safe.copy(semanticKey = "return:2"))
        rejected(result)
        assertTrue(result.safety.filter { it.claim != SafetyClaim.WHOLE_CHANGE_EQUIVALENT }
            .all { it.status == SafetyStatus.PROVEN })
        assertFalse(result.safety.any { it.status == SafetyStatus.REFUTED })
    }

    @Test
    fun `null semantic keys on either or both revisions cannot establish equivalence`() {
        listOf(safe.copy(semanticKey = null) to safe, safe to safe.copy(semanticKey = null),
            safe.copy(semanticKey = null) to safe.copy(semanticKey = null)).forEach { (base, head) ->
            rejected(assess(base, head))
        }
    }

    @Test
    fun `missing added and removed method summaries leave whole change proof incomplete`() {
        for ((base, head) in listOf(emptyMap<String, MethodSafetySummary>() to mapOf(identity to safe),
            mapOf(identity to safe) to emptyMap(), emptyMap<String, MethodSafetySummary>() to emptyMap())) {
            val result = WholeChangeSafety(base, head).assess(listOf(identity), emptyList(), setOf(identity))
            rejected(result)
            assertTrue(whole(result).evidence.any { identity in it })
        }
    }

    @Test
    fun `declaration changes and unresolved gaps block otherwise complete summaries`() {
        val proof = WholeChangeSafety(mapOf(identity to safe), mapOf(identity to safe))
        rejected(proof.assess(listOf(identity), emptyList(), emptySet()))
        val gap = "Unknown external observation"
        val result = proof.assess(listOf(identity), listOf(gap), setOf(identity))
        rejected(result)
        assertTrue(whole(result).evidence.any { gap in it })
    }

    @Test
    fun `every changed method class field and initialization obligation must be covered`() {
        val unsafe = summary("Example.other()I").copy(semanticKey = "return:2")
        val base = mapOf(identity to safe, unsafe.identity to unsafe.copy(semanticKey = "return:1"))
        val head = mapOf(identity to safe, unsafe.identity to unsafe)
        val proof = WholeChangeSafety(base, head)
        listOf(unsafe.identity, "Example", "Example.field:I", "Class initialization dependencies of Example changed").forEach {
            rejected(proof.assess(listOf(identity, it), emptyList(), setOf(identity, it)))
        }
    }

    @Test
    fun `whole change assumptions include both revisions without discarding property citations`() {
        val base = safe.copy(effectFree = safe.effectFree.copy(assumptions = listOf("BASE assumption", "shared")))
        val head = safe.copy(terminating = safe.terminating.copy(assumptions = listOf("HEAD assumption", "shared")))
        val result = assess(base, head)
        assertEquals(setOf("BASE assumption", "HEAD assumption", "shared", "fixture assumption"), whole(result).assumptions.toSet())
        assertEquals(listOf("BASE assumption", "shared"), result.safety.single {
            it.subject == "BASE $identity" && it.claim == SafetyClaim.EFFECT_FREE
        }.assumptions)
        assertEquals(listOf("HEAD assumption", "shared"), result.safety.single {
            it.subject == "HEAD $identity" && it.claim == SafetyClaim.TERMINATING
        }.assumptions)
    }

    @Test
    fun `empty differences are not a surviving uncontrolled change`() {
        val proof = WholeChangeSafety(mapOf(identity to safe), mapOf(identity to safe))
        assertFailsWith<IllegalArgumentException> { proof.assess(emptyList(), emptyList(), setOf(identity)) }
        var evaluations = 0
        val comparison = SnapshotComparison(snapshot(identity), snapshot(identity), safety = { evaluations++; proof })
        assertNull(comparison.safetyAssessment(emptyList(), emptyList()))
        assertEquals(0, evaluations)
        assertNotNull(comparison.safetyAssessment(listOf(identity), emptyList()))
        assertNotNull(comparison.safetyAssessment(listOf(identity), emptyList()))
        assertEquals(1, evaluations)
        assertNull(SnapshotComparison(snapshot(identity), snapshot(identity)).safetyAssessment(listOf(identity), emptyList()))
    }

    @Test
    fun `snapshot gate requires actual identical declarations in both unaligned inputs`() {
        val base = snapshot(identity)
        val proof = WholeChangeSafety(mapOf(identity to safe), mapOf(identity to safe))
        val variants = listOf(base.copy(declarations = emptyMap()), base.copy(declarations = mapOf(identity to "changed")),
            base.copy(methods = emptyMap()))
        variants.forEach { changed ->
            rejected(assertNotNull(SnapshotComparison(base, changed, safety = { proof })
                .safetyAssessment(listOf(identity), emptyList())))
            rejected(assertNotNull(SnapshotComparison(changed, base, safety = { proof })
                .safetyAssessment(listOf(identity), emptyList())))
        }
        assertEquals(AcceptanceDecision.ACCEPTED, assertNotNull(SnapshotComparison(base, base, safety = { proof })
            .safetyAssessment(listOf(identity), emptyList())).decision)
    }

    @Test
    fun `private member alignment does not manufacture matching safety declarations`() {
        val renamed = "Example.renamed()I"
        val base = snapshot(identity, MethodModifier.PRIVATE)
        val head = snapshot(renamed, MethodModifier.PRIVATE)
        // Deliberately provide summaries under either alias: input inventory still has to match literally.
        val summaries = mapOf(identity to safe, renamed to safe.copy(identity = renamed))
        val proof = WholeChangeSafety(summaries, summaries)
        val comparison = SnapshotComparison(base, head, safety = { proof })
        rejected(assertNotNull(comparison.safetyAssessment(listOf(identity), emptyList())))
        rejected(assertNotNull(comparison.safetyAssessment(listOf(renamed), emptyList())))
    }

    @Test
    fun `equivalent folded output needs an independent ungapped pre fold difference to establish controls`() {
        val same = snapshot(identity)
        val changed = same.copy(methods = mapOf(identity to same.methods.getValue(identity)
            .copy(canonical = "return 2", referenceCanonical = "return 2")))
        val incomplete = changed.copy(methodDiagnostics = mapOf(identity to listOf(MethodAnalysisGap("missing proof"))))
        val cases = listOf(
            SnapshotComparison(same, same) to ControlClassification.UNCONTROLLED,
            SnapshotComparison(same, changed) to ControlClassification.CONTROLLED,
            SnapshotComparison(same, incomplete) to ControlClassification.UNPROVEN,
        )
        val obligation = ProofObligation(identity, SymbolKind.METHOD, "Example", emptyList())
        for ((baseline, expected) in cases) {
            val comparison = SnapshotComparison(same, same, selectedControls = true, unfolded = { baseline })
            for (selected in listOf(null, obligation)) {
                val result = comparison.equivalenceAssessment(selected)
                assertEquals(expected, result.control)
                assertEquals(AcceptanceDecision.ACCEPTED, result.decision)
                assertEquals(if (expected == ControlClassification.CONTROLLED) AcceptanceBasis.CONTROL_COVERAGE
                    else AcceptanceBasis.REPRESENTATION_EQUIVALENCE, result.basis)
            }
        }
        assertEquals(ControlClassification.UNPROVEN, SnapshotComparison(same, same, selectedControls = true)
            .equivalenceAssessment(obligation).control)
    }

    @Test
    fun `zero selected controls never establish control coverage even with a contradictory removal certificate`() {
        val removed = snapshot(identity, MethodModifier.PRIVATE).copy(controlRemovedMethods = setOf(identity))
        val obligation = ProofObligation(identity, SymbolKind.METHOD, "Example", emptyList())
        val controlled = SnapshotComparison(removed, removed, selectedControls = true)
        assertTrue(controlled.isUnreachable(obligation))
        assertEquals(ControlClassification.CONTROLLED, controlled.equivalenceAssessment(obligation).control)
        val uncontrolled = SnapshotComparison(removed, removed, selectedControls = false)
        assertEquals(ControlClassification.UNCONTROLLED, uncontrolled.equivalenceAssessment(obligation).control)
        assertEquals(ControlClassification.UNCONTROLLED, uncontrolled.equivalenceAssessment().control)
    }

    @Test
    fun `baseline comparison is lazy and only evaluated when needed for classification`() {
        val same = snapshot(identity)
        var evaluations = 0
        val baseline = { evaluations++; SnapshotComparison(same, same) }
        SnapshotComparison(same, same, selectedControls = false, unfolded = baseline).equivalenceAssessment()
        assertEquals(0, evaluations)
        val removed = snapshot(identity, MethodModifier.PRIVATE).copy(controlRemovedMethods = setOf(identity))
        val obligation = ProofObligation(identity, SymbolKind.METHOD, "Example", emptyList())
        SnapshotComparison(removed, removed, selectedControls = true, unfolded = baseline).equivalenceAssessment(obligation)
        assertEquals(0, evaluations)
        val comparison = SnapshotComparison(same, same, selectedControls = true, unfolded = baseline)
        comparison.equivalenceAssessment()
        comparison.equivalenceAssessment(obligation)
        assertEquals(1, evaluations)
    }

    private fun assertPropertyStatus(revision: String, claim: SafetyClaim, status: ProofStatus) {
        val property = PropertyProof(status, listOf("$revision $claim evidence"), listOf("$revision assumption"))
        val changed = when (claim) {
            SafetyClaim.EFFECT_FREE -> safe.copy(effectFree = property)
            SafetyClaim.NONTHROWING -> safe.copy(nonThrowing = property)
            SafetyClaim.TERMINATING -> safe.copy(terminating = property)
            else -> error("Method property required")
        }
        val result = if (revision == "BASE") assess(changed, safe) else assess(safe, changed)
        assertEquals(SafetyStatus.valueOf(status.name), result.safety.single {
            it.subject == "$revision $identity" && it.claim == claim
        }.status)
        if (status == ProofStatus.PROVEN) assertEquals(AcceptanceDecision.ACCEPTED, result.decision) else rejected(result)
    }

    private fun assess(base: MethodSafetySummary, head: MethodSafetySummary): ChangeAssessment =
        WholeChangeSafety(mapOf(identity to base), mapOf(identity to head)).assess(listOf(identity), emptyList(), setOf(identity))

    private fun whole(result: ChangeAssessment): SafetyEvidence = result.safety.single { it.claim == SafetyClaim.WHOLE_CHANGE_EQUIVALENT }

    private fun rejected(result: ChangeAssessment) {
        assertEquals(ControlClassification.UNCONTROLLED, result.control)
        assertEquals(AcceptanceDecision.REJECTED, result.decision)
        assertEquals(AcceptanceBasis.INCOMPLETE_PROOF, result.basis)
        assertEquals(SafetyStatus.UNPROVEN, whole(result).status)
    }

    private fun summary(name: String): MethodSafetySummary {
        val property = PropertyProof(ProofStatus.PROVEN, listOf("fixture evidence"), listOf("fixture assumption"))
        return MethodSafetySummary(name, property, property, property, "return:1", emptySet())
    }

    private fun snapshot(name: String, modifier: MethodModifier = MethodModifier.PUBLIC): ProgramSnapshot = ProgramSnapshot(
        mapOf(name to MethodSnapshot(name, "return 1", emptySet(), setOf(modifier), emptySet())),
        mapOf("Example" to "class declaration", name to "unchanged declaration"), emptyList(), emptyList(),
    )
}

/** Original-bytecode summaries cannot override selected folding in the method or its transitive proof dependencies. */
class WholeChangeFoldSafetyTest {
    private val identity = "Example.value()I"
    private val dependency = "Example.helper()I"
    private val transitive = "Example.enabled()Z"

    @Test
    fun `folding the method itself invalidates its original bytecode relational proof`() {
        val summaries = mapOf(identity to summary(emptySet()))
        val result = WholeChangeSafety(summaries, summaries).assess(
            listOf(identity), emptyList(), setOf(identity), setOf(identity),
        )
        assertEquals(AcceptanceDecision.REJECTED, result.decision)
        assertEquals(SafetyStatus.UNPROVEN, result.safety.last().status)
        assertEquals(ControlClassification.UNCONTROLLED, result.control)
    }

    @Test
    fun `a folded transitive dependency in either revision invalidates the original summary`() {
        val independent = summary(emptySet())
        val dependent = summary(setOf(dependency, transitive))
        for ((base, head) in listOf(independent to dependent, dependent to independent)) {
            val proof = WholeChangeSafety(mapOf(identity to base), mapOf(identity to head))
            for (changed in listOf(dependency, transitive)) {
                val rejected = proof.assess(listOf(identity), emptyList(), setOf(identity), setOf(changed))
                assertEquals(AcceptanceDecision.REJECTED, rejected.decision)
                assertEquals(SafetyStatus.UNPROVEN, rejected.safety.last().status)
            }
            val unrelated = proof.assess(listOf(identity), emptyList(), setOf(identity), setOf("Other.enabled()Z"))
            assertEquals(AcceptanceDecision.ACCEPTED, unrelated.decision)
            assertEquals(AcceptanceBasis.STATIC_SAFETY, unrelated.basis)
            assertEquals(ControlClassification.UNCONTROLLED, unrelated.control)
        }
    }

    @Test
    fun `snapshot comparison guards both revision bodies and every retained summary dependency`() {
        val summary = summary(setOf(dependency, transitive))
        val proof = WholeChangeSafety(mapOf(identity to summary), mapOf(identity to summary))
        val methods = listOf(identity, dependency, transitive).associateWith {
            MethodSnapshot(it, "original $it", emptySet(), setOf(MethodModifier.PUBLIC), emptySet())
        }
        val original = ProgramSnapshot(methods, methods.mapValues { "declaration" }, emptyList(), emptyList())
        val baseline = { SnapshotComparison(original, original) }
        for (changed in listOf(identity, dependency, transitive)) {
            val folded = original.copy(methods = methods + (changed to methods.getValue(changed).copy(canonical = "folded")))
            for ((base, head) in listOf(folded to original, original to folded)) {
                val comparison = SnapshotComparison(base, head, safety = { proof }, selectedControls = true, unfolded = baseline)
                val result = assertNotNull(comparison.safetyAssessment(listOf(identity), emptyList()))
                assertEquals(AcceptanceDecision.REJECTED, result.decision)
            }
        }
        val unknown = SnapshotComparison(original, original, safety = { proof }, selectedControls = true)
        assertEquals(AcceptanceDecision.REJECTED, assertNotNull(unknown.safetyAssessment(listOf(identity), emptyList())).decision)
        val unchanged = SnapshotComparison(original, original, safety = { proof }, selectedControls = true, unfolded = baseline)
        assertEquals(AcceptanceDecision.ACCEPTED, assertNotNull(unchanged.safetyAssessment(listOf(identity), emptyList())).decision)
    }

    private fun summary(dependencies: Set<String>): MethodSafetySummary {
        val property = PropertyProof(ProofStatus.PROVEN, listOf("fixture evidence"))
        return MethodSafetySummary(identity, property, property, property, "return:42", dependencies)
    }
}
