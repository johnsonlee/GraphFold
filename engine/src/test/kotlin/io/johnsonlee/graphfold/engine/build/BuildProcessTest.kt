package io.johnsonlee.graphfold.engine.build

import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildProcessTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `failure embeds bounded output which survives log deletion`() {
        val log = temporary.resolve("failure.log")
        val failure = assertFailsWith<BuildFailure> {
            runBuild(temporary, log, listOf("sh", "-c", "echo compiler-diagnostic; exit 1"), Duration.ofSeconds(5))
        }
        Files.delete(log)
        assertTrue("compiler-diagnostic" in requireNotNull(failure.message))
        Files.writeString(log, (1..100).joinToString("\n") { "line-$it" })
        val message = buildFailureMessage("failed", log)
        assertFalse("line-50\n" in message)
        assertTrue("line-51\n" in message)
        assertTrue(message.endsWith("line-100"))
        Files.writeString(log, "a".repeat(50_000))
        assertTrue(buildFailureMessage("failed", log).length < 17_000)
        Files.delete(log)
        assertTrue("could not read" in buildFailureMessage("failed", log))
    }

    @Test
    fun `external build environment is opt in`() {
        val inherited = System.getenv().keys.first { it !in DEFAULT_BUILD_ENVIRONMENT && it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }
        val script = "if printenv '$inherited' >/dev/null; then echo present; else echo absent; fi"
        val hidden = temporary.resolve("hidden.log")
        runBuild(temporary, hidden, listOf("sh", "-c", script), Duration.ofSeconds(5))
        assertEquals("absent", Files.readString(hidden).trim())
        val allowed = temporary.resolve("allowed.log")
        runBuild(temporary, allowed, listOf("sh", "-c", script), Duration.ofSeconds(5), DEFAULT_BUILD_ENVIRONMENT + inherited)
        assertEquals("present", Files.readString(allowed).trim())
        assertFalse("GITHUB_TOKEN" in DEFAULT_BUILD_ENVIRONMENT)
        assertFalse("AWS_SECRET_ACCESS_KEY" in DEFAULT_BUILD_ENVIRONMENT)
    }
}
