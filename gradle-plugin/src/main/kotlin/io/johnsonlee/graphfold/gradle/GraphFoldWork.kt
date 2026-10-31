package io.johnsonlee.graphfold.gradle

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

interface GraphFoldParameters : WorkParameters {
    val repository: Property<String>
    val base: Property<String>
    val head: Property<String>
    val workspaceSlots: Property<Int>
    val buildTimeout: Property<Long>
    val leaseTimeout: Property<Long>
    val buildEnv: SetProperty<String>
    val fold: RegularFileProperty
    val cacheDirectory: DirectoryProperty
    val jsonReport: RegularFileProperty
    val textReport: RegularFileProperty
}

abstract class GraphFoldWork : WorkAction<GraphFoldParameters> {
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
            val message = if (result.diagnostics.any { it.code == "WORKSPACE_CAPACITY_TIMEOUT" }) {
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
