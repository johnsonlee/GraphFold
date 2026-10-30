package io.johnsonlee.graphfold.engine.build

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

internal fun runBuild(checkout: Path, log: Path, command: List<String>, timeout: Duration) {
    val process = ProcessBuilder(command).directory(checkout.toFile()).redirectErrorStream(true)
        .redirectOutput(log.toFile()).start()
    process.outputStream.close()
    try {
        checkBuild(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS), "Build exceeded $timeout; output: $log")
        checkBuild(process.exitValue() == 0, "Build failed; output: $log")
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } finally {
        terminateBuild(process)
    }
}

private fun terminateBuild(process: Process) {
    if (process.isAlive) {
        process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
}
