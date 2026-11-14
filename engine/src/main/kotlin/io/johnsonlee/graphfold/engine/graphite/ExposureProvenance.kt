package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.sootup.ExposureBranchKey

import io.johnsonlee.graphfold.engine.bytecode.sootup.ExposureBranches
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.sootup.FoldedMethodObservation

/** Translate application-body observations and unambiguous Graphite selections into IR evidence. */
internal class ExposureProvenance {
    private val branches = ExposureBranches()

    fun capture(observation: FoldedMethodObservation) {
        val snapshot = observation.snapshot ?: return
        val body = snapshot.body
        if (observation.evidence.application == true) branches.capture(observation.evidence.method.identity, body)
    }

    fun select(ruleIndex: Int, sites: List<CallSiteNode>) {
        sites.groupBy { it.caller.identity }.forEach { (caller, matches) ->
            val site = matches.singleOrNull()?.takeIf { it.origin == null && (it.ordinal ?: -1) >= 0 } ?: return@forEach
            branches.select(ruleIndex, caller, site.callee.identity)
        }
    }

    fun authorized(identity: String, foldedRules: Set<Int>): Set<ExposureBranchKey> = branches.authorized(identity, foldedRules)
}
