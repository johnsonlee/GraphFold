package io.johnsonlee.graphfold.gradle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import org.eclipse.jgit.api.Git
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleProjectApiIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `WAR export builds project API jars as dependencies without making them application inputs`() {
        val root = Files.createDirectory(directory.resolve("repository"))
        Files.writeString(root.resolve("settings.gradle"),
            "rootProject.name='api-consumer'; include 'contracts', 'provided', 'business', 'web'")
        Files.writeString(root.resolve(".gitignore"), ".gradle/\nbuild/\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'io.johnsonlee.graphfold' }
            subprojects { apply plugin: 'java-library' }
            project(':web') {
                apply plugin: 'war'
                dependencies {
                    compileOnly project(':contracts')
                    providedCompile project(':provided')
                    implementation project(':business')
                }
            }
        """.trimIndent())
        listOf("contracts", "provided", "business", "web").forEach { name ->
            val sources = Files.createDirectories(root.resolve("$name/src/main/java/$name"))
            Files.writeString(sources.resolve("Api.java"), "package $name; public interface Api {}")
        }
        Git.init().setDirectory(root.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Native API model").setAuthor("GraphFold Test", "test@example.invalid")
                .setCommitter("GraphFold Test", "test@example.invalid").call()
        }
        val snapshot = directory.resolve("snapshot")
        val result = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("graphFoldInputs", "-Pgraphfold.inputsDirectory=$snapshot", "--stacktrace", "--max-workers=1").build()
        listOf("contracts", "provided", "business").forEach {
            assertEquals(TaskOutcome.SUCCESS, result.task(":$it:jar")?.outcome)
        }
        val scope = jacksonObjectMapper().readTree(snapshot.resolve("inputs.json").toFile())["scopes"].single()
        val dependencies = scope["dependencies"].map { Path.of(it.asText()) }
        val definitions = dependencies.flatMap { archive ->
            JarFile(archive.toFile()).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") }.map { it.name }.toList()
            }
        }.toSet()
        assertEquals(setOf("contracts/Api.class", "provided/Api.class"), definitions)
        assertEquals(2, scope["projectArtifacts"].size())
        val roots = scope["sourceRoots"].map { it.asText() }
        assertTrue(roots.none { it.contains("/contracts/") || it.contains("/provided/") })
        JarFile(Path.of(scope["archive"].asText()).toFile()).use { war ->
            val libraries = war.entries().asSequence().filter { it.name.startsWith("WEB-INF/lib/") && !it.isDirectory }.toList()
            assertEquals(listOf("WEB-INF/lib/business.jar"), libraries.map { it.name })
        }
    }
}
