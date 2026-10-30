package io.johnsonlee.graphfold.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import io.johnsonlee.graphfold.engine.AnalysisReports
import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.AnalysisStage
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import io.johnsonlee.graphfold.engine.Verdict
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
    private val repository by option("--repository", help = "Git repository URL or local path").required()
    private val base by option("--base", help = "Base commit SHA").required()
    private val head by option("--head", help = "Head commit SHA").required()
    private val fold by option("--fold", help = "YAML fold rules, including optional scenarios")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true).required()
    private val cacheDirectory by option("--cache-dir", help = "Shared Git and analysis cache directory")
        .path(canBeFile = false).default(Path.of(System.getProperty("user.home"), ".cache", "graphfold"))
    private val format by option("--format", help = "Report format").choice("json", "text").default("text")

    @Suppress("ThrowsCount") // Clikt represents process exit codes as ProgramResult.
    override fun run() {
        val result = try {
            val request = AnalysisRequest(repository, base, head, Files.readString(fold))
            analyze(EngineConfiguration(cacheDirectory), request)
        } catch (error: IllegalArgumentException) {
            echo(error.message, err = true)
            throw ProgramResult(INPUT_FAILURE)
        } catch (error: IOException) {
            echo(error.message, err = true)
            throw ProgramResult(INPUT_FAILURE)
        }
        echo(if (format == "json") AnalysisReports.json(result) else AnalysisReports.text(result))
        val exitCode = when {
            result.diagnostics.any { it.blocking && it.stage in operationalStages } -> INPUT_FAILURE
            result.verdict == Verdict.FAIL -> VIOLATION
            else -> SUCCESS
        }
        if (exitCode != SUCCESS) throw ProgramResult(exitCode)
    }

    private companion object {
        const val SUCCESS = 0
        const val VIOLATION = 1
        const val INPUT_FAILURE = 2
        val operationalStages = setOf(AnalysisStage.INPUT, AnalysisStage.GIT, AnalysisStage.BUILD)
    }
}
