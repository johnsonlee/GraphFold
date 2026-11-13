package io.johnsonlee.graphfold.maven

import io.johnsonlee.graphfold.engine.AnalysisReports
import io.johnsonlee.graphfold.engine.RepositoryConfiguration
import io.johnsonlee.graphfold.engine.Verdict
import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import java.io.File
import java.nio.file.Files
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope

/** Compares immutable revision inputs; it never rebuilds either Git revision. */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
class GraphFoldCheckMojo : GraphFoldSnapshotMojo() {
    @field:Parameter(property = "graphfold.baseSnapshot", required = true)
    lateinit var baseSnapshot: File
    @field:Parameter(property = "graphfold.rules", defaultValue = "\${session.topLevelProject.basedir}/fold-rules.yml", required = true)
    lateinit var rules: File
    @field:Parameter(property = "graphfold.report", defaultValue = "\${project.build.directory}/graphfold/report.json", required = true)
    lateinit var report: File

    override fun execute() {
        if (skipAggregator()) return
        val base = project.baseSnapshot(baseSnapshot.toPath())
        val head = exportSnapshot()
        val result = StaticInputSnapshots.analyze(repository().toString(), base, head,
            Files.readString(rules.toPath()), RepositoryConfiguration(head.parent.resolve("analysis")))
        val requested = report.toPath().toAbsolutePath()
        val output = outputDirectory(requested.parent).resolve(requested.fileName)
        Files.createDirectories(output.parent)
        Files.writeString(output, AnalysisReports.json(result))
        if (result.verdict != Verdict.PASS) throw MojoFailureException("GraphFold found uncontrolled or unproved changes: $output")
        log.info("GraphFold control coverage passed: $output")
    }
}
