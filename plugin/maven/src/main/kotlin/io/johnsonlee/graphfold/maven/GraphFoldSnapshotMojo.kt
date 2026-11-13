package io.johnsonlee.graphfold.maven

import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import java.io.File
import java.nio.file.Path
import javax.inject.Inject
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.project.MavenProject
import org.apache.maven.toolchain.ToolchainManager
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope

/** Runs after packaging, with Maven's already resolved model for the current project. */
@Mojo(name = "snapshot", defaultPhase = LifecyclePhase.VERIFY,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
open class GraphFoldSnapshotMojo : AbstractMojo() {
    @field:Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject
    @field:Parameter(defaultValue = "\${session}", readonly = true, required = true)
    lateinit var session: MavenSession
    @field:Inject
    lateinit var toolchainManager: ToolchainManager
    @field:Parameter(property = "graphfold.snapshotDirectory", defaultValue = "\${project.build.directory}/graphfold/snapshot",
        required = true)
    lateinit var snapshotDirectory: File

    override fun execute() {
        if (skipAggregator()) return
        val manifest = exportSnapshot()
        log.info("GraphFold static input snapshot: $manifest")
    }

    internal fun skipAggregator(): Boolean {
        val aggregator = project.packaging == "pom"
        if (aggregator) log.info("GraphFold: aggregator POM has no packaged bytecode input")
        return aggregator
    }

    internal fun repository(): Path = session.topLevelProject.basedir.toPath()

    internal fun outputDirectory(root: Path): Path = if (session.projects.count { it.packaging != "pom" } > 1) {
        project.moduleDirectory(root)
    } else root

    internal fun snapshotManifest(): Path = outputDirectory(snapshotDirectory.toPath()).resolve("snapshot.json")

    internal fun exportSnapshot(): Path = StaticInputSnapshots.write(repository(), snapshotManifest(),
        listOf(project.staticInput(buildJavaVersion(project, session, toolchainManager), session.projects)))
}
