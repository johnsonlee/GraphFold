package io.johnsonlee.graphfold.maven

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.DefaultArtifactHandler
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Build
import org.apache.maven.model.Model
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.project.MavenProject
import org.apache.maven.toolchain.ToolchainManager
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MavenReactorMojoTest {
    @TempDir lateinit var temporary: Path

    @Test fun `reactor goals pair each module and retain both reports under shared configured roots`() {
        val repository = Files.createDirectory(temporary.resolve("repository"))
        Files.writeString(repository.resolve(".gitignore"), "**/target/\n")
        val root = project(repository, "root", "pom")
        val safe = project(repository.resolve("safe"), "safe", "jar")
        val unsafe = project(repository.resolve("unsafe"), "unsafe", "jar")
        val projects = listOf(safe, unsafe)
        val session = session(root, listOf(root) + projects)
        compile(safe, 1, 10)
        compile(unsafe, 1, 20)
        Git.init().setDirectory(repository.toFile()).call().close()
        commit(repository)
        val base = temporary.resolve("base")
        projects.forEach { module -> exporter(module, session, base).execute() }
        val safeBase = base.resolve("modules/fixture%3Asafe/snapshot.json")
        val unsafeBase = base.resolve("modules/fixture%3Aunsafe/snapshot.json")
        assertEquals(listOf("fixture:safe"), StaticInputSnapshots.scopeIds(safeBase))
        assertEquals(listOf("fixture:unsafe"), StaticInputSnapshots.scopeIds(unsafeBase))
        assertFailsWith<IllegalArgumentException> { safe.baseSnapshot(unsafeBase) }
        assertEquals(safeBase, safe.baseSnapshot(safeBase))
        safe.version = "2"
        assertEquals(safeBase, safe.moduleSnapshot(base))
        safe.version = "1"
        compile(safe, 2, 10)
        compile(unsafe, 1, 21)
        commit(repository)
        val rules = Files.writeString(temporary.resolve("rules.yml"), RULES)
        val sharedReport = temporary.resolve("reports/report.json")
        val head = temporary.resolve("head")
        // A later PASS must not overwrite the preceding module's FAIL report, as in Maven --fail-at-end.
        for (module in listOf(unsafe, safe)) {
            val check = GraphFoldCheckMojo().apply {
                project = module
                this.session = session
                toolchainManager = mock(ToolchainManager::class.java)
                snapshotDirectory = head.toFile()
                baseSnapshot = base.toFile()
                this.rules = rules.toFile()
                report = sharedReport.toFile()
            }
            if (module == unsafe) assertFailsWith<MojoFailureException> { check.execute() } else check.execute()
        }
        assertTrue(Files.notExists(sharedReport))
        projects.forEach { module ->
            val key = "fixture%3A${module.artifactId}"
            val result = jacksonObjectMapper().readTree(sharedReport.parent.resolve("modules/$key/report.json").toFile())
            assertEquals(if (module == safe) "PASS" else "FAIL", result["verdict"].asText(), result.toString())
            assertEquals(0, result["diagnostics"].size(), result.toString())
            assertEquals(2, result["ruleAudits"].size(), result.toString())
            assertTrue(result["ruleAudits"].all { it["selectedCount"].asInt() > 0 }, result.toString())
            assertEquals(module == safe, result["findings"].isEmpty, result.toString())
            if (module == unsafe) {
                val locations = result["findings"].flatMap { it["locations"].toList() }
                assertTrue(locations.isNotEmpty(), result.toString())
                assertTrue(locations.all { it["path"].asText() == "unsafe/src/main/java/Logic.java" }, result.toString())
            }
            assertEquals(listOf("fixture:${module.artifactId}"),
                StaticInputSnapshots.scopeIds(head.resolve("modules/$key/snapshot.json")))
        }
    }

    private fun exporter(module: MavenProject, context: MavenSession, output: Path) = GraphFoldSnapshotMojo().apply {
        project = module
        session = context
        toolchainManager = mock(ToolchainManager::class.java)
        snapshotDirectory = output.toFile()
    }

    private fun project(directory: Path, name: String, type: String): MavenProject {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("pom.xml"), "<project/>")
        val output = Files.createDirectories(directory.resolve("target/classes"))
        return MavenProject(Model().apply {
            groupId = "fixture"
            artifactId = name
            version = "1"
            packaging = type
            build = Build().apply { outputDirectory = output.toString() }
        }).apply {
            file = directory.resolve("pom.xml").toFile()
            addCompileSourceRoot(Files.createDirectories(directory.resolve("src/main/java")).toString())
            artifact = DefaultArtifact("fixture", name, "1", "compile", type, null, DefaultArtifactHandler(type))
                .apply { file = directory.resolve("target/$name.$type").toFile() }
        }
    }

    private fun session(root: MavenProject, modules: List<MavenProject>): MavenSession = mock(MavenSession::class.java).also {
        `when`(it.topLevelProject).thenReturn(root)
        `when`(it.projects).thenReturn(modules)
        `when`(it.userProperties).thenReturn(Properties())
        `when`(it.systemProperties).thenReturn(Properties().apply { setProperty("java.version", System.getProperty("java.version")) })
    }

    private fun compile(project: MavenProject, controlled: Int, fallback: Int) {
        val source = Path.of(project.compileSourceRoots.single()).resolve("Logic.java")
        Files.writeString(source, """
            public class Logic {
                public static boolean enabled() { return true; }
                public int value() { return enabled() ? $controlled : $fallback; }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", project.build.outputDirectory, source.toString()))
        JarOutputStream(Files.newOutputStream(project.artifact.file.toPath())).use { archive ->
            archive.putNextEntry(JarEntry("Logic.class"))
            Files.copy(Path.of(project.build.outputDirectory).resolve("Logic.class"), archive)
            archive.closeEntry()
        }
    }

    private fun commit(repository: Path) = Git.open(repository.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("reactor fixture").setAuthor("GraphFold", "test@example.invalid")
            .setCommitter("GraphFold", "test@example.invalid").call()
    }

    private companion object {
        const val RULES = "version: 1\nfolds:\n  - match: {CallSite: {callee_class: Logic, callee_name: enabled}}\n    value: false\n"
    }
}
