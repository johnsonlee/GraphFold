package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import io.johnsonlee.graphfold.engine.safety.PropertyProof
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComparisonAssessmentBoundaryTest {
    private val identity = "Example.value()I"

    @Test
    fun `neither side of private member alignment can borrow original identity safety evidence`() {
        val before = snapshot("before")
        val after = snapshot("after")
        val property = PropertyProof(ProofStatus.PROVEN, listOf("test mechanism"))
        val summary = MethodSafetySummary(identity, property, property, property, "return:1", emptySet())
        val safety = { WholeChangeSafety(mapOf(identity to summary), mapOf(identity to summary)) }
        for (alignment in listOf(mapOf(identity to "Example.other()I"), mapOf("Example.other()I" to identity))) {
            val assessment = ComparisonAssessments(before, after, alignment, safety, false, null, { false }, { emptySet() })
                .safety(listOf(identity), emptyList())
            assertEquals(AcceptanceDecision.REJECTED, assessment?.decision)
            assertTrue(requireNotNull(assessment).safety.any {
                it.claim == SafetyClaim.WHOLE_CHANGE_EQUIVALENT && it.status == SafetyStatus.UNPROVEN
            })
        }
        val unchangedIdentity = ComparisonAssessments(before, after, emptyMap(), safety, false, null, { false }, { emptySet() })
        assertEquals(AcceptanceDecision.ACCEPTED, unchangedIdentity.safety(listOf(identity), emptyList())?.decision)
    }

    @Test
    fun `fold dependency inventory includes bodies removed or added on either revision`() {
        val empty = ProgramSnapshot(emptyMap(), emptyMap(), emptyList(), emptyList())
        val body = snapshot("body")
        val absent = SnapshotComparison(empty, empty)
        for (comparison in listOf(SnapshotComparison(body, empty), SnapshotComparison(empty, body), SnapshotComparison(body, body))) {
            assertEquals(setOf(identity), comparison.foldedBodies(absent))
            assertEquals(setOf(identity), absent.foldedBodies(comparison))
            assertTrue(comparison.foldedBodies(comparison).isEmpty())
        }
    }

    private fun snapshot(body: String) = ProgramSnapshot(
        mapOf(identity to MethodSnapshot(identity, body, emptySet(), emptySet(), emptySet())),
        mapOf("Example" to "class", identity to "method"), emptyList(), emptyList(),
    )
}
