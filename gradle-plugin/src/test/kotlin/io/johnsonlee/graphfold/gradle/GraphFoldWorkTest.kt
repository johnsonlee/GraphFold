package io.johnsonlee.graphfold.gradle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_TIMEOUT
import io.johnsonlee.graphfold.engine.DEFAULT_MAXIMUM_WORKSPACE_SLOTS
import io.johnsonlee.graphfold.engine.defaultWorkspaceLeaseTimeout
import io.johnsonlee.graphfold.engine.git.GitWorkspaceManager
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphFoldWorkTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `worker writes pass then reports workspace capacity timeout distinctly`() {
        val repository = directory.resolve("repository").toFile().apply { mkdirs() }
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repository.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return output.trim()
        }
        git("init", "--quiet")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--allow-empty", "-m", "initial")
        val revision = git("rev-parse", "HEAD")
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val parameters = project.objects.newInstance(GraphFoldParameters::class.java)
        val fold = directory.resolve("pass.yml").apply {
            writeText("folds:\n  - select: 'MATCH (cs:CallSite) RETURN cs'\n    value: false\n")
        }
        parameters.workspaceSlots.set(DEFAULT_MAXIMUM_WORKSPACE_SLOTS)
        parameters.buildTimeout.set(DEFAULT_BUILD_TIMEOUT.seconds)
        parameters.leaseTimeout.set(defaultWorkspaceLeaseTimeout(DEFAULT_BUILD_TIMEOUT).seconds)
        parameters.buildEnv.set(DEFAULT_BUILD_ENVIRONMENT)
        parameters.repository.set(repository.path)
        parameters.base.set(revision)
        parameters.head.set(revision)
        parameters.fold.set(fold.toFile())
        parameters.cacheDirectory.set(directory.resolve("cache").toFile())
        parameters.jsonReport.set(directory.resolve("reports/pass.json").toFile())
        parameters.textReport.set(directory.resolve("reports/pass.txt").toFile())
        val work = object : GraphFoldWork() {
            override fun getParameters() = parameters
        }
        work.execute()
        assertTrue(directory.resolve("reports/pass.json").readText().contains("PASS"))
        assertInfoLogging(work, directory.resolve("reports/pass.json"))
        parameters.workspaceSlots.set(1)
        parameters.leaseTimeout.set(1L)
        GitWorkspaceManager(directory.resolve("cache/git"), maximumWorkspaceSlots = 1).open(repository.path, revision, revision).use {
            val failure = assertFailsWith<GradleException> { work.execute() }
            assertTrue(failure.message.orEmpty().contains("timed out waiting for workspace capacity"))
            assertTrue(directory.resolve("reports/pass.json").readText().contains("WORKSPACE_CAPACITY_TIMEOUT"))
            assertTrue(directory.resolve("reports/pass.txt").readText().contains("WORKSPACE_CAPACITY_TIMEOUT"))
        }
    }

    private fun assertInfoLogging(work: GraphFoldWork, report: Path) {
        val logger = mock(Logger::class.java)
        `when`(logger.isInfoEnabled).thenReturn(true)
        mockStatic(Logging::class.java, CALLS_REAL_METHODS).use { logging ->
            logging.`when`<Logger> { Logging.getLogger(GraphFoldWork::class.java) }.thenReturn(logger)
            work.execute()
        }
        val mapper = jacksonObjectMapper()
        val audits = mapper.readTree(report.toFile())["ruleAudits"]
        assertEquals(2, audits.size())
        audits.forEach { audit ->
            verify(logger).info("[graphfold.fold] ${mapper.writeValueAsString(audit)}")
            verify(logger).lifecycle(
                "[graphfold.fold] ${audit["scenario"].asText()}/${audit["revision"].asText()}/rule#0: " +
                    "NOT_EXECUTED unknown sites, unknown matched",
            )
        }
    }

    @Test
    fun `worker writes a failure report before failing the gate`() {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val parameters = project.objects.newInstance(GraphFoldParameters::class.java)
        val fold = directory.resolve("fold.yml").apply {
            writeText("folds:\n  - select: 'MATCH (cs:CallSite) RETURN cs'\n    value: false\n")
        }
        parameters.workspaceSlots.set(DEFAULT_MAXIMUM_WORKSPACE_SLOTS)
        parameters.buildTimeout.set(DEFAULT_BUILD_TIMEOUT.seconds)
        parameters.leaseTimeout.set(defaultWorkspaceLeaseTimeout(DEFAULT_BUILD_TIMEOUT).seconds)
        parameters.buildEnv.set(DEFAULT_BUILD_ENVIRONMENT)
        parameters.repository.set(directory.resolve("missing-repository").toString())
        parameters.base.set("0123456789abcdef")
        parameters.head.set("fedcba9876543210")
        parameters.fold.set(fold.toFile())
        parameters.cacheDirectory.set(directory.resolve("cache").toFile())
        parameters.jsonReport.set(directory.resolve("reports/report.json").toFile())
        parameters.textReport.set(directory.resolve("reports/report.txt").toFile())
        val work = object : GraphFoldWork() {
            override fun getParameters() = parameters
        }
        assertFailsWith<GradleException> { work.execute() }
        assertTrue(directory.resolve("reports/report.json").readText().contains("FAIL"))
        assertTrue(directory.resolve("reports/report.txt").readText().contains("GraphFold: FAIL"))
    }
}
