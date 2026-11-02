package io.johnsonlee.graphfold.cli

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionOptionTest {
    @Test
    fun `packaged version matches publication version`() {
        val expected = requireNotNull(System.getProperty("graphfold.expectedVersion"))
        assertEquals(expected, releaseVersion())
        assertTrue(releaseVersion().isNotBlank())
        assertFalse(releaseVersion().contains('$'))
    }

    @Test
    fun `version prints release version without required analysis options`() {
        val command = GraphFoldCommand { _, _ -> error("Version must not analyze") }
        val output = command.test("--version")
        assertEquals(0, output.statusCode, output.output)
        assertEquals("graphfold version ${releaseVersion()}", output.stdout.trim())
        assertTrue(output.stderr.isEmpty())
        assertEquals(0, executeCli(arrayOf("--version"), GraphFoldCommand { _, _ -> error("Version must not analyze") }))
    }

    @Test
    fun `help advertises version option`() {
        val output = GraphFoldCommand { _, _ -> error("Help must not analyze") }.test("--help")
        assertEquals(0, output.statusCode)
        assertTrue(output.stdout.contains("--version"))
    }
}
