package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarArchiveInputTest {
    @TempDir lateinit var root: Path

    @Test fun `WAR preserves source associations and web resources without a container`() {
        val library = projectionArchive(root.resolve("library.jar"), projectionBytes("sample/Library.class" to "library"))
        val other = projectionArchive(root.resolve("different.jar"), projectionBytes("sample/Other.class" to "other"))
        val classes = root.resolve("classes")
        val libraryClasses = root.resolve("library-classes")
        val war = projectionArchive(root.resolve("app.war"), projectionBytes("index.html" to "hello",
            "META-INF/context.xml" to "<Context><Loader delegate=\"true\"/></Context>",
            "WEB-INF/classes/sample/App.class" to "app", "WEB-INF/lib/readme.txt" to "notes",
            "WEB-INF/lib/sub/nested.jar" to "not a web library") + mapOf("WEB-INF/lib/library.jar" to Files.readAllBytes(library)))
        val selected = ArchiveInput("web", listOf(ArchiveEntryInput(war, listOf(classes))),
            producerArchives = mapOf(library to listOf(libraryClasses), other to listOf(root.resolve("unused")),
                root.resolve("missing.jar") to listOf(root.resolve("missing-classes"))))
        val actual = requireNotNull(warArchiveInput(selected, root.resolve("out"))).input
        val application = actual.classpath.indexOfFirst { it.producerOutputs == listOf(classes) }
        val libraryIndex = actual.classpath.indexOfFirst { it.producerOutputs == listOf(libraryClasses) }
        assertTrue(application >= 0 && libraryIndex == application + 1)
        assertEquals("war", actual.archiveMetadata["kind"])
        assertEquals(0, application)
        assertTrue(actual.classpath.last().inventoryOnly)
        val resources = readWarArchive(actual.classpath.last().artifact)
        assertEquals("hello", resources.getValue("index.html").decodeToString())
        assertTrue("WEB-INF/lib/sub/nested.jar" in resources)
    }

    @Test fun `provided API directories remain dependency archives outside application ownership`() {
        val war = projectionArchive(root.resolve("app.war"), projectionBytes("WEB-INF/classes/App.class" to "app"))
        val provided = Files.createDirectories(root.resolve("api/package"))
        Files.writeString(provided.resolve("Type.class"), "external type bytes")
        val input = ArchiveInput("web", listOf(ArchiveEntryInput(war)), staticDependencies = listOf(provided.parent))
        val projected = requireNotNull(warArchiveInput(input, root.resolve("out"))).input
        val dependency = projected.classpath.single { readWarArchive(it.artifact).containsKey("package/Type.class") }
        assertTrue(!dependency.application && dependency.producerOutputs.isEmpty())
        assertEquals("external type bytes", readWarArchive(dependency.artifact).getValue("package/Type.class").decodeToString())
    }

    @Test fun `WAR projection requires one identified archive`() {
        val jar = ArchiveEntryInput(root.resolve("ordinary.jar"))
        assertNull(warArchiveInput(ArchiveInput("plain", listOf(jar)), root.resolve("out")))
        val war = ArchiveEntryInput(root.resolve("app.war"))
        assertTrue(assertFailsWith<InputFailure> {
            warArchiveInput(ArchiveInput("many", listOf(war, war)), root.resolve("out"))
        }.message.contains("A WAR input must identify one WAR"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `WAR definitions are checked by shared static input normalization`(different: Boolean) {
        fun definition(value: Int): ByteArray = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Duplicate", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "VALUE", "I", null, value).visitEnd()
            visitEnd()
        }.toByteArray()
        fun library(name: String, value: Int): Path = projectionArchive(root.resolve(name),
            mapOf("sample/Duplicate.class" to definition(value)))
        val first = library("first.jar", 1)
        val second = library("second.jar", if (different) 2 else 1)
        val war = projectionArchive(root.resolve("app.war"), mapOf("WEB-INF/lib/one.jar" to Files.readAllBytes(first),
            "WEB-INF/lib/two.jar" to Files.readAllBytes(second)))
        val expanded = requireNotNull(warArchiveInput(ArchiveInput("web", listOf(ArchiveEntryInput(war))), root.resolve("out"))).input
        val normalize = {
            normalizeDependencyInputs(expanded.classpath.filterNot { it.inventoryOnly }.map { it.artifact },
                root.resolve("normalized"))
        }
        if (different) {
            assertTrue(assertFailsWith<InputFailure> { normalize() }.message.contains("Ambiguous static class definition"))
        } else {
            val inputs = normalize()
            java.util.jar.JarFile(inputs.last().toFile()).use { assertTrue(!it.entries().hasMoreElements()) }
        }
    }

    @Test fun `versioned module descriptors in separate WAR libraries are not duplicate application classes`() {
        fun library(name: String): ByteArray {
            val descriptor = ClassWriter(0).apply {
                visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
                visitModule(name, 0, null).visitEnd()
                visitEnd()
            }.toByteArray()
            val archive = projectionArchive(root.resolve("$name.jar"), mapOf(
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".toByteArray(),
                "META-INF/versions/9/module-info.class" to descriptor))
            return Files.readAllBytes(archive)
        }
        val war = projectionArchive(root.resolve("app.war"), mapOf(
            "WEB-INF/lib/first.jar" to library("first"), "WEB-INF/lib/second.jar" to library("second")))
        val expanded = requireNotNull(warArchiveInput(ArchiveInput("web", listOf(ArchiveEntryInput(war))), root.resolve("out"))).input
        for (variant in listOf(0, 9)) {
            val inputs = normalizeDependencyInputs(expanded.classpath.filterNot { it.inventoryOnly }.map { it.artifact },
                root.resolve("variant-$variant"), variantThreshold = variant)
            inputs.forEach { path ->
                java.util.jar.JarFile(path.toFile()).use { jar ->
                    assertTrue(jar.entries().asSequence().none { it.name.endsWith("module-info.class") })
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/absolute.txt", "../outside.txt", "folder/../normalized.txt", "duplicate"])
    fun `WAR entries retain a unique normalized identity`(name: String) {
        val entries = if (name == "duplicate") projectionBytes("first.txt" to "first", "other.txt" to "other")
            else projectionBytes(name to "payload")
        val war = projectionArchive(root.resolve("invalid.war"), entries)
        if (name == "duplicate") duplicateProjectionEntry(war)
        assertFailsWith<InputFailure> { readWarArchive(war) }
    }
}
