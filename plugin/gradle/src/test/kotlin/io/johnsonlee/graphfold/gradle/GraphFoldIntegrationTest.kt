package io.johnsonlee.graphfold.gradle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class GraphFoldIntegrationTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @CsvSource("library,true", "library,false", "boot,true", "boot,false", "war,true", "war,false")
    fun `each revision exports its own model and static snapshots determine control coverage`(packaging: String, controlled: Boolean) {
        val repository = Files.createDirectories(directory.resolve("repository"))
        repository.resolve("settings.gradle").writeText("rootProject.name = 'consumer'\n")
        val packagingPlugin = packagingPlugin(packaging)
        repository.resolve("gradlew").writeText("#!/bin/sh\necho 'Nested builds are forbidden' >&2\nexit 73\n")
        repository.resolve("gradlew").toFile().setExecutable(true)
        repository.resolve("build.gradle").writeText("""
            plugins { id 'java-library'; id 'io.johnsonlee.graphfold'; $packagingPlugin }
            repositories { mavenCentral() }
            tasks.named('graphFoldInputs') {
                outputDirectory.set(file(providers.gradleProperty('snapshot').get()))
            }
        """.trimIndent())
        repository.resolve(".gitignore").writeText("build/\n.gradle/\n")
        val sources = Files.createDirectories(repository.resolve("src/main/java/sample"))
        sources.resolve("Flag.java").writeText("package sample; public class Flag { public static boolean enabled() { return true; } }")
        val source = sources.resolve("Example.java")
        fun body(value: Int): String = if (controlled) "return Flag.enabled() ? $value : 0;" else "return $value;"
        source.writeText("package sample; public class Example { public static void main(String[] args) {} " +
            "public boolean selected() { return Flag.enabled(); } public int value() { ${body(1)} } }")
        git(repository, "init", "--quiet")
        git(repository, "config", "user.name", "GraphFold Test")
        git(repository, "config", "user.email", "test@example.invalid")
        git(repository, "add", ".")
        git(repository, "commit", "--quiet", "-m", "base")
        val base = git(repository, "rev-parse", "HEAD")
        val baseInputs = directory.resolve("base")
        val runner = GradleRunner.create().withProjectDir(repository.toFile()).withPluginClasspath()
        assertTrue(runner.pluginClasspath.none { it.name.startsWith("gradle-api-") })
        val exported = runner.withArguments("graphFoldInputs", "-Psnapshot=$baseInputs", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, exported.task(":graphFoldInputs")?.outcome)
        val archiveTask = when (packaging) { "boot" -> ":bootJar"; "war" -> ":war"; else -> ":jar" }
        assertEquals(TaskOutcome.SUCCESS, exported.task(archiveTask)?.outcome)
        val baseBytes = baseInputs.resolve("inputs.json").readText()
        source.writeText("package sample; public class Example { public static void main(String[] args) {} " +
            "public boolean selected() { return Flag.enabled(); } public int value() { ${body(2)} } }")
        // Positive cases also prove a fresh HEAD model; negative cases isolate the uncontrolled source edit.
        if (controlled) addSourceRoot(repository)
        git(repository, "add", ".")
        git(repository, "commit", "--quiet", "-m", "head")
        val head = git(repository, "rev-parse", "HEAD")
        val headInputs = directory.resolve("head")
        runner.withArguments("graphFoldInputs", "-Psnapshot=$headInputs", "--stacktrace").build()
        assertEquals(baseBytes, baseInputs.resolve("inputs.json").readText())
        val snapshot = jacksonObjectMapper().readTree(headInputs.resolve("inputs.json").toFile())
        val roots = snapshot["scopes"].flatMap { scope -> scope["sourceRoots"].map { it.asText() } }
        assertEquals(controlled, roots.any { Path.of(it).endsWith("generated/java") })
        val fold = directory.resolve("fold.yml")
        fold.writeText("""
            folds:
              - select: |
                  MATCH (cs:CallSite {callee_class: "sample.Flag", callee_name: "enabled"}) RETURN cs
                value: false
        """.trimIndent())
        val check = runner.withArguments(
            "graphFoldCheck", "-Psnapshot=$headInputs", "-Pgraphfold.baseInputs=$baseInputs/inputs.json",
            "-Pgraphfold.headInputs=$headInputs/inputs.json", "-Pgraphfold.fold=$fold", "--stacktrace", "--info",
        )
        val result = if (controlled) check.build() else check.buildAndFail()
        assertEquals(if (controlled) TaskOutcome.SUCCESS else TaskOutcome.FAILED, result.task(":graphFoldCheck")?.outcome)
        assertFalse(result.tasks.any { it.path in setOf(":jar", ":bootJar", ":war", ":compileJava", ":graphFoldInputs") })
        val report = jacksonObjectMapper().readTree(repository.resolve("build/reports/graphfold/report.json").toFile())
        assertEquals(if (controlled) "PASS" else "FAIL", report["verdict"].asText())
        assertEquals(base, report["metadata"]["baseSha"].asText())
        assertEquals(head, report["metadata"]["headSha"].asText())
        assertEquals(head, git(repository, "rev-parse", "HEAD"))
        assertTrue(report["diagnostics"].isEmpty, report.toString())
        assertTrue(report["ruleAudits"].all { audit ->
            audit["selectionStatus"].asText() == "SELECTED" && audit["matchedCallCount"].asInt() == if (controlled) 2 else 1
        }, report.toString())
        if (!controlled) assertEquals(setOf("src/main/java/sample/Example.java"),
            report["findings"].flatMap { it["locations"] }.map { it["path"].asText() }.toSet(), report.toString())
        assertTrue(repository.resolve("build/reports/graphfold/report.txt").readText().isNotBlank())
    }

    private fun packagingPlugin(packaging: String): String = when (packaging) {
        "boot" -> "id 'org.springframework.boot' version '3.5.7'"
        "war" -> "id 'war'"
        else -> ""
    }

    private fun addSourceRoot(repository: Path) {
        repository.resolve("build.gradle").toFile().appendText("\nsourceSets.main.java.srcDir('generated/java')\n")
        Files.createDirectories(repository.resolve("generated/java"))
        repository.resolve("generated/java/.keep").writeText("")
    }

    private fun git(repository: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output.trim()
    }
}
