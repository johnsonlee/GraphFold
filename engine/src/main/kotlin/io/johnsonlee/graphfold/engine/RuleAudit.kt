package io.johnsonlee.graphfold.engine

/** Selection execution is distinct from applying folds to bytecode. */
enum class RuleSelectionStatus {
    /** Selection has not run, for example because an earlier stage failed or the diff was empty. */
    NOT_EXECUTED,
    /** Selection completed; an empty result is still a successful selection execution. */
    SELECTED,
    /** Selection failed before a reliable selected-node list could be recorded. */
    FAILED,
}
/** Mechanism used to select the call sites targeted by a folding rule. */
enum class RuleSelectorKind {
    /** A Cypher query evaluated against the graph. */
    CYPHER,
    /** Explicit call-site matching criteria from the fold rule. */
    CALL_SITE_MATCH,
}

/**
 * Auditable description of a rule's selection criteria.
 *
 * @property kind Selection mechanism used by the rule.
 * @property query Cypher query text, or null for explicit call-site matching.
 * @property description Human-readable rendering of the selection criteria.
 */
data class RuleSelector(val kind: RuleSelectorKind, val query: String?, val description: String)
/**
 * JVM method identity attached to a selected graph node.
 *
 * @property signature Graphite method signature identifying the owner, method name, and parameter types.
 * @property descriptor JVM method descriptor including parameter and return types.
 */
data class SelectedMethod(val signature: String, val descriptor: String)

/**
 * Selected call site in the original, unfolded graph for one scenario and revision.
 * Node identities must not be compared across graphs as persistent identifiers.
 *
 * @property nodeId Raw Graphite call-site node identifier.
 * @property caller Method containing the call site.
 * @property callee Method invoked at this call site.
 * @property ordinal Call-site ordinal supplied by Graphite, or null when unavailable.
 * @property originNodeId Original-node reference supplied by Graphite, or null when unavailable.
 * @property lineNumber Source line supplied by Graphite, or null when unavailable; not an exact diff span.
 */
data class SelectedCallSite(
    val nodeId: Int,
    val caller: SelectedMethod,
    val callee: SelectedMethod,
    val ordinal: Int?,
    val originNodeId: Int?,
    val lineNumber: Int?,
)

/**
 * Selection evidence and application counts for one folding rule in one scenario and revision.
 * Selected nodes are unique raw graph nodes; application counts come independently from Graphite's fold report.
 *
 * @property scenario Name of the folding scenario.
 * @property revision Revision whose graph was queried.
 * @property ruleIndex Zero-based position of the rule within its scenario.
 * @property selector Recorded selection criteria.
 * @property selectionStatus Whether selection ran successfully, failed, or did not run.
 * @property selectedNodes Unique nodes selected in the original graph; empty if none were recorded.
 * @property matchedCallCount Calls matched during folding, or null when no application count is available.
 * @property message Additional context for a failed or skipped operation, or null when none is needed.
 */
data class RuleAudit(
    val scenario: String,
    val revision: Revision,
    val ruleIndex: Int,
    val selector: RuleSelector,
    val selectionStatus: RuleSelectionStatus = RuleSelectionStatus.NOT_EXECUTED,
    val selectedNodes: List<SelectedCallSite> = emptyList(),
    val matchedCallCount: Int? = null,
    val message: String? = null,
) {
    /** Unique selected-node count after successful selection, otherwise null; independent of [matchedCallCount]. */
    val selectedCount: Int? get() = if (selectionStatus == RuleSelectionStatus.SELECTED) selectedNodes.size else null
}
