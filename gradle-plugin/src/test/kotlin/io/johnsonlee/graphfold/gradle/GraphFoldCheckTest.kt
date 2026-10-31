package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import java.time.Duration
import org.gradle.api.Action
import org.gradle.process.JavaForkOptions
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.workers.ProcessWorkerSpec
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals

class GraphFoldCheckTest {
    @Test
    @Suppress("UNCHECKED_CAST") // The mocked Gradle worker API preserves the submit parameter type.
    fun `task submits isolated work and awaits completion`() {
        val project = ProjectBuilder.builder().build()
        val workers = mock(WorkerExecutor::class.java)
        val queue = mock(WorkQueue::class.java)
        val spec = mock(ProcessWorkerSpec::class.java)
        val fork = mock(JavaForkOptions::class.java)
        `when`(spec.forkOptions).thenReturn(fork)
        `when`(workers.processIsolation(any())).thenAnswer { invocation ->
            (invocation.arguments[0] as Action<ProcessWorkerSpec>).execute(spec)
            queue
        }
        val parameters = project.objects.newInstance(GraphFoldParameters::class.java)
        doAnswer { invocation ->
            (invocation.arguments[1] as Action<GraphFoldParameters>).execute(parameters)
            null
        }.`when`(queue).submit(eq(GraphFoldWork::class.java), any())
        val task = project.tasks.register("isolated", GraphFoldCheck::class.java, workers).get()
        task.repository.set("repository")
        task.base.set("base")
        task.head.set("head")
        task.workspaceSlots.set(3)
        task.buildTimeout.set(1500L)
        task.leaseTimeout.set(1800L)
        task.buildEnv.set(DEFAULT_BUILD_ENVIRONMENT + "ARTIFACT_TOKEN")
        task.fold.set(project.layout.projectDirectory.file("fold.yml"))
        task.cacheDirectory.set(project.layout.projectDirectory.dir("cache"))
        task.jsonReport.set(project.layout.projectDirectory.file("report.json"))
        task.textReport.set(project.layout.projectDirectory.file("report.txt"))
        task.analyze()
        assertEquals("repository", parameters.repository.get())
        assertEquals("base", parameters.base.get())
        assertEquals("head", parameters.head.get())
        assertEquals(task.fold.get(), parameters.fold.get())
        assertEquals(task.cacheDirectory.get(), parameters.cacheDirectory.get())
        assertEquals(task.jsonReport.get(), parameters.jsonReport.get())
        assertEquals(task.textReport.get(), parameters.textReport.get())
        val configuration = parameters.engineConfiguration()
        assertEquals(3, configuration.maximumWorkspaceSlots)
        assertEquals(Duration.ofSeconds(1500), configuration.buildTimeout)
        assertEquals(Duration.ofSeconds(1800), configuration.workspaceLeaseTimeout)
        assertEquals(DEFAULT_BUILD_ENVIRONMENT + "ARTIFACT_TOKEN", configuration.buildEnvironmentAllowlist)
        verify(fork).setMaxHeapSize("2g")
        verify(queue).await()
    }
}
