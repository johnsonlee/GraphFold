package io.johnsonlee.graphfold.gradle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.inputs.ArchiveScopeInput
import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

class GraphFoldWorkTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `worker uses snapshots writes reports and logs summaries without running a build`() {
        val parameters = parameters()
        val work = object : GraphFoldWork() { override fun getParameters() = parameters }
        val logger = mock(Logger::class.java)
        mockStatic(Logging::class.java, CALLS_REAL_METHODS).use { logging ->
            logging.`when`<Logger> { Logging.getLogger(GraphFoldWork::class.java) }.thenReturn(logger)
            work.execute()
            assertTrue(parameters.jsonReport.get().asFile.readText().contains("PASS"))
            `when`(logger.isInfoEnabled).thenReturn(true)
            work.execute()
        }
        val mapper = jacksonObjectMapper()
        val report = mapper.readTree(parameters.jsonReport.get().asFile)
        report["ruleAudits"].forEach { audit ->
            verify(logger).info("[graphfold.fold] ${mapper.writeValueAsString(audit)}")
        }
        parameters.fold.get().asFile.writeText("invalid: [")
        val failure = assertFailsWith<GradleException> { work.execute() }
        assertTrue(failure.message.orEmpty().contains(parameters.jsonReport.get().asFile.toString()))
        assertTrue(parameters.jsonReport.get().asFile.readText().contains("FAIL"))
        assertTrue(parameters.textReport.get().asFile.readText().isNotBlank())
    }

    private fun parameters(): GraphFoldParameters {
        val repository = Files.createDirectories(directory.resolve("repository"))
        val source = repository.resolve("Example.java")
        source.writeText("public class Example { public int value() { return 1; } }")
        git(repository, "init", "--quiet")
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "base")
        val classes = Files.createDirectories(directory.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        val archive = directory.resolve("example.jar")
        JarOutputStream(Files.newOutputStream(archive)).use { jar ->
            jar.putNextEntry(JarEntry("Example.class"))
            jar.write(Files.readAllBytes(classes.resolve("Example.class")))
            jar.closeEntry()
        }
        val scope = ArchiveScopeInput("example", archive, javaVersion = Runtime.version().feature())
        val base = StaticInputSnapshots.write(repository, directory.resolve("base/inputs.json"), listOf(scope))
        val head = StaticInputSnapshots.write(repository, directory.resolve("head/inputs.json"), listOf(scope))
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val parameters = project.objects.newInstance(GraphFoldParameters::class.java)
        parameters.repository.set(repository.toString())
        parameters.baseInputs.set(base.toFile())
        parameters.headInputs.set(head.toFile())
        parameters.fold.set(directory.resolve("fold.yml").toFile())
        parameters.fold.get().asFile.writeText("folds:\n  - select: 'MATCH (cs:CallSite) RETURN cs'\n    value: false\n")
        parameters.cacheDirectory.set(directory.resolve("cache").toFile())
        parameters.jsonReport.set(directory.resolve("report/result.json").toFile())
        parameters.textReport.set(directory.resolve("report/result.txt").toFile())
        return parameters
    }

    private fun git(repository: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }
}
