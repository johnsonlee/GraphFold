package io.johnsonlee.graphfold.engine.git

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitNoExecutableTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `repository checkout diff and engine analysis work with an empty executable search path`() {
        val classpath = requireNotNull(System.getProperty("graphfold.test.runtimeClasspath")) {
            "Gradle must pass the test runtime classpath to the child JVM"
        }
        val log = temporary.resolve("child.log")
        val noExecutables = Files.createDirectory(temporary.resolve("empty-path"))
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, GitWithoutExecutableProbe::class.java.name, temporary.toString(),
            Path.of("../fixtures").toAbsolutePath().normalize().toString(),
        ).apply {
            environment()["PATH"] = noExecutables.toString()
            redirectErrorStream(true)
            redirectOutput(log.toFile())
        }.start()
        try {
            assertTrue(process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "JGit subprocess timed out")
            assertEquals(0, process.exitValue(), Files.readString(log))
            assertTrue(Files.readString(log).contains("JGIT_WITHOUT_EXECUTABLE_OK"))
        } finally {
            process.destroyForcibly()
        }
    }

    private companion object {
        const val CHILD_TIMEOUT_SECONDS = 60L
    }
}

/** Runs in a JVM whose PATH contains no executables; even fixture construction uses JGit. */
object GitWithoutExecutableProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Path.of(arguments[0])
        val fixtures = Path.of(arguments[1])
        check(Files.list(Path.of(System.getenv("PATH"))).use { it.count() } == 0L)
        val source = root.resolve("source")
        val revisions = Git.init().setDirectory(source.toFile()).call().use { git ->
            disableFixtureHooks(git, root)
            fun commit(value: String): String {
                Files.writeString(source.resolve("Example.java"), value)
                git.add().addFilepattern(".").call()
                return git.commit().setNoVerify(true).setMessage("fixture")
                    .setAuthor("Fixture", "fixture@example.invalid")
                    .setCommitter("Fixture", "fixture@example.invalid").call().name
            }
            commit("before\n") to commit("after\n")
        }
        val workspace = GitWorkspaceManager(root.resolve("cache"))
            .open(source.toString(), revisions.first, revisions.second)
        workspace.use {
            check(Files.readString(it.baseDirectory.resolve("Example.java")) == "before\n")
            check(Files.readString(it.headDirectory.resolve("Example.java")) == "after\n")
            val change = it.changes().single()
            check(change.basePath == "Example.java" && change.headPath == "Example.java")
            check(change.baseLines == listOf(ChangedLineRange(1, 1)))
            check(change.headLines == listOf(ChangedLineRange(1, 1)))
        }
        check(Files.exists(workspace.baseDirectory) && Files.exists(workspace.headDirectory))
        analyzeJavaFixture(root, fixtures)
        println("JGIT_WITHOUT_EXECUTABLE_OK")
    }

    private fun disableFixtureHooks(git: Git, root: Path) {
        val hooks = Files.createDirectories(root.resolve("empty-fixture-hooks"))
        git.repository.config.apply {
            setString("core", null, "hooksPath", hooks.toString())
            save()
        }
    }

    private fun analyzeJavaFixture(root: Path, fixtures: Path) {
        val source = root.resolve("engine-source")
        val revisions = Git.init().setDirectory(source.toFile()).call().use { git ->
            disableFixtureHooks(git, root)
            val sources = Files.createDirectories(source.resolve("src/main/java/sample"))
            Files.copy(fixtures.resolve("common/Ab.java"), sources.resolve("Ab.java"))
            fun commit(revision: String): String {
                Files.copy(
                    fixtures.resolve("cases/arithmetic/$revision/Example.java"),
                    sources.resolve("Example.java"), StandardCopyOption.REPLACE_EXISTING,
                )
                git.add().addFilepattern(".").call()
                return git.commit().setNoVerify(true).setMessage(revision)
                    .setAuthor("Fixture", "fixture@example.invalid")
                    .setCommitter("Fixture", "fixture@example.invalid").call().name
            }
            commit("base") to commit("head")
        }
        val result = GraphFoldEngine(EngineConfiguration(root.resolve("engine-cache"))).analyze(
            AnalysisRequest(
                source.toString(), revisions.first, revisions.second, Files.readString(fixtures.resolve("fold.yml")),
            ),
        )
        check(result.verdict == Verdict.FAIL) { "Unexpected verdict: $result" }
        check(result.findings.isNotEmpty()) { "Arithmetic change must produce a finding" }
        check(result.diagnostics.isEmpty()) { "Analysis gaps cannot substitute for detection: ${result.diagnostics}" }
    }
}
