package io.johnsonlee.graphfold.maven

import io.johnsonlee.graphfold.engine.inputs.DeclaredSourceRoot
import java.nio.file.Files
import java.nio.file.Path
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.DefaultArtifactHandler
import org.apache.maven.model.Model
import org.apache.maven.model.Build
import org.apache.maven.project.MavenProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MavenProjectInputTest {
    @TempDir lateinit var temporary: Path

    @Test fun `packaged input prioritizes runtime artifacts while compiler mappings retain model order`() {
        val project = project()
        val included = listOf(Artifact.SCOPE_PROVIDED, Artifact.SCOPE_RUNTIME, Artifact.SCOPE_SYSTEM, Artifact.SCOPE_COMPILE)
            .map { dependency(it, it) }
        project.artifacts = (included + dependency("tests", Artifact.SCOPE_TEST)).toSet()
        project.addCompileSourceRoot(temporary.resolve("generated").toString())
        project.addCompileSourceRoot(temporary.resolve("generated/../generated").toString())
        project.addTestCompileSourceRoot(temporary.resolve("tests").toString())
        val input = project.staticInput(17)
        assertEquals("fixture:library", input.id)
        assertEquals(17, input.javaVersion)
        assertEquals(project.artifact.file.toPath().toRealPath(), input.archive)
        assertEquals(listOf(included[1], included[3], included[0], included[2]).map { it.file.toPath().toRealPath() },
            input.dependencies)
        assertEquals(listOf(temporary.resolve("generated")), input.sourceRoots)
        assertEquals(mapOf(temporary.resolve("generated") to listOf(temporary.resolve("classes"))), input.sourceOutputs)
        assertEquals(listOf(
            DeclaredSourceRoot("fixture:library", "main", temporary.resolve("generated")),
            DeclaredSourceRoot("fixture:library", "main", temporary.resolve("generated")),
            DeclaredSourceRoot("fixture:library", "test", temporary.resolve("tests")),
        ), input.declaredSources)
        assertEquals(included.map { it.file.toPath() }, input.sourceClasspaths.getValue(temporary.resolve("generated")))
    }

    @Test fun `unpackaged and unresolved artifacts fail before engine analysis`() {
        val project = project()
        Files.delete(project.artifact.file.toPath())
        assertFailsWith<IllegalArgumentException> { project.staticInput(17) }
        project.artifact.file = null
        assertFailsWith<IllegalArgumentException> { project.staticInput(17) }
        project.artifact = null
        assertFailsWith<IllegalArgumentException> { project.staticInput(17) }
        val resolved = project()
        val dependency = dependency("missing", Artifact.SCOPE_RUNTIME)
        resolved.artifacts = setOf(dependency)
        Files.delete(dependency.file.toPath())
        assertFailsWith<IllegalArgumentException> { resolved.staticInput(17) }
        dependency.file = null
        assertFailsWith<IllegalArgumentException> { resolved.staticInput(17) }
    }

    @Test fun `only resolved reactor dependencies contribute project archive identities and source mappings`() {
        val project = project()
        val selected = project().apply {
            artifactId = "selected"
            artifact = dependency("selected", Artifact.SCOPE_COMPILE)
            build.outputDirectory = temporary.resolve("selected-classes").toString()
            addCompileSourceRoot(temporary.resolve("selected-source").toString())
        }
        val unrelated = project().apply {
            artifactId = "unrelated"
            artifact = dependency("unrelated", Artifact.SCOPE_COMPILE)
        }
        val aggregator = project().apply { artifact = null }
        project.artifacts = setOf(selected.artifact)
        val missingMappingDependency = dependency("optional-mapping", Artifact.SCOPE_COMPILE).apply { file = null }
        selected.artifacts = setOf(missingMappingDependency)
        val input = project.staticInput(17, listOf(aggregator, unrelated, selected, project))
        assertEquals(setOf(project.artifact.file.toPath().toRealPath(), selected.artifact.file.toPath().toRealPath()),
            input.projectArtifacts.keys)
        assertEquals(listOf(temporary.resolve("selected-source")), input.sourceRoots)
        assertEquals(emptyList(), input.sourceClasspaths.getValue(temporary.resolve("selected-source")))
        assertEquals(listOf(temporary.resolve("selected-classes")),
            input.projectArtifacts.getValue(selected.artifact.file.toPath().toRealPath()))
    }

    @ParameterizedTest
    @CsvSource("compile,true", "runtime,true", "provided,true", "system,true", "test,true",
        "compile,false", "runtime,false", "provided,false", "system,false", "test,false")
    fun `classpath membership follows Maven artifact handler and scope`(scope: String, added: Boolean) {
        val project = project()
        (requireNotNull(project.artifact.artifactHandler) as DefaultArtifactHandler).isAddedToClasspath = false
        val source = temporary.resolve("source")
        project.addCompileSourceRoot(source.toString())
        val selected = dependency("candidate", scope).apply {
            (requireNotNull(artifactHandler) as DefaultArtifactHandler).isAddedToClasspath = added
        }
        project.artifacts = setOf(selected)
        val input = project.staticInput(17)
        val nativeClasspath = (project.compileClasspathElements + project.runtimeClasspathElements)
            .map { Path.of(it) }.filter { it == selected.file.toPath() }.distinct()
        assertEquals(nativeClasspath.map { it.toRealPath() }, input.dependencies)
        assertEquals(nativeClasspath, input.sourceClasspaths.getValue(source))
        assertEquals(project.artifact.file.toPath().toRealPath(), input.archive)
    }

    private fun project(): MavenProject = MavenProject(Model().apply {
        groupId = "fixture"
        artifactId = "library"
        version = "1"
        build = Build().apply { outputDirectory = temporary.resolve("classes").toString() }
    }).apply { artifact = dependency("library", Artifact.SCOPE_COMPILE) }

    private fun dependency(name: String, scope: String): Artifact =
        DefaultArtifact("fixture", name, "1", scope, "jar", null, DefaultArtifactHandler("jar").apply { isAddedToClasspath = true }).apply {
            file = Files.write(temporary.resolve("$name.jar"), byteArrayOf()).toFile()
        }
}
