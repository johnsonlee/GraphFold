package io.johnsonlee.graphfold.maven

import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.DefaultArtifactHandler
import org.apache.maven.model.Build
import org.apache.maven.model.Model
import org.apache.maven.project.MavenProject
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MavenDependencyOrderTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @CsvSource("provided,compile", "provided,runtime", "system,compile", "system,runtime")
    fun `runtime definition wins while supplemental class changes remain inventoried`(supplementalScope: String, runtimeScope: String) {
        val repository = Files.createDirectories(temporary.resolve("repository"))
        Files.writeString(repository.resolve("pom.xml"), "<project/>")
        val primary = artifact("application", Artifact.SCOPE_COMPILE, "App", 0)
        val supplemental = artifact("supplemental", supplementalScope, "Value", 7)
        val runtime = artifact("runtime", runtimeScope, "Value", 9)
        val excluded = listOf("tar.gz", "jar").map { type ->
            DefaultArtifact("fixture", "native-$type", "1", Artifact.SCOPE_COMPILE, type, null,
                DefaultArtifactHandler(type).apply { isAddedToClasspath = false }).apply {
                file = Files.writeString(temporary.resolve("native.$type"), "not a classpath archive").toFile()
            }
        }
        val project = MavenProject(Model().apply {
            groupId = "fixture"
            artifactId = "application"
            version = "1"
            build = Build().apply { outputDirectory = temporary.resolve("application/classes").toString() }
        }).apply {
            file = repository.resolve("pom.xml").toFile()
            artifact = primary
            artifacts = (excluded + listOf(supplemental, runtime)).toSet()
        }
        Git.init().setDirectory(repository.toFile()).call().close()
        val baseSha = commit(repository)
        val base = StaticInputSnapshots.write(repository, temporary.resolve("base/snapshot.json"), listOf(project.staticInput(17)))
        artifact("supplemental", supplementalScope, "Value", 8)
        Files.writeString(repository.resolve("pom.xml"), "<project><!-- changed supplemental input --></project>")
        commit(repository)
        val input = project.staticInput(17)
        assertEquals(listOf(runtime.file.toPath().toRealPath(), supplemental.file.toPath().toRealPath()), input.dependencies)
        val head = StaticInputSnapshots.write(repository, temporary.resolve("head/snapshot.json"), listOf(input))
        val baseCheckout = temporary.resolve("base-checkout")
        Git.cloneRepository().setURI(repository.toUri().toString()).setDirectory(baseCheckout.toFile()).call().use {
            it.checkout().setName(baseSha).call()
        }
        val (old, new) = StaticInputSnapshots.prepare(base, head, baseCheckout, repository, temporary.resolve("prepared"))
        val oldScope = old.scopes.getValue(input.id)
        val newScope = new.scopes.getValue(input.id)
        val expected = assertNotNull(entry(runtime.file.toPath(), "sample/Value.class"))
        for (scope in listOf(oldScope, newScope)) {
            val definitions = scope.dependencies.mapNotNull { entry(it, "sample/Value.class") }
            assertEquals(1, definitions.size)
            assertContentEquals(expected, definitions.single())
            assertTrue(scope.dependencies.any { entry(it, "supplemental.txt") != null })
        }
        excluded.forEach { assertEquals("not a classpath archive", Files.readString(it.file.toPath())) }
        val oldEvidence = assertNotNull(oldScope.production).nonClassEntries
        val newEvidence = assertNotNull(newScope.production).nonClassEntries
        assertNotEquals(assertNotNull(oldEvidence["dependency/2"]), assertNotNull(newEvidence["dependency/2"]),
            "The changed supplemental class must remain evidence even though the graph selects the runtime class")
    }

    private fun artifact(name: String, scope: String, owner: String, value: Int): Artifact {
        val directory = Files.createDirectories(temporary.resolve(name))
        val classes = Files.createDirectories(directory.resolve("classes"))
        val source = Files.writeString(directory.resolve("$owner.java"),
            "package sample; public class $owner { public static int value() { return $value; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "17", "-g:none", "-d", classes.toString(), source.toString()))
        val archive = directory.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(archive)).use { jar ->
            jar.putNextEntry(JarEntry("sample/$owner.class"))
            Files.copy(classes.resolve("sample/$owner.class"), jar)
            jar.closeEntry()
            jar.putNextEntry(JarEntry("$name.txt"))
            jar.write(name.toByteArray())
            jar.closeEntry()
        }
        return DefaultArtifact("fixture", name, "1", scope, "custom-classes", null,
            DefaultArtifactHandler("custom-classes").apply { isAddedToClasspath = true })
            .apply { file = archive.toFile() }
    }

    private fun entry(archive: Path, name: String): ByteArray? = JarFile(archive.toFile()).use { jar ->
        jar.getJarEntry(name)?.let { jar.getInputStream(it).use { input -> input.readBytes() } }
    }

    private fun commit(repository: Path): String = Git.open(repository.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("model input fixture").setAuthor("GraphFold", "test@example.invalid")
            .setCommitter("GraphFold", "test@example.invalid").call().name
    }
}
