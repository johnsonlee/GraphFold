package io.johnsonlee.graphfold.cli

import io.johnsonlee.graphfold.engine.testing.createFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryPointTest {
    @TempDir lateinit var directory: Path

    @Test fun `successful entry point returns normally and failure requests its precise process exit code`() {
        main(arrayOf("--version"))
        runMain(arrayOf("--version")) { error("Success must return normally") }
        val exits = mutableListOf<Int>()
        runMain(arrayOf("--unknown-option"), exits::add)
        assertEquals(listOf(2), exits)
    }

    @Test fun `real CLI processes terminate after controlled analysis and invalid input`() {
        val fixture = createFixture(directory, "gated_return")
        val analysis = listOf("--repository", fixture.repository.toString(), "--base", fixture.base, "--head", fixture.head,
            "--fold", fixture.fold.toString(), "--cache-dir", directory.resolve("process-cache").toString())
        for ((args, status) in listOf(analysis to 0, listOf("--unknown-option") to 2)) {
            val output = directory.resolve("process-$status.log")
            val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                requireNotNull(System.getProperty("graphfold.test.runtimeClasspath")), "io.johnsonlee.graphfold.cli.MainKt") + args
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI left a live process after completing its request")
                assertEquals(status, process.exitValue(), Files.readString(output))
            } finally {
                process.destroyForcibly()
            }
        }
    }
}
