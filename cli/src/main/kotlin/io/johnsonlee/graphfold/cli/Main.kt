package io.johnsonlee.graphfold.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import io.johnsonlee.graphfold.engine.AnalysisReports
import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.AnalysisStage
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_TIMEOUT
import io.johnsonlee.graphfold.engine.DEFAULT_MAXIMUM_WORKSPACE_SLOTS
import io.johnsonlee.graphfold.engine.Diagnostic
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import io.johnsonlee.graphfold.engine.Verdict
import io.johnsonlee.graphfold.engine.defaultWorkspaceLeaseTimeout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(executeCli(args))
}

internal fun executeCli(args: Array<String>, command: GraphFoldCommand = GraphFoldCommand()): Int = try {
    command.parse(args)
    0
} catch (result: ProgramResult) {
    result.statusCode
} catch (error: CliktError) {
    command.echoFormattedHelp(error)
    if (error.statusCode == 0) 0 else 2
}

internal class GraphFoldCommand(
    private val analyze: (EngineConfiguration, AnalysisRequest) -> AnalysisResult = { configuration, request ->
        GraphFoldEngine(configuration).analyze(request)
    },
) : CliktCommand(name = "graphfold") {
    init {
        versionOption(releaseVersion())
    }

    private val repository by option("--repository", help = "Git repository URL or local path").required()
    private val base by option("--base", help = "Base commit SHA").required()
    private val head by option("--head", help = "Head commit SHA").required()
    private val fold by option("--fold", help = "YAML fold rules, including optional scenarios")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true).required()
    private val cacheDirectory by option("--cache-dir", help = "Shared Git and analysis cache directory")
        .path(canBeFile = false).default(Path.of(System.getProperty("user.home"), ".cache", "graphfold"))
    private val format by option("--format", help = "Report format").choice("json", "text").default("text")

    private val workspaceSlots by option("--workspace-slots", help = "Maximum concurrent checkout pairs in the shared cache")
        .int().default(DEFAULT_MAXIMUM_WORKSPACE_SLOTS).check("must be positive") { it > 0 }
    private val buildTimeout by option("--build-timeout", help = "Timeout per external build command, in positive integer seconds")
        .int().default(DEFAULT_BUILD_TIMEOUT.seconds.toInt()).check("must be positive") { it > 0 }
    private val leaseTimeout by option(
        "--lease-timeout", help = "Wait for a free workspace slot, in seconds (default: twice build timeout plus 300)",
    ).int().check("must be positive") { it > 0 }
    private val buildEnvironment by option("--build-env", help = "Additional inherited environment variable name; repeatable")
        .multiple().check("must be an environment variable name, not a value") { names ->
            names.all { it.matches(ENVIRONMENT_NAME) }
        }

    @Suppress("ThrowsCount") // Clikt represents process exit codes as ProgramResult.
    override fun run() {
        val result = try {
            val request = AnalysisRequest(repository, base, head, Files.readString(fold))
            val timeout = Duration.ofSeconds(buildTimeout.toLong())
            val configuration = EngineConfiguration(
                cacheDirectory = cacheDirectory,
                buildTimeout = timeout,
                maximumWorkspaceSlots = workspaceSlots,
                workspaceLeaseTimeout = leaseTimeout?.let { Duration.ofSeconds(it.toLong()) }
                    ?: defaultWorkspaceLeaseTimeout(timeout),
                buildEnvironmentAllowlist = DEFAULT_BUILD_ENVIRONMENT + buildEnvironment,
            )
            analyze(configuration, request)
        } catch (error: IllegalArgumentException) {
            echo(error.message, err = true)
            throw ProgramResult(INPUT_FAILURE)
        } catch (error: IOException) {
            echo(error.message, err = true)
            throw ProgramResult(INPUT_FAILURE)
        }
        AnalysisReports.foldSelectionLog(result).forEach { echo(it, err = true) }
        echo(if (format == "json") AnalysisReports.json(result) else AnalysisReports.text(result))
        val exitCode = result.exitCode()
        if (exitCode != SUCCESS) throw ProgramResult(exitCode)
    }

    private fun AnalysisResult.exitCode(): Int = when {
        diagnostics.any { it.blocking && it.stage in operationalStages && !it.isCapacityTimeout() } -> INPUT_FAILURE
        diagnostics.any { it.blocking && it.isCapacityTimeout() } -> CAPACITY_TIMEOUT
        verdict == Verdict.FAIL -> VIOLATION
        else -> SUCCESS
    }

    private fun Diagnostic.isCapacityTimeout(): Boolean =
        stage == AnalysisStage.GIT && code == "WORKSPACE_CAPACITY_TIMEOUT"

    private companion object {
        const val SUCCESS = 0
        const val VIOLATION = 1
        const val INPUT_FAILURE = 2
        const val CAPACITY_TIMEOUT = 3
        val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val operationalStages = setOf(AnalysisStage.INPUT, AnalysisStage.GIT, AnalysisStage.BUILD)
    }
}

/** Packaged at build time from the version used for publications and distribution archives. */
internal fun releaseVersion(): String = GraphFoldCommand::class.java.getResource("version.txt").readText().trim()
