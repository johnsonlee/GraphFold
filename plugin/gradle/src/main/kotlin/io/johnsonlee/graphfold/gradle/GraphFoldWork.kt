package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisReports
import io.johnsonlee.graphfold.engine.RepositoryConfiguration
import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import io.johnsonlee.graphfold.engine.Verdict
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.nio.file.Files

/** Serializable Gradle-managed inputs and report destinations for an isolated [GraphFoldWork] execution. */
interface GraphFoldParameters : WorkParameters {
    /** Git repository URL or local filesystem path to analyze. */
    val repository: Property<String>
    /** Immutable BASE snapshot manifest. */
    val baseInputs: RegularFileProperty
    /** Immutable HEAD snapshot manifest. */
    val headInputs: RegularFileProperty
    /** YAML file supplying fold rules and optional analysis scenarios. */
    val fold: RegularFileProperty
    /** Shared location of Git objects, reusable checkouts, and analysis state. */
    val cacheDirectory: DirectoryProperty
    /** File to receive the machine-readable analysis result. */
    val jsonReport: RegularFileProperty
    /** File to receive the human-readable analysis result. */
    val textReport: RegularFileProperty
}

/** Runs the engine in a Gradle worker, logs fold selections, and persists both result formats. */
abstract class GraphFoldWork : WorkAction<GraphFoldParameters> {
    /**
     * Analyzes the supplied revisions and writes reports before enforcing the verdict.
     *
     * @throws GradleException when the engine returns a failing verdict, including analysis gaps or capacity timeouts.
     */
    override fun execute() {
        val result = StaticInputSnapshots.analyze(
            parameters.repository.get(), parameters.baseInputs.get().asFile.toPath(),
            parameters.headInputs.get().asFile.toPath(), parameters.fold.get().asFile.readText(),
            RepositoryConfiguration(cacheDirectory = parameters.cacheDirectory.get().asFile.toPath()),
        )
        val logger = Logging.getLogger(GraphFoldWork::class.java)
        AnalysisReports.foldSelectionSummary(result).forEach(logger::lifecycle)
        if (logger.isInfoEnabled) {
            AnalysisReports.foldSelectionLog(result).forEach(logger::info)
        }
        val json = parameters.jsonReport.get().asFile.toPath()
        val text = parameters.textReport.get().asFile.toPath()
        Files.createDirectories(json.parent)
        Files.createDirectories(text.parent)
        Files.writeString(json, AnalysisReports.json(result))
        Files.writeString(text, AnalysisReports.text(result))
        if (result.verdict == Verdict.FAIL) {
            throw GradleException("GraphFold detected uncontrolled or unverifiable changes; see $json")
        }
    }
}
