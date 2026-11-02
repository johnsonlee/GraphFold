package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_TIMEOUT
import io.johnsonlee.graphfold.engine.DEFAULT_MAXIMUM_WORKSPACE_SLOTS
import io.johnsonlee.graphfold.engine.defaultWorkspaceLeaseTimeout
import java.time.Duration
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
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

/**
 * Compares two Git revisions under fold rules in a separate Gradle worker process.
 *
 * The task always invokes the engine, which manages checkout reuse and analysis input identity.
 * Target-project builds can still use their own incremental compilation and build caches.
 *
 * @param workers Gradle-provided executor used to isolate compiler and engine dependencies.
 */
@DisableCachingByDefault(because = "Remote repositories and external builds require engine-managed input identity")
abstract class GraphFoldCheck @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    /** Git repository URL or local path; defaults to the root project's directory when registered by the plugin. */
    @get:Input abstract val repository: Property<String>
    /** Base commit SHA against which [head] is compared. */
    @get:Input abstract val base: Property<String>
    /** Head commit SHA containing the changes to analyze. */
    @get:Input abstract val head: Property<String>
    /** Maximum number of checkout pairs available concurrently in the shared cache; must be positive. */
    @get:Input abstract val workspaceSlots: Property<Int>
    /** Timeout for each external build command, in positive whole seconds. */
    @get:Input abstract val buildTimeout: Property<Long>
    /** Maximum wait for a free checkout pair, in positive whole seconds; excludes time spent using the pair. */
    @get:Input abstract val leaseTimeout: Property<Long>
    /** Names of environment variables external builds may inherit; entries are names, not values. */
    @get:Input abstract val buildEnv: SetProperty<String>
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

    init {
        workspaceSlots.convention(DEFAULT_MAXIMUM_WORKSPACE_SLOTS)
        buildTimeout.convention(DEFAULT_BUILD_TIMEOUT.seconds)
        leaseTimeout.convention(buildTimeout.map { defaultWorkspaceLeaseTimeout(Duration.ofSeconds(it)).seconds })
        buildEnv.convention(DEFAULT_BUILD_ENVIRONMENT)
        outputs.upToDateWhen { false }
    }

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
            parameters.base.set(base)
            parameters.head.set(head)
            parameters.workspaceSlots.set(workspaceSlots)
            parameters.buildTimeout.set(buildTimeout)
            parameters.leaseTimeout.set(leaseTimeout)
            parameters.buildEnv.set(buildEnv)
            parameters.fold.set(fold)
            parameters.cacheDirectory.set(cacheDirectory)
            parameters.jsonReport.set(jsonReport)
            parameters.textReport.set(textReport)
        }
        queue.await()
    }
}
