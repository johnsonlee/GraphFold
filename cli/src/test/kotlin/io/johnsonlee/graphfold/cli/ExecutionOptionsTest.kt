package io.johnsonlee.graphfold.cli

import com.github.ajalt.clikt.testing.test
import io.johnsonlee.graphfold.engine.AnalysisMetadata
import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.AnalysisStage
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_TIMEOUT
import io.johnsonlee.graphfold.engine.DEFAULT_MAXIMUM_WORKSPACE_SLOTS
import io.johnsonlee.graphfold.engine.Diagnostic
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.defaultWorkspaceLeaseTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExecutionOptionsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `defaults follow engine configuration`() {
        val configuration = configuration(emptyList())
        assertEquals(DEFAULT_MAXIMUM_WORKSPACE_SLOTS, configuration.maximumWorkspaceSlots)
        assertEquals(DEFAULT_BUILD_TIMEOUT, configuration.buildTimeout)
        assertEquals(defaultWorkspaceLeaseTimeout(DEFAULT_BUILD_TIMEOUT), configuration.workspaceLeaseTimeout)
        assertEquals(DEFAULT_BUILD_ENVIRONMENT, configuration.buildEnvironmentAllowlist)
    }

    @Test
    fun `custom settings transfer and environment names extend defaults without duplicates`() {
        val configuration = configuration(listOf(
            "--workspace-slots", "5", "--build-timeout", "17", "--lease-timeout", "41",
            "--build-env", "GRAPHFOLD_TEST_TOKEN", "--build-env", "GRAPHFOLD_TEST_TOKEN",
            "--build-env", "_custom2", "--build-env", "PATH",
        ))
        assertEquals(5, configuration.maximumWorkspaceSlots)
        assertEquals(Duration.ofSeconds(17), configuration.buildTimeout)
        assertEquals(Duration.ofSeconds(41), configuration.workspaceLeaseTimeout)
        assertEquals(DEFAULT_BUILD_ENVIRONMENT + setOf("GRAPHFOLD_TEST_TOKEN", "_custom2", "PATH"),
            configuration.buildEnvironmentAllowlist)
    }

    @Test
    fun `lease default derives from custom build timeout and explicit option takes precedence`() {
        val derived = configuration(listOf("--build-timeout", "2000"))
        assertEquals(Duration.ofSeconds(4300), derived.workspaceLeaseTimeout)
        val explicit = configuration(listOf("--lease-timeout", "3", "--build-timeout", "2000"))
        assertEquals(Duration.ofSeconds(3), explicit.workspaceLeaseTimeout)
    }

    @Test
    fun `largest supported integers remain exact`() {
        val configuration = configuration(listOf(
            "--workspace-slots", Int.MAX_VALUE.toString(),
            "--build-timeout", Int.MAX_VALUE.toString(), "--lease-timeout", Int.MAX_VALUE.toString(),
        ))
        assertEquals(Int.MAX_VALUE, configuration.maximumWorkspaceSlots)
        assertEquals(Duration.ofSeconds(Int.MAX_VALUE.toLong()), configuration.buildTimeout)
        assertEquals(configuration.buildTimeout, configuration.workspaceLeaseTimeout)
    }

    @ParameterizedTest
    @ValueSource(strings = ["--workspace-slots", "--build-timeout", "--lease-timeout"])
    fun `numeric options reject missing nonpositive noninteger and overflowing values`(option: String) {
        val invalid = listOf(emptyList(), listOf("0"), listOf("-1"), listOf("1.5"), listOf("abc"),
            listOf("2147483648"), listOf("999999999999999999999999"))
        for (value in invalid) {
            val command = GraphFoldCommand { _, _ -> error("Invalid inputs must not reach analysis") }
            assertEquals(2, executeCli((arguments() + option + value).toTypedArray(), command), "$option $value")
        }
    }

    @Test
    fun `environment option rejects missing names assignments and invalid names`() {
        for (value in listOf(emptyList(), listOf(""), listOf("TOKEN=secret"), listOf("1TOKEN"),
            listOf("HAS-DASH"), listOf("HAS SPACE"), listOf("TOKEN\n"))) {
            val command = GraphFoldCommand { _, _ -> error("Invalid inputs must not reach analysis") }
            assertEquals(2, executeCli((arguments() + "--build-env" + value).toTypedArray(), command), value.toString())
        }
    }

    @Test
    fun `capacity timeout is retryable while other operational errors take precedence`() {
        val capacity = Diagnostic(AnalysisStage.GIT, "WORKSPACE_CAPACITY_TIMEOUT", "No checkout available")
        assertStatus(3, listOf(capacity))
        assertStatus(3, listOf(capacity, Diagnostic(AnalysisStage.GRAPH, "gap", "Analysis incomplete")))
        assertStatus(2, listOf(capacity, Diagnostic(AnalysisStage.BUILD, "failed", "Compilation failed")))
        assertStatus(2, listOf(capacity, Diagnostic(AnalysisStage.GIT, "invalid", "Invalid revision")))
        assertStatus(3, listOf(capacity, Diagnostic(AnalysisStage.BUILD, "note", "Build note", false)))
        assertStatus(0, listOf(capacity.copy(blocking = false)))
        assertStatus(1, listOf(capacity.copy(stage = AnalysisStage.GRAPH)))
        assertStatus(2, listOf(capacity.copy(stage = AnalysisStage.INPUT)))
    }

    @Test
    fun `help describes resource settings`() {
        val output = GraphFoldCommand { _, _ -> error("Help must not analyze") }.test("--help")
        assertEquals(0, output.statusCode)
        for (option in listOf("--workspace-slots", "--build-timeout", "--lease-timeout", "--build-env")) {
            assertTrue(output.stdout.contains(option))
        }
    }

    private fun configuration(options: List<String>): EngineConfiguration {
        var captured: EngineConfiguration? = null
        val command = GraphFoldCommand { configuration, _ ->
            captured = configuration
            result(emptyList())
        }
        val output = command.test(arguments() + options)
        assertEquals(0, output.statusCode, output.output)
        return assertNotNull(captured)
    }

    private fun assertStatus(expected: Int, diagnostics: List<Diagnostic>) {
        val command = GraphFoldCommand { _, _ -> result(diagnostics) }
        val output = command.test(arguments())
        assertEquals(expected, output.statusCode, output.output)
    }

    private fun result(diagnostics: List<Diagnostic>) = AnalysisResult(
        emptyList(), diagnostics, AnalysisMetadata("base", "head", listOf("baseline"), 0, 0),
    )

    private fun arguments(): List<String> {
        val fold = directory.resolve("fold.yml").apply { writeText("folds: []") }
        return listOf("--repository", "repo", "--base", "base", "--head", "head", "--fold", fold.toString())
    }
}
