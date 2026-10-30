package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Common report encoding for the CLI, Gradle plugin, and SDK consumers. */
object AnalysisReports {
    fun json(result: AnalysisResult): String = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result)

    fun text(result: AnalysisResult): String = buildString {
        appendLine("GraphFold: ${result.verdict}")
        appendLine("Proved ${result.metadata.provedObligations}/${result.metadata.obligations} change obligations")
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
