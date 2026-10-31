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
     * Renders [result] for a terminal, including proof counts, full selection logs, reasons, findings, and diagnostics.
     *
     * @return Human-readable report without a trailing newline; not a stable machine-readable format.
     */
    fun text(result: AnalysisResult): String = buildString {
        appendLine("GraphFold: ${result.verdict}")
        appendLine("Proved ${result.metadata.provedObligations}/${result.metadata.obligations} change obligations")
        foldSelectionLog(result).forEach { appendLine(it) }
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
