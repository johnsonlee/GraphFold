package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpringBootArchiveInputTest {
    @TempDir lateinit var root: Path

    @Test fun `Boot projection preserves packaged classes library provenance and root resources`() {
        val library = projectionArchive(root.resolve("library.jar"), mapOf("sample/Library.class" to bytecode("sample/Library")))
        val unrelated = projectionArchive(root.resolve("other.jar"), mapOf("sample/Other.class" to bytecode("sample/Other")))
        val source = root.resolve("classes")
        val librarySource = root.resolve("library-classes")
        val entries = linkedMapOf("loader/" to byteArrayOf(), "loader.txt" to "loader".toByteArray(), "BOOT-INF/lib/" to byteArrayOf(),
            "BOOT-INF/lib/library.jar" to Files.readAllBytes(library), "BOOT-INF/lib/readme.txt" to "opaque".toByteArray(),
            "BOOT-INF/classes/" to byteArrayOf(), "BOOT-INF/classes/sample/App.class" to bytecode("sample/App"),
            "BOOT-INF/classes/config.txt" to "config".toByteArray())
        val archive = projectionArchive(root.resolve("app.jar"), entries, attributes())
        val selected = ArchiveInput("boot", listOf(ArchiveEntryInput(archive, listOf(source))),
            producerArchives = mapOf(library to listOf(librarySource), unrelated to listOf(root.resolve("unused"))))
        val actual = requireNotNull(springBootArchiveInput(selected, root.resolve("projection")))
        assertEquals(listOf(emptyList(), listOf(source), listOf(librarySource)), actual.classpath.map { it.producerOutputs })
        assertEquals(mapOf("kind" to "spring-boot"), actual.archiveMetadata)
        val classes = readWarArchive(actual.classpath[1].artifact)
        assertContentEquals(bytecode("sample/App"), classes.getValue("sample/App.class"))
        assertEquals("config", classes.getValue("config.txt").decodeToString())
        assertContentEquals(Files.readAllBytes(library), Files.readAllBytes(actual.classpath[2].artifact))
        assertEquals("loader", readWarArchive(actual.classpath.first().artifact).getValue("loader.txt").decodeToString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `missing optional producer archive does not prevent nested library projection`(mapped: Boolean) {
        val library = projectionArchive(root.resolve("library.jar"), mapOf("sample/Library.class" to bytecode("sample/Library")))
        val archive = projectionArchive(root.resolve("app.jar"), mapOf(
            "BOOT-INF/classes/sample/App.class" to bytecode("sample/App"),
            "BOOT-INF/lib/library.jar" to Files.readAllBytes(library)))
        val librarySource = root.resolve("library-classes")
        val producers = mapOf(root.resolve("missing.jar") to listOf(root.resolve("missing-classes"))) +
            if (mapped) mapOf(library to listOf(librarySource)) else emptyMap()
        val selected = requireNotNull(springBootArchiveInput(ArchiveInput("boot",
            listOf(ArchiveEntryInput(archive, listOf(root.resolve("classes")))), producerArchives = producers),
            root.resolve("out")))
        val projectedLibrary = selected.classpath.last()
        assertContentEquals(Files.readAllBytes(library), Files.readAllBytes(projectedLibrary.artifact))
        assertEquals(if (mapped) listOf(librarySource) else emptyList(), projectedLibrary.producerOutputs)
        assertContentEquals(bytecode("sample/App"),
            readWarArchive(selected.classpath[1].artifact).getValue("sample/App.class"))
    }

    @Test fun `ordinary manifestless and multiple inputs do not masquerade as Boot`() {
        val plain = projectionArchive(root.resolve("plain.jar"), emptyMap())
        assertNull(springBootArchiveInput(ArchiveInput("plain", listOf(ArchiveEntryInput(plain))), root.resolve("out")))
        val many = ArchiveInput("many", listOf(plain, plain).map { ArchiveEntryInput(it) })
        assertNull(springBootArchiveInput(many, root.resolve("out")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["absolute", "traversal", "empty", "duplicate"])
    fun `malformed Boot packaged entries are rejected as unreadable input`(mode: String) {
        val attributes = attributes()
        val entries = linkedMapOf("BOOT-INF/classes/" to byteArrayOf(), "BOOT-INF/classes/sample/App.class" to bytecode("sample/App"))
        when (mode) {
            "empty" -> entries[""] = byteArrayOf(1)
            "absolute" -> entries["BOOT-INF/classes//outside.txt"] = byteArrayOf(1)
            "traversal" -> entries["BOOT-INF/classes/../outside.txt"] = byteArrayOf(1)
            "duplicate" -> entries.putAll(projectionBytes("first.txt" to "first", "other.txt" to "other"))
        }
        val archive = projectionArchive(root.resolve("app.jar"), entries, attributes)
        if (mode == "duplicate") duplicateProjectionEntry(archive)
        assertFailsWith<InputFailure> {
            springBootArchiveInput(ArchiveInput("boot", listOf(ArchiveEntryInput(archive, listOf(root.resolve("classes"))))),
                root.resolve("out"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["classpath", "agent", "missing-start", "blank-start", "legacy", "properties", "manifestless"])
    fun `packaged layout is the input independently of launcher declarations`(mode: String) {
        val attributes = attributes().toMutableMap()
        when (mode) {
            "classpath" -> attributes["Class-Path"] = "external.jar"
            "agent" -> attributes["Launcher-Agent-Class"] = "Agent"
            "missing-start" -> attributes.remove("Start-Class")
            "blank-start" -> attributes["Start-Class"] = " "
            "legacy" -> attributes["Main-Class"] = "org.springframework.boot.loader.JarLauncher"
            "properties" -> attributes["Main-Class"] = "org.springframework.boot.loader.launch.PropertiesLauncher"
            "manifestless" -> attributes.clear()
        }
        val archive = projectionArchive(root.resolve("layout.jar"),
            mapOf("BOOT-INF/classes/sample/App.class" to bytecode("sample/App")), attributes)
        val selected = requireNotNull(springBootArchiveInput(ArchiveInput("boot",
            listOf(ArchiveEntryInput(archive, listOf(root.resolve("classes"))))), root.resolve("out")))
        assertEquals(setOf("kind"), selected.archiveMetadata.keys)
        assertEquals("spring-boot", selected.archiveMetadata["kind"])
        val classes = selected.classpath.single { it.producerOutputs.isNotEmpty() }
        assertContentEquals(bytecode("sample/App"), readWarArchive(classes.artifact).getValue("sample/App.class"))
    }

    @Test fun `blank optional launch attributes do not affect packaged input`() {
        val archive = projectionArchive(root.resolve("app.jar"), mapOf("BOOT-INF/classes/sample/App.class" to bytecode("sample/App")),
            attributes() + mapOf("Class-Path" to " ", "Launcher-Agent-Class" to " "))
        assertTrue(springBootArchiveInput(ArchiveInput("boot",
            listOf(ArchiveEntryInput(archive, listOf(root.resolve("classes"))))), root.resolve("out")) != null)
    }

    private fun bytecode(owner: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null)
        visitEnd()
    }.toByteArray()

    private fun attributes() = mapOf("Main-Class" to "org.springframework.boot.loader.launch.JarLauncher", "Start-Class" to "sample.App")
}
