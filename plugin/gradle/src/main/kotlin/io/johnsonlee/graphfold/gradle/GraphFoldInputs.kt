package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.inputs.ArchiveScopeInput
import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import io.johnsonlee.graphfold.engine.inputs.DeclaredSourceRoot
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Gradle-managed input model captured from the configured project, without retaining a Project in the task action. */
abstract class GraphFoldInputScope {
    @get:Input abstract val identity: Property<String>
    @get:Input abstract val javaVersion: Property<Int>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archive: RegularFileProperty
    @get:Classpath abstract val dependencies: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceRoots: ConfigurableFileCollection
    @get:Classpath abstract val sourceOutputs: ConfigurableFileCollection
    @get:Classpath abstract val sourceClasspath: ConfigurableFileCollection
    @get:Input abstract val sourceOutputPaths: MapProperty<String, List<String>>
    @get:Input abstract val sourceClasspathPaths: MapProperty<String, List<String>>
    @get:Input abstract val projectArtifactPaths: MapProperty<String, List<String>>
    @get:Input abstract val declaredSourceRoots: MapProperty<String, List<String>>

    internal fun input(): ArchiveScopeInput {
        val roots = sourceRoots.files.map { it.toPath() }
        return ArchiveScopeInput(
            id = identity.get(), archive = archive.get().asFile.toPath(), javaVersion = javaVersion.get(),
            dependencies = dependencies.files.map { it.toPath() }, sourceRoots = roots,
            sourceOutputs = sourceOutputPaths.get().pathMap(),
            sourceClasspaths = sourceClasspathPaths.get().pathMap(),
            projectArtifacts = projectArtifactPaths.get().pathMap(),
            declaredSources = declaredSourceRoots.get().flatMap { (owner, directories) ->
                directories.map { DeclaredSourceRoot(owner.substringBeforeLast('/'), owner.substringAfterLast('/'), Path.of(it)) }
            },
        )
    }
}

/** Exports this checkout's packaged outputs after their ordinary Gradle producer tasks have run. */
@DisableCachingByDefault(because = "Snapshots bind packaged input bytes to the current Git revision")
abstract class GraphFoldInputs : DefaultTask() {
    @get:Internal abstract val repository: DirectoryProperty
    @get:Nested abstract val scopes: ListProperty<GraphFoldInputScope>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun export() {
        StaticInputSnapshots.write(
            repository.get().asFile.toPath(), outputDirectory.file("inputs.json").get().asFile.toPath(),
            scopes.get().map { it.input() },
        )
    }
}

private fun Map<String, List<String>>.pathMap(): Map<Path, List<Path>> =
    entries.associate { (root, paths) -> Path.of(root) to paths.map { Path.of(it) } }
