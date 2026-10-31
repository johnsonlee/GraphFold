package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Common report encoding for the CLI, Gradle plugin, and SDK consumers. */
object AnalysisReports {
    private val mapper = jacksonObjectMapper()

    /**
     * Encodes [result] as indented JSON under the versioned [schema] contract.
     *
     * @return JSON text including derived verdict, reasons, diagnostics, and complete rule audits.
     */
    fun json(result: AnalysisResult): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)

    /** The packaged JSON Schema applies to the serialized result, independently of its output filename. */
    fun schema(): String = javaClass.getResource("verdict.schema.json").readText()

    /** One structured log line per revision/rule, including every selected node; callers choose the log destination. */
    fun foldSelectionLog(result: AnalysisResult): List<String> = result.ruleAudits.map {
        "[graphfold.fold] ${mapper.writeValueAsString(it)}"
    }

    /**
     * Summarizes each scenario/revision/rule without serializing selected nodes or query text.
     *
     * @param result Analysis report containing the available rule audits.
     * @return One line per audit, with selection status and separate selected/matched counts;
     *   unavailable counts are shown as `unknown`, not zero. Line size does not grow with the node list.
     */
    fun foldSelectionSummary(result: AnalysisResult): List<String> = result.ruleAudits.map {
        "[graphfold.fold] ${it.scenario}/${it.revision}/rule#${it.ruleIndex}: " +
            "${it.selectionStatus} ${it.selectedCount ?: "unknown"} sites, ${it.matchedCallCount ?: "unknown"} matched"
    }

    /**
     * Renders [result] for a terminal, including proof counts, selection summaries, reasons, findings, and diagnostics.
     *
     * @return Human-readable report without a trailing newline; not a stable machine-readable format.
     */
    fun text(result: AnalysisResult): String = buildString {
        appendLine("GraphFold: ${result.verdict}")
        appendLine("Proved ${result.metadata.provedObligations}/${result.metadata.obligations} change obligations")
        foldSelectionSummary(result).forEach { appendLine(it) }
        result.reasons.forEach { reason ->
            appendLine("${reason.code} [${reason.changes.joinToString()}]: ${reason.message}")
        }
        result.findings.forEach { finding ->
            appendLine("${finding.kind}: ${finding.message}")
            finding.locations.forEach { location ->
                appendLine(
                    "  ${location.revision} ${location.path}:" +
                        "${location.line.start}:${location.column.start}-${location.line.end}:${location.column.end}",
                )
            }
        }
        result.diagnostics.forEach { diagnostic ->
            appendLine("${diagnostic.stage}/${diagnostic.code}: ${diagnostic.message}")
        }
    }.trimEnd()
}
