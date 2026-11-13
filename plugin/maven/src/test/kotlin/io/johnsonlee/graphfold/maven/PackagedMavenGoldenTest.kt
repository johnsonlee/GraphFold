package io.johnsonlee.graphfold.maven

import io.johnsonlee.graphfold.engine.RepositoryConfiguration
import io.johnsonlee.graphfold.engine.Verdict
import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
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
import org.apache.maven.project.MavenProject
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagedMavenGoldenTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["boot", "war"])
    fun `packaged nested library keeps source identity and paired static control verdicts`(layout: String) {
        val root = Files.createDirectories(temporary.resolve("repository"))
        Files.writeString(root.resolve("pom.xml"), "<project/>")
        Files.writeString(root.resolve(".gitignore"), "**/target/\n")
        val library = model(root, "library", "jar")
        val application = model(root, "application", if (layout == "war") "war" else "jar")
        application.artifacts = setOf(library.artifact)
        val aggregator = MavenProject(Model().apply { packaging = "pom" }).apply { file = root.resolve("pom.xml").toFile() }
        val session = mock(MavenSession::class.java)
        `when`(session.systemProperties).thenReturn(Properties().apply { setProperty("java.version", System.getProperty("java.version")) })
        `when`(session.userProperties).thenReturn(Properties())
        `when`(session.topLevelProject).thenReturn(aggregator)
        `when`(session.projects).thenReturn(listOf(aggregator, library, application))
        val snapshots = listOf(1, 2).map { value ->
            compile(library, "Logic", "public static int value() { return enabled() ? $value : 0; } " +
                "public static boolean enabled() { return true; }")
            archive(library.artifact.file.toPath(), mapOf("sample/Logic.class" to
                Path.of(library.build.outputDirectory).resolve("sample/Logic.class")))
            compile(application, "App", "public int value() { return Logic.value(); }", library.artifact.file.toPath())
            val prefix = if (layout == "war") "WEB-INF" else "BOOT-INF"
            archive(application.artifact.file.toPath(), mapOf(
                "$prefix/classes/sample/App.class" to Path.of(application.build.outputDirectory).resolve("sample/App.class"),
                "$prefix/lib/library.jar" to library.artifact.file.toPath()))
            if (value == 1) Git.init().setDirectory(root.toFile()).call().close()
            commit(root)
            val snapshot = temporary.resolve("snapshot-$value/snapshot.json")
            val exporter = GraphFoldSnapshotMojo().apply {
                project = application
                this.session = session
                toolchainManager = mock(ToolchainManager::class.java)
                snapshotDirectory = snapshot.parent.toFile()
            }
            exporter.execute()
            exporter.snapshotManifest()
        }
        for (enabled in listOf(false, true)) {
            val fold = """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_class: "sample.Logic", callee_name: "enabled"}) RETURN cs
                    value: $enabled
            """.trimIndent()
            val result = StaticInputSnapshots.analyze(root.toString(), snapshots[0], snapshots[1], fold,
                RepositoryConfiguration(temporary.resolve("cache-$enabled")))
            assertEquals(if (enabled) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
            assertTrue(result.diagnostics.isEmpty(), result.toString())
            assertTrue(result.ruleAudits.isNotEmpty() && result.ruleAudits.all { (it.selectedCount ?: 0) > 0 }, result.toString())
            assertEquals(enabled, result.findings.isNotEmpty(), result.toString())
            if (enabled) assertTrue(result.findings.flatMap { it.locations }.all {
                it.path == "library/src/main/java/sample/Logic.java"
            }, result.toString())
        }
    }

    private fun model(root: Path, name: String, packaging: String): MavenProject {
        val directory = Files.createDirectories(root.resolve(name))
        Files.writeString(directory.resolve("pom.xml"), "<project/>")
        val output = Files.createDirectories(directory.resolve("target/classes"))
        return MavenProject(Model().apply {
            groupId = "fixture"
            artifactId = name
            version = "1"
            this.packaging = packaging
            build = Build().apply { outputDirectory = output.toString() }
        }).apply {
            file = directory.resolve("pom.xml").toFile()
            addCompileSourceRoot(Files.createDirectories(directory.resolve("src/main/java")).toString())
            artifact = DefaultArtifact("fixture", name, "1", "compile", packaging, null, DefaultArtifactHandler(packaging))
                .apply { file = directory.resolve("target/$name.$packaging").toFile() }
        }
    }

    private fun compile(project: MavenProject, owner: String, body: String, dependency: Path? = null) {
        val source = Path.of(project.compileSourceRoots.single()).resolve("sample/$owner.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "package sample; public class $owner { $body }")
        val options = listOf("-g", "-d", project.build.outputDirectory) +
            if (dependency == null) emptyList() else listOf("-classpath", dependency.toString())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *(options + source.toString()).toTypedArray()))
    }

    private fun archive(path: Path, entries: Map<String, Path>) = JarOutputStream(Files.newOutputStream(path)).use { jar ->
        entries.forEach { (name, source) ->
            jar.putNextEntry(JarEntry(name))
            Files.copy(source, jar)
            jar.closeEntry()
        }
    }

    private fun commit(repository: Path) = Git.open(repository.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("packaged fixture").setAuthor("GraphFold", "test@example.invalid")
            .setCommitter("GraphFold", "test@example.invalid").call()
    }
}
