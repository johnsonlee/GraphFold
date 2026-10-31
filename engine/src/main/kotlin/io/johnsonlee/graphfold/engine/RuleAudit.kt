package io.johnsonlee.graphfold.engine

/** Selection execution is distinct from applying folds to bytecode. */
enum class RuleSelectionStatus { NOT_EXECUTED, SELECTED, FAILED }
enum class RuleSelectorKind { CYPHER, CALL_SITE_MATCH }

data class RuleSelector(val kind: RuleSelectorKind, val query: String?, val description: String)
data class SelectedMethod(val signature: String, val descriptor: String)

/** Node ids belong to the original, unfolded graph for this scenario and revision only. */
data class SelectedCallSite(
    val nodeId: Int,
    val caller: SelectedMethod,
    val callee: SelectedMethod,
    val ordinal: Int?,
    val originNodeId: Int?,
    val lineNumber: Int?,
)

/** Selected nodes are unique raw graph nodes; matchedCallCount comes independently from Graphite's fold report. */
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
    val selectedCount: Int? get() = if (selectionStatus == RuleSelectionStatus.SELECTED) selectedNodes.size else null
}
