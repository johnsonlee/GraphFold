package io.johnsonlee.graphfold.maven

import io.johnsonlee.graphfold.engine.inputs.ArchiveScopeInput
import io.johnsonlee.graphfold.engine.inputs.DeclaredSourceRoot
import java.nio.file.Files
import java.nio.file.Path
import org.apache.maven.artifact.Artifact
import org.apache.maven.project.MavenProject

/** Reads the current lifecycle's resolved model; it neither builds nor resolves another checkout. */
internal fun MavenProject.staticInput(javaVersion: Int, reactor: List<MavenProject> = emptyList()): ArchiveScopeInput {
    val archive = requireNotNull(artifact?.file) { "Package the Maven project before exporting GraphFold inputs" }.toPath()
    require(Files.isRegularFile(archive)) { "Packaged Maven artifact is missing: $archive" }
    val selected = artifacts.filter { it.scope in INPUT_SCOPES }
    val resolved = selected.associateWith { dependency ->
        val file = requireNotNull(dependency.file) { "Maven dependency has no resolved artifact: ${dependency.id}" }.toPath()
        require(Files.isRegularFile(file)) { "Resolved Maven dependency is missing: $file" }
        file.toRealPath()
    }
    val producers = resolved.mapNotNull { (dependency, file) ->
        reactor.firstOrNull { it.artifact?.id == dependency.id }?.let { file to it }
    }.toMap() + (archive.toRealPath() to this)
    val sourceOutputs = producers.values.flatMap { producer ->
        producer.compileSourceRoots.map { Path.of(it).toAbsolutePath().normalize() to listOf(producer.classes()) }
    }.toMap()
    val sources = producers.values.flatMap { producer ->
        producer.compileSourceRoots.map { DeclaredSourceRoot(producer.identity(), "main", Path.of(it).toAbsolutePath().normalize()) } +
            producer.testCompileSourceRoots.map {
                DeclaredSourceRoot(producer.identity(), "test", Path.of(it).toAbsolutePath().normalize())
            }
    }
    val classpaths = producers.values.flatMap { producer ->
        val dependencies = producer.artifacts.filter { it.scope in INPUT_SCOPES }.mapNotNull { it.file?.toPath() }
        producer.compileSourceRoots.map { Path.of(it).toAbsolutePath().normalize() to dependencies }
    }.toMap()
    return ArchiveScopeInput(identity(), archive.toRealPath(), resolved.values.distinct(), sourceOutputs.keys.toList(),
        sourceOutputs = sourceOutputs, declaredSources = sources, sourceClasspaths = classpaths,
        projectArtifacts = producers.mapValues { (_, producer) -> listOf(producer.classes()) }, javaVersion = javaVersion)
}

private fun MavenProject.identity() = "$groupId:$artifactId"
private fun MavenProject.classes(): Path = Path.of(build.outputDirectory).toAbsolutePath().normalize()

private val INPUT_SCOPES = setOf(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME, Artifact.SCOPE_PROVIDED, Artifact.SCOPE_SYSTEM)
