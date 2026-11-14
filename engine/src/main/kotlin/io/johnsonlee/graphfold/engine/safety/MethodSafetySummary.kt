package io.johnsonlee.graphfold.engine.safety

/** A failed proof is distinct from a statically demonstrated counterexample. */
internal enum class ProofStatus { PROVEN, REFUTED, UNPROVEN }

internal data class PropertyProof(
    val status: ProofStatus, val evidence: List<String>, val assumptions: List<String> = emptyList(),
)

internal enum class ReturnNullness { NULL, NON_NULL, UNKNOWN, NOT_APPLICABLE }

/** Revision-local, input-derived proof. A semantic key is available only for a complete whole-method proof. */
internal data class MethodSafetySummary(
    val identity: String,
    val effectFree: PropertyProof,
    val nonThrowing: PropertyProof,
    val terminating: PropertyProof,
    val semanticKey: String?,
    val dependencies: Set<String>,
    val returnNullness: ReturnNullness = ReturnNullness.UNKNOWN,
)
