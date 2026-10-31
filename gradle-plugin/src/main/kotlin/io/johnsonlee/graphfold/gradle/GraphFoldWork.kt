package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.DiagnosticCode
import io.johnsonlee.graphfold.engine.AnalysisReports
import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import io.johnsonlee.graphfold.engine.Verdict
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.nio.file.Files
import java.time.Duration

/** Serializable Gradle-managed inputs and report destinations for an isolated [GraphFoldWork] execution. */
interface GraphFoldParameters : WorkParameters {
    /** Git repository URL or local filesystem path to analyze. */
    val repository: Property<String>
    /** Base commit SHA used as the comparison baseline. */
    val base: Property<String>
    /** Head commit SHA containing the proposed changes. */
    val head: Property<String>
    /** Positive maximum number of concurrent checkout pairs in [cacheDirectory]. */
    val workspaceSlots: Property<Int>
    /** Positive timeout in whole seconds for each external build command. */
    val buildTimeout: Property<Long>
    /** Positive timeout in whole seconds for waiting to acquire a checkout pair. */
    val leaseTimeout: Property<Long>
    /** Environment-variable names allowed to be inherited by external builds. */
    val buildEnv: SetProperty<String>
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
        val request = AnalysisRequest(
            parameters.repository.get(), parameters.base.get(), parameters.head.get(),
            parameters.fold.get().asFile.readText(),
        )
        val result = GraphFoldEngine(parameters.engineConfiguration()).analyze(request)
        val logger = Logging.getLogger(GraphFoldWork::class.java)
        AnalysisReports.foldSelectionLog(result).forEach(logger::lifecycle)
        val json = parameters.jsonReport.get().asFile.toPath()
        val text = parameters.textReport.get().asFile.toPath()
        Files.createDirectories(json.parent)
        Files.createDirectories(text.parent)
        Files.writeString(json, AnalysisReports.json(result))
        Files.writeString(text, AnalysisReports.text(result))
        if (result.verdict == Verdict.FAIL) {
            val message = if (result.diagnostics.any { it.code == DiagnosticCode.WORKSPACE_CAPACITY_TIMEOUT }) {
                "GraphFold timed out waiting for workspace capacity; increase graphfold.leaseTimeout or graphfold.workspaceSlots"
            } else {
                "GraphFold detected uncontrolled or unverifiable changes"
            }
            throw GradleException("$message; see $json")
        }
    }
}

internal fun GraphFoldParameters.engineConfiguration(): EngineConfiguration = EngineConfiguration(
    cacheDirectory = cacheDirectory.get().asFile.toPath(),
    buildTimeout = Duration.ofSeconds(buildTimeout.get()),
    buildEnvironmentAllowlist = buildEnv.get(),
    maximumWorkspaceSlots = workspaceSlots.get(),
    workspaceLeaseTimeout = Duration.ofSeconds(leaseTimeout.get()),
)
