package io.johnsonlee.graphfold.engine

/** Static control coverage, independently of safety and acceptance. */
enum class ControlClassification { CONTROLLED, UNCONTROLLED, UNPROVEN }

/** Property established or left unresolved by a static proof. */
enum class SafetyClaim { EFFECT_FREE, NONTHROWING, TERMINATING, WHOLE_CHANGE_EQUIVALENT }

/** An absent proof is not a demonstrated hazard. */
enum class SafetyStatus { PROVEN, REFUTED, UNPROVEN }

/** Per-obligation acceptance; accepting an uncontrolled change does not relabel it controlled. */
enum class AcceptanceDecision { ACCEPTED, REJECTED }

/** The specific proof or remaining difference responsible for the acceptance decision. */
enum class AcceptanceBasis {
    CONTROL_COVERAGE, STATIC_SAFETY, REPRESENTATION_EQUIVALENCE, INPUT_IDENTITY,
    INCOMPLETE_PROOF, SURVIVING_DIFFERENCE,
}

/**
 * Cited static evidence for one claim about [subject], with its explicit [assumptions].
 * Method properties alone do not establish [SafetyClaim.WHOLE_CHANGE_EQUIVALENT].
 */
data class SafetyEvidence(
    val claim: SafetyClaim,
    val status: SafetyStatus,
    val subject: String,
    val evidence: List<String>,
    val assumptions: List<String>,
)

/** Independent control, safety, and acceptance axes for one change obligation. */
data class ChangeAssessment(
    val control: ControlClassification,
    val safety: List<SafetyEvidence>,
    val decision: AcceptanceDecision,
    val basis: AcceptanceBasis,
) {
    init {
        if (basis == AcceptanceBasis.STATIC_SAFETY && decision == AcceptanceDecision.ACCEPTED) {
            require(control == ControlClassification.UNCONTROLLED) { "static safety does not establish control coverage" }
            require(safety.any { it.claim == SafetyClaim.WHOLE_CHANGE_EQUIVALENT &&
                it.status == SafetyStatus.PROVEN && it.subject.isNotBlank() &&
                it.evidence.isNotEmpty() && it.evidence.all(String::isNotBlank)
            }) { "static acceptance requires cited whole-change equivalence evidence" }
        }
    }
}

/** Conservative compatibility mapping; the engine supplies additional evidence when available. */
internal fun ReasonCode.defaultAssessment(): ChangeAssessment = when (this) {
    ReasonCode.UNREACHABLE -> ChangeAssessment(ControlClassification.CONTROLLED, emptyList(),
        AcceptanceDecision.ACCEPTED, AcceptanceBasis.CONTROL_COVERAGE)
    ReasonCode.FOLDED_EQUIVALENT -> ChangeAssessment(ControlClassification.UNPROVEN, emptyList(),
        AcceptanceDecision.ACCEPTED, AcceptanceBasis.REPRESENTATION_EQUIVALENCE)
    ReasonCode.BUILD_OUTPUT_UNCHANGED -> ChangeAssessment(ControlClassification.UNCONTROLLED, emptyList(),
        AcceptanceDecision.ACCEPTED, AcceptanceBasis.INPUT_IDENTITY)
    ReasonCode.SURVIVING_DIFFERENCE -> ChangeAssessment(ControlClassification.UNCONTROLLED, emptyList(),
        AcceptanceDecision.REJECTED, AcceptanceBasis.SURVIVING_DIFFERENCE)
    ReasonCode.ANALYSIS_INCOMPLETE, ReasonCode.STATIC_SAFETY_PROVEN ->
        ChangeAssessment(ControlClassification.UNPROVEN, emptyList(),
            AcceptanceDecision.REJECTED, AcceptanceBasis.INCOMPLETE_PROOF)
}
