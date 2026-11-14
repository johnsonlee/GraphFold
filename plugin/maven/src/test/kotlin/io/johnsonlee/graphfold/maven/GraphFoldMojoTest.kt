package io.johnsonlee.graphfold.maven

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.DefaultArtifactHandler
import org.apache.maven.execution.MavenSession
import org.apache.maven.toolchain.ToolchainManager
import java.util.Properties
import org.apache.maven.model.Build
import org.apache.maven.model.Model
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.project.MavenProject
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphFoldMojoTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `goals prove selected control coverage from current model snapshots`(controlled: Boolean) {
        val repository = Files.createDirectories(temporary.resolve("repository"))
        val sourceRoot = Files.createDirectories(repository.resolve("src/main/java"))
        val output = Files.createDirectories(temporary.resolve("classes"))
        val archive = temporary.resolve("library.jar")
        val source = sourceRoot.resolve("Library.java")
        Files.writeString(repository.resolve("pom.xml"), "<project/>")
        val project = MavenProject(Model().apply {
            groupId = "fixture"
            artifactId = "library"
            version = "1"
            build = Build().apply { outputDirectory = output.toString() }
        }).apply {
            file = repository.resolve("pom.xml").toFile()
            addCompileSourceRoot(sourceRoot.toString())
            artifact = DefaultArtifact("fixture", "library", "1", "compile", "jar", null, DefaultArtifactHandler("jar"))
                .apply { file = archive.toFile() }
        }
        val session = mock(MavenSession::class.java)
        `when`(session.systemProperties).thenReturn(Properties().apply { setProperty("java.version", System.getProperty("java.version")) })
        `when`(session.userProperties).thenReturn(Properties())
        `when`(session.topLevelProject).thenReturn(project)
        `when`(session.projects).thenReturn(listOf(project))
        compile(source, output, archive, 1, controlled)
        Git.init().setDirectory(repository.toFile()).call().close()
        commit(repository)
        val baseline = GraphFoldSnapshotMojo().apply {
            this.project = project
            this.session = session
            toolchainManager = mock(ToolchainManager::class.java)
            snapshotDirectory = temporary.resolve("base").toFile()
        }
        baseline.execute()
        assertTrue(Files.isRegularFile(temporary.resolve("base/snapshot.json")))
        compile(source, output, archive, 2, controlled)
        commit(repository)
        val rules = Files.writeString(temporary.resolve("rules.yml"), """
            version: 1
            folds:
              - select: |
                  MATCH (cs:CallSite {callee_class: "Library", callee_name: "enabled"}) RETURN cs
                value: false
        """.trimIndent())
        val check = GraphFoldCheckMojo().apply {
            this.project = project
            this.session = session
            toolchainManager = mock(ToolchainManager::class.java)
            snapshotDirectory = temporary.resolve("head").toFile()
            baseSnapshot = temporary.resolve(if (controlled) "base" else "base/snapshot.json").toFile()
            this.rules = rules.toFile()
            report = temporary.resolve("reports/report.json").toFile()
        }
        if (controlled) check.execute() else assertFailsWith<MojoFailureException> { check.execute() }
        val report = jacksonObjectMapper().readTree(check.report)
        assertEquals(if (controlled) "PASS" else "FAIL", report["verdict"].asText(), report.toString())
        assertEquals(0, report["diagnostics"].size(), report.toString())
        assertTrue(report["ruleAudits"].all { it["selectedCount"].asInt() > 0 }, report.toString())
        assertEquals(controlled, report["findings"].isEmpty, report.toString())
    }

    @Test fun `aggregator POM contributes no invented bytecode input`() {
        val aggregator = MavenProject(Model().apply { packaging = "pom" })
        GraphFoldSnapshotMojo().apply { project = aggregator }.execute()
        GraphFoldCheckMojo().apply { project = aggregator }.execute()
    }

    private fun compile(source: Path, output: Path, archive: Path, value: Int, controlled: Boolean) {
        Files.writeString(source, """
            public class Library {
                public int value() { return enabled() ? ${if (controlled) value else 0} : ${if (controlled) 0 else value}; }
                public static boolean enabled() { return true; }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", output.toString(), source.toString()))
        JarOutputStream(Files.newOutputStream(archive)).use { jar ->
            jar.putNextEntry(JarEntry("Library.class"))
            Files.copy(output.resolve("Library.class"), jar)
            jar.closeEntry()
        }
    }

    private fun commit(repository: Path) = Git.open(repository.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("fixture").setAuthor("GraphFold", "test@example.invalid")
            .setCommitter("GraphFold", "test@example.invalid").call()
    }
}
