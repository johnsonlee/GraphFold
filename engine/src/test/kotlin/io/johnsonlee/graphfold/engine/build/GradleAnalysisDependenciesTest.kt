package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GradleAnalysisDependenciesTest {
    @TempDir lateinit var temporary: Path

    @Test fun `only exported project artifacts with identical application classes are removed in classpath order`() {
        val classes = classes()
        val project = archive("project.jar", mapOf(CLASS to ORIGINAL, "config.properties" to "answer=42",
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n\n", "sample/" to ""))
        val external = archive("external.jar", mapOf(CLASS to ORIGINAL))
        val externalDirectory = Files.createDirectory(temporary.resolve("external-classes"))
        val entries = listOf(input(GradleInputKind.CLASSES, classes), input(GradleInputKind.ARTIFACT, project),
            input(GradleInputKind.SOURCE, temporary.resolve("src"))) +
            listOf(external, classes, project, externalDirectory, external).map { input(GradleInputKind.DEPENDENCY, it) }
        assertEquals(listOf(external, externalDirectory), gradleAnalysisDependencies(entries, classes))
        assertEquals(emptyList(), gradleAnalysisDependencies(emptyList(), classes))
    }

    @ParameterizedTest
    @EnumSource(ArtifactCase::class)
    fun `non-equivalent or unknown project artifacts are retained without discarding their classes`(case: ArtifactCase) {
        val classes = classes()
        val artifact = retainedArtifact(case, classes)
        assertEquals(listOf(artifact), gradleAnalysisDependencies(
            listOf(input(GradleInputKind.ARTIFACT, artifact), input(GradleInputKind.DEPENDENCY, artifact)), classes,
        ))
    }

    private fun retainedArtifact(case: ArtifactCase, classes: Path): Path = when (case) {
        ArtifactCase.NOT_ARCHIVE -> Files.writeString(temporary.resolve("artifact.bin"), "opaque artifact")
        ArtifactCase.DIRECTORY -> Files.createDirectory(temporary.resolve("artifact-directory"))
        else -> archive("project.jar", artifactContents(case, classes))
    }

    private fun artifactContents(case: ArtifactCase, classes: Path): Map<String, String> {
        val contents = if (case.includeApplicationClass) linkedMapOf(CLASS to ORIGINAL) else linkedMapOf()
        contents.putAll(case.contents)
        if (case == ArtifactCase.ABSOLUTE) contents[classes.resolve(CLASS).toString()] = ORIGINAL
        return contents
    }

    @Test fun `duplicate ZIP entries are not accepted as a complete equivalent project artifact`() {
        val classes = classes()
        val artifact = archive("duplicate.jar", mapOf(CLASS to ORIGINAL, "a.txt" to "first", "b.txt" to "second"))
        val bytes = Files.readAllBytes(artifact)
        val oldName = "b.txt".toByteArray()
        bytes.indices.filter { index -> index + oldName.size <= bytes.size &&
            oldName.indices.all { bytes[index + it] == oldName[it] }
        }.forEach { bytes[it] = 'a'.code.toByte() }
        Files.write(artifact, bytes)
        assertEquals(listOf(artifact), gradleAnalysisDependencies(
            listOf(input(GradleInputKind.ARTIFACT, artifact), input(GradleInputKind.DEPENDENCY, artifact)), classes,
        ))
    }

    @Test fun `missing project dependency fails before artifact exclusion`() {
        val missing = temporary.resolve("missing.jar")
        assertFailsWith<BuildFailure> {
            gradleAnalysisDependencies(listOf(input(GradleInputKind.ARTIFACT, missing),
                input(GradleInputKind.DEPENDENCY, missing)), classes())
        }
    }

    private fun classes(): Path = Files.createDirectory(temporary.resolve("classes")).also { root ->
        Files.createDirectories(root.resolve(CLASS).parent)
        Files.writeString(root.resolve(CLASS), ORIGINAL)
    }

    private fun archive(name: String, contents: Map<String, String>): Path = temporary.resolve(name).also { file ->
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            contents.forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun input(kind: GradleInputKind, path: Path) = GradleInput(kind, listOf(path))

    enum class ArtifactCase(val contents: Map<String, String> = emptyMap(), val includeApplicationClass: Boolean = true) {
        UNIQUE(mapOf("sample/Added.class" to "unique")),
        TRANSFORMED(mapOf(CLASS to "transformed")),
        VERSIONED(mapOf("META-INF/versions/17/$CLASS" to ORIGINAL)),
        BOOT(mapOf("BOOT-INF/classes/$CLASS" to ORIGINAL), includeApplicationClass = false),
        NESTED_JAR(mapOf("lib/dependency.JAR" to "nested")),
        NESTED_WAR(mapOf("lib/dependency.war" to "nested")),
        NESTED_ZIP(mapOf("lib/dependency.zip" to "nested")),
        CLASS_PATH(mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nClass-Path: other.jar\n\n")),
        RESOURCES_ONLY(mapOf("config.properties" to "answer=42"), includeApplicationClass = false),
        EMPTY(includeApplicationClass = false),
        NOT_ARCHIVE,
        DIRECTORY,
        BAD_MANIFEST(mapOf("META-INF/MANIFEST.MF" to "not a manifest header\n")),
        ABSOLUTE,
        TRAVERSAL(mapOf("../external.class" to ORIGINAL)),
        NORMALIZED_ALIAS(mapOf("sample/../$CLASS" to ORIGINAL)),
    }

    private companion object {
        const val CLASS = "sample/Example.class"
        const val ORIGINAL = "original bytecode"
    }
}
