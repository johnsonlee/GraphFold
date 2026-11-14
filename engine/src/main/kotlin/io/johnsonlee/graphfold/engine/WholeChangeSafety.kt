package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import io.johnsonlee.graphfold.engine.safety.PropertyProof

/** A relational proof over every remaining difference, independent of selected controls. */
internal class WholeChangeSafety(
    private val base: Map<String, MethodSafetySummary>,
    private val head: Map<String, MethodSafetySummary>,
) {
    fun assess(
        differences: List<String>, gaps: List<String>, unchangedDeclarations: Set<String>, changedByFolding: Set<String> = emptySet(),
    ): ChangeAssessment {
        require(differences.isNotEmpty()) { "Safety comparison requires a surviving change obligation" }
        val properties = differences.flatMap { identity ->
            listOfNotNull(base[identity]?.let { "BASE" to it }, head[identity]?.let { "HEAD" to it })
                .flatMap { (revision, summary) -> evidence("$revision $identity", summary) }
        }
        val equivalents = differences.mapNotNull { equivalent(it, unchangedDeclarations, changedByFolding) }.toMap()
        val missing = differences.filterNot { it in equivalents }
        val complete = missing.isEmpty() && gaps.isEmpty()
        val justification = if (complete) equivalents.map { (identity, expression) ->
            "BASE and HEAD $identity have identical declarations and total, effect-free return expressions: $expression"
        } else missing.map { "No complete relational proof for $it" } + gaps.map { "Unresolved analysis obligation: $it" }
        val whole = SafetyEvidence(SafetyClaim.WHOLE_CHANGE_EQUIVALENT,
            if (complete) SafetyStatus.PROVEN else SafetyStatus.UNPROVEN,
            differences.joinToString(), justification, properties.flatMap { it.assumptions }.distinct())
        return ChangeAssessment(ControlClassification.UNCONTROLLED, properties + whole,
            if (complete) AcceptanceDecision.ACCEPTED else AcceptanceDecision.REJECTED,
            if (complete) AcceptanceBasis.STATIC_SAFETY else AcceptanceBasis.INCOMPLETE_PROOF)
    }

    private fun equivalent(identity: String, unchangedDeclarations: Set<String>, changedByFolding: Set<String>): Pair<String, String>? {
        val before = base[identity]
        val after = head[identity]
        if (before == null || after == null || identity !in unchangedDeclarations) return null
        val expression = before.semanticKey
        val untouched = (before.dependencies + after.dependencies + identity).none { it in changedByFolding }
        val total = untouched && listOf(before, after).all { summary ->
            listOf(summary.effectFree, summary.nonThrowing, summary.terminating).all { it.status == ProofStatus.PROVEN }
        }
        return if (expression != null && expression == after.semanticKey && total) identity to expression else null
    }

    private fun evidence(subject: String, summary: MethodSafetySummary): List<SafetyEvidence> = listOf(
        property(subject, SafetyClaim.EFFECT_FREE, summary.effectFree),
        property(subject, SafetyClaim.NONTHROWING, summary.nonThrowing),
        property(subject, SafetyClaim.TERMINATING, summary.terminating),
    )

    private fun property(subject: String, claim: SafetyClaim, proof: PropertyProof) =
        SafetyEvidence(claim, SafetyStatus.valueOf(proof.status.name), subject, proof.evidence, proof.assumptions)
}
