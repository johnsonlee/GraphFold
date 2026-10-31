package io.johnsonlee.graphfold.engine.build

import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

internal fun runBuild(
    checkout: Path, log: Path, command: List<String>, timeout: Duration,
    environmentAllowlist: Set<String> = DEFAULT_BUILD_ENVIRONMENT,
) {
    val builder = ProcessBuilder(command).directory(checkout.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
    builder.environment().keys.retainAll(environmentAllowlist)
    val process = builder.start()
    process.outputStream.close()
    try {
        val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!completed) terminateBuild(process)
        if (!completed || process.exitValue() != 0) {
            val reason = if (completed) "Build failed" else "Build exceeded $timeout"
            throw BuildFailure(buildFailureMessage(reason, log))
        }
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } finally {
        terminateBuild(process)
    }
}

/** Bound both line count and message size; failures remain useful after temporary logs are removed. */
internal fun buildFailureMessage(reason: String, log: Path): String {
    return try {
        "$reason; build output (tail):\n${logTail(log)}"
    } catch (failure: java.io.IOException) {
        "$reason; could not read build output: ${failure.message}"
    }
}

private fun logTail(log: Path): String {
    val tail = ArrayDeque<String>()
    Files.newBufferedReader(log).use { reader ->
        reader.lineSequence().forEach { line ->
            if (tail.size == LOG_TAIL_LINES) tail.removeFirst()
            tail.addLast(line.takeLast(LOG_TAIL_CHARACTERS))
        }
    }
    return tail.joinToString("\n").takeLast(LOG_TAIL_CHARACTERS)
}

private const val LOG_TAIL_LINES = 50
private const val LOG_TAIL_CHARACTERS = 16_384

private fun terminateBuild(process: Process) {
    if (process.isAlive) {
        process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
}
