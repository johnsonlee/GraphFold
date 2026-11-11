package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.diagnosticLabel
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.sootup.MethodObservationEvidence

/**
 * Converts evidence from one folded graph into global inventory failures and method-local gaps.
 * Evidence is relative to that graph's declared scope: excluded or unexpanded library classes are
 * not global failures, but an actual call to an unavailable body still needs a downstream model.
 * Abstract declarations alone do not imply incomplete dispatch. Native bodies and incomplete
 * branch scopes cannot justify structural equivalence without a separate model.
 * When [receipts] are supplied, reconcile every delivered method with the final graph and
 * require a receipt for each analyzed or explicitly failed declaration. [applicationOwners]
 * is the independent application inventory; artifact role alone does not establish entry scope.
 * A [metadataOnlyMultiRelease] exemption requires an independent inspection of the actual archive;
 * a library name, version label or graph flag alone must never establish it.
 */
internal class GraphiteCompleteness(
    graph: Graph,
    receipts: Map<String, MethodObservationEvidence>? = null,
    applicationOwners: Set<String>? = null,
    metadataOnlyMultiRelease: (String) -> Boolean = { false },
    modeledResourceCall: (CallSiteNode) -> Boolean = { false },
    provenDispatch: (CallSiteNode) -> Boolean = { false },
) {
    val diagnostics: List<String>
    val methodDiagnostics: Map<String, List<MethodAnalysisGap>>
    val incompleteMethods: Set<String> get() = methodDiagnostics.keys
    private val incompleteBodies = linkedSetOf<String>()
    private val incompleteDispatch = mutableListOf<CallSiteNode>()

    /** Resource contracts can cover a particular call, but never an unavailable body or recovery. */
    fun incompleteResourceMethods(modeledCall: (CallSiteNode) -> Boolean): Set<String> =
        incompleteBodies + incompleteDispatch.filterNot(modeledCall).map { it.caller.identity }

    init {
        val failures = mutableListOf<String>()
        val gaps = linkedMapOf<String, MutableList<MethodAnalysisGap>>()
        fun gap(identity: String, message: String) {
            incompleteBodies.add(identity)
            gaps.getOrPut(identity) { mutableListOf() }.add(MethodAnalysisGap(message))
        }
        val evidence = graph.analysisCompleteness()
        if (evidence == null) {
            failures.add("Graphite did not provide analysis completeness evidence")
        } else {
            evidence.duplicateClasses.toSortedMap().forEach { (owner, origins) ->
                failures.add("Graphite duplicate class definition: $owner in ${origins.joinToString()}")
            }
            evidence.artifacts.toSortedMap().filterValues { it.multiRelease }.forEach { (origin, _) ->
                if (!metadataOnlyMultiRelease(origin)) {
                    failures.add("Graphite did not select runtime variants for multi-release artifact: $origin")
                }
            }
            evidence.classes.toSortedMap().filterValues { it.status == AnalysisStatus.RESOLUTION_FAILED }
                .forEach { (owner, declaration) ->
                    failures.add("Graphite ${declaration.status}: class $owner from ${diagnosticLabel(declaration.origin)}")
                }
            evidence.methods.toSortedMap().filterValues { it.status in UNPROVED_METHOD_STATUSES }
                .forEach { (identity, method) ->
                    val detail = method.message?.let { ": $it" }.orEmpty()
                    gap(identity, "Graphite ${method.status}: $identity$detail")
                }
            evidence.callSiteGaps.toSortedMap().forEach { (id, reason) ->
                val site = graph.node(NodeId(id)) as? CallSiteNode
                if (site == null) {
                    failures.add("Graphite $reason references an unavailable call site: $id")
                } else if (!provenDispatch(site)) {
                    incompleteDispatch.add(site)
                    gaps.getOrPut(site.caller.identity) { mutableListOf() }.add(MethodAnalysisGap(
                        "Graphite $reason: ${site.caller.identity} calls ${site.callee.identity} at $id",
                        callee = site.callee.identity, globalObservation = true,
                        resourceEffectModeled = modeledResourceCall(site), dispatchOnly = true))
                }
            }
            receipts?.let { reconcile(graph, evidence, it, applicationOwners, failures, ::gap) }
        }
        diagnostics = failures
        methodDiagnostics = gaps.mapValues { (_, values) -> values.distinct() }
    }

    private fun reconcile(
        graph: Graph, evidence: AnalysisCompleteness, receipts: Map<String, MethodObservationEvidence>,
        applicationOwners: Set<String>?, failures: MutableList<String>, gap: (String, String) -> Unit,
    ) {
        val methods = graph.methods(MethodPattern()).associateBy { it.identity }
        (methods.keys + evidence.methods.keys - receipts.keys).sorted().forEach { identity ->
            failures.add("Graphite has no observation receipt for declared method: $identity")
        }
        receipts.toSortedMap().forEach { (identity, receipt) ->
            if (identity != receipt.method.identity) {
                failures.add("Graphite observation identity mismatch: $identity versus ${receipt.method.identity}")
            }
            val declaration = methods[identity]
            val status = if (declaration == null) evidence.methods[identity]?.status else evidence.statusOf(declaration, true)
            if (status == null) {
                failures.add("Graphite observation has no matching declaration: $identity")
            } else if (receipt.status != status) {
                failures.add("Graphite observation status mismatch: $identity has ${receipt.status}, final graph has $status")
            }
            reconcileDeclaration(identity, receipt, evidence, applicationOwners, failures)
            if (receipt.status in UNPROVED_METHOD_STATUSES) {
                gap(identity, "Graphite observed ${receipt.status}: $identity")
            }
            receipt.recovery?.let { recovery ->
                gap(identity, "Graphite recovered interception for $identity using ${recovery.chain}: ${recovery.reason}")
            }
        }
    }

    private fun reconcileDeclaration(
        identity: String, receipt: MethodObservationEvidence, evidence: AnalysisCompleteness,
        applicationOwners: Set<String>?, failures: MutableList<String>,
    ) {
        val declaration = evidence.classes[receipt.method.owner]
        if (declaration?.origin != receipt.origin) {
            failures.add("Graphite observation origin mismatch: $identity from ${diagnosticLabel(receipt.origin)}, " +
                "final graph has ${diagnosticLabel(declaration?.origin)}")
        }
        if (evidence.artifacts[receipt.origin]?.role != receipt.role) {
            failures.add("Graphite observation artifact role mismatch: $identity from ${diagnosticLabel(receipt.origin)}")
        }
        val incompleteDeclaration = declaration == null || receipt.modifiers == null || receipt.application == null
        if (receipt.status != AnalysisStatus.RESOLUTION_FAILED && incompleteDeclaration) {
            failures.add("Graphite observation has incomplete declaration metadata: $identity")
        }
        if (applicationOwners != null && receipt.application != null &&
            receipt.application != (receipt.method.owner in applicationOwners)) {
            failures.add("Graphite observation application ownership mismatch: $identity")
        }
    }

    private companion object {
        val UNPROVED_METHOD_STATUSES = setOf(
            AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW,
            AnalysisStatus.NATIVE,
            AnalysisStatus.NO_BODY,
            AnalysisStatus.INCOMPLETE,
            AnalysisStatus.RESOLUTION_FAILED,
        )
    }
}
