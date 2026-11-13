package io.johnsonlee.graphfold.engine

/** A zero-obligation probe contributes selection evidence, not unrelated program coverage gaps. */
@Suppress("TooGenericExceptionCaught")
internal fun scopeRuleProbe(names: Set<String>, audits: List<RuleAudit>, analyze: () -> Unit): Diagnostic? {
    var failure: Exception? = null
    try {
        analyze()
    } catch (interrupted: InterruptedException) {
        throw interrupted
    } catch (exception: Exception) {
        failure = exception
    }
    return if (audits.any { it.matchedCallCount == null }) {
        Diagnostic(AnalysisStage.FOLD, DiagnosticCode.INCOMPLETE_GRAPH,
            "Could not establish rule matches for unchanged input scope ${names.joinToString()}: " +
                (failure?.message ?: "rule matching did not complete"))
    } else null
}
