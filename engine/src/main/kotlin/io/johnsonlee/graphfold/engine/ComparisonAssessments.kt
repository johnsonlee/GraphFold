package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation

/** Combines independent proof domains without changing the structural differences they explain. */
internal class ComparisonAssessments(
    base: ProgramSnapshot, head: ProgramSnapshot, alignedNames: Map<String, String>,
    safety: (() -> WholeChangeSafety)?, private val selectedControls: Boolean?,
    unfolded: (() -> SnapshotComparison)?, private val removedByControl: (ProofObligation) -> Boolean,
    private val foldedBodies: (SnapshotComparison) -> Set<String>,
) {
    private val safetyProof by lazy { safety?.invoke() }
    private val controlBaseline by lazy { unfolded?.invoke() }
    private val unchangedDeclarations = base.methods.keys.intersect(head.methods.keys).filterTo(hashSetOf()) {
        base.declarations[it] != null && base.declarations[it] == head.declarations[it] &&
            it !in alignedNames && it !in alignedNames.values
    }

    fun safety(differences: List<String>, gaps: List<String>): ChangeAssessment? {
        return if (differences.isEmpty()) null else safetyProof?.let { proof ->
            proof.assess(differences, gaps, unchangedDeclarations, changedByFolding(differences))
        }
    }

    private fun changedByFolding(differences: List<String>): Set<String> {
        if (selectedControls != true) return emptySet()
        val original = controlBaseline
        return if (original == null) differences.toSet() else foldedBodies(original)
    }

    fun equivalence(obligation: ProofObligation?): ChangeAssessment {
        val classification = when {
            selectedControls == false -> ControlClassification.UNCONTROLLED
            obligation?.let(removedByControl) == true -> ControlClassification.CONTROLLED
            else -> baselineClassification(obligation)
        }
        val basis = if (classification == ControlClassification.CONTROLLED) AcceptanceBasis.CONTROL_COVERAGE
            else AcceptanceBasis.REPRESENTATION_EQUIVALENCE
        return ChangeAssessment(classification, emptyList(), AcceptanceDecision.ACCEPTED, basis)
    }

    private fun baselineClassification(obligation: ProofObligation?): ControlClassification {
        val original = controlBaseline
        return when {
            original == null || original.analysisGaps(obligation).isNotEmpty() -> ControlClassification.UNPROVEN
            (if (obligation == null) original.wholeProgramDifferences() else original.differences(obligation)).isEmpty() ->
                ControlClassification.UNCONTROLLED
            else -> ControlClassification.CONTROLLED
        }
    }
}

internal fun SnapshotComparison.safetyAssessment(differences: List<String>, gaps: List<String>): ChangeAssessment? =
    assessments.safety(differences, gaps)

/** A before-fold difference must independently witness control coverage; equivalence alone cannot do so. */
internal fun SnapshotComparison.equivalenceAssessment(obligation: ProofObligation? = null): ChangeAssessment =
    assessments.equivalence(obligation)

/** Original summaries cannot override selected folds in this method or a transitive callee. */
internal fun SnapshotComparison.foldedBodies(original: SnapshotComparison): Set<String> =
    revisionMethods.zip(original.revisionMethods).flatMap { (folded, unfolded) ->
        (folded.keys + unfolded.keys).filter { folded[it]?.canonical != unfolded[it]?.canonical }
    }.toSet()
