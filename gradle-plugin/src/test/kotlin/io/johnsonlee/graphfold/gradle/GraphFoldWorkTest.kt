package io.johnsonlee.graphfold.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphFoldWorkTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `worker writes a pass report for identical revisions`() {
        val repository = directory.resolve("repository").toFile().apply { mkdirs() }
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repository.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return output.trim()
        }
        git("init", "--quiet")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--allow-empty", "-m", "initial")
        val revision = git("rev-parse", "HEAD")
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val parameters = project.objects.newInstance(GraphFoldParameters::class.java)
        val fold = directory.resolve("pass.yml").apply {
            writeText("folds:\n  - select: 'MATCH (cs:CallSite) RETURN cs'\n    value: false\n")
        }
        parameters.repository.set(repository.path)
        parameters.base.set(revision)
        parameters.head.set(revision)
        parameters.fold.set(fold.toFile())
        parameters.cacheDirectory.set(directory.resolve("cache").toFile())
        parameters.jsonReport.set(directory.resolve("reports/pass.json").toFile())
        parameters.textReport.set(directory.resolve("reports/pass.txt").toFile())
        val work = object : GraphFoldWork() {
            override fun getParameters() = parameters
        }
        work.execute()
        assertTrue(directory.resolve("reports/pass.json").readText().contains("PASS"))
    }

    @Test
    fun `worker writes a failure report before failing the gate`() {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val parameters = project.objects.newInstance(GraphFoldParameters::class.java)
        val fold = directory.resolve("fold.yml").apply {
            writeText("folds:\n  - select: 'MATCH (cs:CallSite) RETURN cs'\n    value: false\n")
        }
        parameters.repository.set(directory.resolve("missing-repository").toString())
        parameters.base.set("0123456789abcdef")
        parameters.head.set("fedcba9876543210")
        parameters.fold.set(fold.toFile())
        parameters.cacheDirectory.set(directory.resolve("cache").toFile())
        parameters.jsonReport.set(directory.resolve("reports/report.json").toFile())
        parameters.textReport.set(directory.resolve("reports/report.txt").toFile())
        val work = object : GraphFoldWork() {
            override fun getParameters() = parameters
        }
        assertFailsWith<GradleException> { work.execute() }
        assertTrue(directory.resolve("reports/report.json").readText().contains("FAIL"))
        assertTrue(directory.resolve("reports/report.txt").readText().contains("GraphFold: FAIL"))
    }
}
