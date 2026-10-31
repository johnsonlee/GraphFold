package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.SelectedCallSite
import io.johnsonlee.graphfold.engine.SelectedMethod
import io.johnsonlee.graphite.core.CallSiteNode

internal data class RuleAuditUpdate(
    val selectionStatus: RuleSelectionStatus,
    val selectedNodes: List<SelectedCallSite> = emptyList(),
    val matchedCallCount: Int? = null,
    val message: String? = null,
)

/** Publish progress immediately, so later query/frontend failures cannot erase earlier selection evidence. */
internal class RuleAuditCapture(private val publish: (Int, RuleAuditUpdate) -> Unit) {
    private val records = mutableMapOf<Int, RuleAuditUpdate>()

    fun selected(index: Int, sites: List<CallSiteNode>) {
        update(index, RuleAuditUpdate(RuleSelectionStatus.SELECTED, sites.distinctBy { it.id }.sortedBy { it.id.value }.map {
            SelectedCallSite(it.id.value, SelectedMethod(it.caller.signature, it.caller.descriptor),
                SelectedMethod(it.callee.signature, it.callee.descriptor), it.ordinal, it.origin?.value, it.lineNumber)
        }))
    }

    fun failed(index: Int, failure: Throwable) {
        update(index, (records[index] ?: RuleAuditUpdate(RuleSelectionStatus.FAILED)).copy(message = failure.message))
    }

    fun applied(index: Int, count: Int) {
        update(index, records.getValue(index).copy(matchedCallCount = count))
    }

    private fun update(index: Int, record: RuleAuditUpdate) {
        records[index] = record
        publish(index, record)
    }
}
