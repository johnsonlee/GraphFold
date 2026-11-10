package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildAvailabilityTest {
    @TempDir lateinit var directory: Path

    @Test fun `missing bundled init script stops before running build and removes its temporary file`() {
        val checkout = Files.createDirectories(directory.resolve("checkout"))
        val output = Files.createDirectories(directory.resolve("output"))
        Files.writeString(checkout.resolve("gradlew"), "touch executed\n")
        val adapter = GradleBuildAdapter(Duration.ofSeconds(1), initScript = { null })
        val error = assertFailsWith<IllegalStateException> { adapter.compile(checkout, output) }
        assertEquals("Missing bundled graphfold.gradle init script", error.message)
        assertFalse(Files.exists(checkout.resolve("executed")))
        Files.list(output).use { assertTrue(it.findAny().isEmpty) }
    }

    @Test fun `default Maven adapter rejects an absent root POM before executing a wrapper`() {
        val checkout = Files.createDirectories(directory.resolve("checkout"))
        val output = Files.createDirectories(directory.resolve("output"))
        Files.writeString(checkout.resolve("mvnw"), "touch executed\n")
        val adapter = MavenBuildAdapter(Duration.ofSeconds(1))
        val error = assertFailsWith<BuildFailure> { adapter.compile(checkout, output) }
        assertEquals("Maven reactor module must have a POM inside the checkout: ${checkout.resolve("pom.xml")}", error.message)
        assertFalse(Files.exists(checkout.resolve("executed")))
        Files.list(output).use { assertTrue(it.findAny().isEmpty) }
    }

    @Test fun `runtime without javac gives a build failure before creating analysis outputs`() {
        val source = Files.writeString(directory.resolve("Example.java"), "class Example {}")
        val classes = directory.resolve("classes")
        val origins = directory.resolve("origins")
        val error = assertFailsWith<BuildFailure> {
            compileJava(listOf(source), classes, emptyList(), origins, directory, "fixture", systemCompiler = null)
        }
        assertEquals("A JDK with javac is required", error.message)
        assertFalse(Files.exists(classes))
        assertFalse(Files.exists(origins))
    }
}
