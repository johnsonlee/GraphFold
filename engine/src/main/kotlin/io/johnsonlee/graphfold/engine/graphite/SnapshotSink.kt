package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.sootup.MethodSnapshots

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.sootup.FoldedMethodObservation
import io.johnsonlee.graphite.sootup.MethodObservationEvidence

/**
 * Invocation-local folded-body consumer. Receipts are retained for final completeness reconciliation;
 * owned bodies are reduced to immutable comparison/effect facts during the callback, never retained.
 * The caller must discard this sink if Graphite's observed load fails.
 */
internal class SnapshotSink(
    private val applicationOwners: Set<String> = emptySet(),
    private val dependencyResources: Boolean = false,
    private val trustedIntrinsics: Boolean = false,
    private val primitiveMethods: Set<String> = emptySet(),
    private val exposure: ExposureProvenance? = null,
) {
    val observations = linkedMapOf<String, MethodObservationEvidence>()
    val appliedRules = linkedMapOf<String, Map<Int, Int>>()
    private val bodies = MethodSnapshots(applicationOwners, dependencyResources, trustedIntrinsics, primitiveMethods)
    val methods get() = bodies.methods
    val discardedCalls get() = bodies.discardedCalls
    val resourceCalls get() = bodies.resourceCalls
    val resourceInitializers get() = bodies.resourceInitializers

    /** Only direct calls inspected by the existing resource model can discharge a resource dispatch gap. */
    fun modelsResourceCall(site: CallSiteNode): Boolean {
        val method = methods[site.caller.identity]
        val unmodeled = resourceCalls[site.caller.identity] ?: return false
        return (site.ordinal ?: -1) >= 0 && site.callee.identity in method?.calls.orEmpty() && site.callee.identity !in unmodeled
    }

    fun capture(observation: FoldedMethodObservation) {
        val evidence = observation.evidence
        val identity = evidence.method.identity
        check(identity !in observations) { "Duplicate method observation $identity" }
        observations[identity] = evidence
        appliedRules[identity] = evidence.folding?.foldedCalls.orEmpty()
        val modifiers = evidence.modifiers ?: return
        val body = observation.snapshot?.body
        val available = when (evidence.status) {
            AnalysisStatus.ANALYZED, AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW -> body != null
            AnalysisStatus.ABSTRACT, AnalysisStatus.NATIVE, AnalysisStatus.NO_BODY -> body == null
            else -> false
        }
        if (available) bodies.capture(identity, modifiers.toSet(), body,
            exposure?.authorized(identity, evidence.folding?.foldedCalls.orEmpty().filterValues { it > 0 }.keys).orEmpty())
    }

}
