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

@DisableCachingByDefault(because = "Remote repositories and external builds require engine-managed input identity")
abstract class GraphFoldCheck @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    @get:Input abstract val repository: Property<String>
    @get:Input abstract val base: Property<String>
    @get:Input abstract val head: Property<String>
    @get:Input abstract val workspaceSlots: Property<Int>
    @get:Input abstract val buildTimeout: Property<Long>
    @get:Input abstract val leaseTimeout: Property<Long>
    @get:Input abstract val buildEnv: SetProperty<String>
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fold: RegularFileProperty
    @get:LocalState abstract val cacheDirectory: DirectoryProperty
    @get:OutputFile abstract val jsonReport: RegularFileProperty
    @get:OutputFile abstract val textReport: RegularFileProperty

    init {
        workspaceSlots.convention(DEFAULT_MAXIMUM_WORKSPACE_SLOTS)
        buildTimeout.convention(DEFAULT_BUILD_TIMEOUT.seconds)
        leaseTimeout.convention(buildTimeout.map { defaultWorkspaceLeaseTimeout(Duration.ofSeconds(it)).seconds })
        buildEnv.convention(DEFAULT_BUILD_ENVIRONMENT)
        outputs.upToDateWhen { false }
    }

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
