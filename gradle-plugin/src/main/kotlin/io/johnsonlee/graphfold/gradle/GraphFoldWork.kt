package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisReports
import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import io.johnsonlee.graphfold.engine.Verdict
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.nio.file.Files

interface GraphFoldParameters : WorkParameters {
    val repository: Property<String>
    val base: Property<String>
    val head: Property<String>
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
        val result = GraphFoldEngine(EngineConfiguration(parameters.cacheDirectory.get().asFile.toPath())).analyze(request)
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
