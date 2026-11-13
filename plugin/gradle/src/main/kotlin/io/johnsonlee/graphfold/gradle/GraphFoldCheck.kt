package io.johnsonlee.graphfold.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/** Compares independently exported revision snapshots in an isolated analysis worker. */
@DisableCachingByDefault(because = "Analysis binds exported static inputs to Git revision differences")
abstract class GraphFoldCheck @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    /** Git repository URL or local path; defaults to the root project's directory when registered by the plugin. */
    @get:Input abstract val repository: Property<String>
    /** Immutable manifest exported from a build of the baseline revision. */
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseInputs: RegularFileProperty
    /** Immutable manifest exported from a separate build of the proposed revision. */
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val headInputs: RegularFileProperty
    /** YAML file containing fold rules and optional analysis scenarios. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fold: RegularFileProperty
    /** Shared directory for Git objects, reusable checkouts, and engine-managed analysis state. */
    @get:LocalState abstract val cacheDirectory: DirectoryProperty
    /** Destination for the machine-readable analysis result, written before an analysis failure is reported. */
    @get:OutputFile abstract val jsonReport: RegularFileProperty
    /** Destination for the human-readable analysis result, written before an analysis failure is reported. */
    @get:OutputFile abstract val textReport: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    /**
     * Submits the configured analysis to an isolated worker and waits for its reports and verdict.
     *
     * Worker failures, including a failing verdict, fail this task through Gradle's worker infrastructure.
     */
    @TaskAction
    fun analyze() {
        val queue = workers.processIsolation { spec -> spec.forkOptions.maxHeapSize = "2g" }
        queue.submit(GraphFoldWork::class.java) { parameters ->
            parameters.repository.set(repository)
            parameters.baseInputs.set(baseInputs)
            parameters.headInputs.set(headInputs)
            parameters.fold.set(fold)
            parameters.cacheDirectory.set(cacheDirectory)
            parameters.jsonReport.set(jsonReport)
            parameters.textReport.set(textReport)
        }
        queue.await()
    }
}
