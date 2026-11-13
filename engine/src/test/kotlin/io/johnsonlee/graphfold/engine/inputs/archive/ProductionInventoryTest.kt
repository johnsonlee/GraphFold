package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProductionInventoryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `identity depends on content and logical paths rather than checkout or timestamps`() {
        val left = Files.createDirectory(temporary.resolve("left"))
        val right = Files.createDirectory(temporary.resolve("right"))
        listOf(left, right).forEach { root ->
            write(root, "sample/Example.class", "class")
            write(root.resolve("resources"), "application.properties", "enabled=true")
        }
        Files.setLastModifiedTime(right.resolve("sample/Example.class"), FileTime.fromMillis(0))
        val before = captureProductionInventory(left, listOf(left.resolve("resources")), emptyList(), emptyList())
        val after = captureProductionInventory(right, listOf(right.resolve("resources")), emptyList(), emptyList())
        assertEquals(before, after)
        assertFalse(before.entries.keys.any { it.contains(temporary.toString()) })
        assertEquals(setOf("class/sample/Example.class", "resource/0/application.properties"), before.entries.keys)
    }

    @Test
    fun `resource additions changes deletions and relative names remain observable`() {
        val classes = temporary.resolve("classes")
        val resources = Files.createDirectory(temporary.resolve("resources"))
        fun capture() = captureProductionInventory(classes, listOf(resources), emptyList(), emptyList())
        val empty = capture()
        val resource = write(resources, "messages/zh.json", "hello")
        val added = capture()
        assertNotEquals(empty, added)
        Files.writeString(resource, "goodbye")
        val modified = capture()
        assertNotEquals(added.nonClassEntries, modified.nonClassEntries)
        Files.move(resource, resources.resolve("messages/en.json"))
        assertNotEquals(modified, capture())
        Files.delete(resources.resolve("messages/en.json"))
        assertEquals(empty, capture())
        Files.delete(resources.resolve("messages"))
        Files.delete(resources)
        assertEquals(empty, capture())
    }

    @Test
    fun `resource outputs omit only content matched application classes from nonclass evidence`() {
        val classes = Files.createDirectory(temporary.resolve("classes"))
        write(classes, "sample/Example.class", "original")
        val resources = Files.createDirectory(temporary.resolve("resources"))
        write(resources, "sample/Example.class", "original")
        write(resources, "sample/Unknown.class", "original")
        write(resources, "README.md", "runtime resource")
        val before = captureProductionInventory(classes, listOf(resources, classes), emptyList(), emptyList())
        assertEquals(setOf("resource/0/sample/Unknown.class", "resource/0/README.md"), before.nonClassEntries.keys)
        Files.writeString(resources.resolve("sample/Example.class"), "transformed")
        val after = captureProductionInventory(classes, listOf(resources), emptyList(), emptyList())
        assertTrue("resource/0/sample/Example.class" in after.nonClassEntries)
        assertTrue("class/sample/Example.class" in after.entries)
    }

    @Test
    fun `runtime dependency order and unknown or transformed class contents remain observable`() {
        val classes = Files.createDirectory(temporary.resolve("classes"))
        write(classes, "sample/Example.class", "original")
        val first = archive("first.jar", listOf("sample/Example.class" to "original"))
        val second = archive("second.jar", listOf("library/B.class" to "second"))
        fun capture(dependencies: List<Path>) = captureProductionInventory(classes, emptyList(), dependencies, emptyList())
        val before = capture(listOf(first, second))
        assertNotEquals(before.entries["dependency/0"], before.nonClassEntries["dependency/0"])
        assertEquals(before.entries["dependency/1"], before.nonClassEntries["dependency/1"])
        assertNotEquals(before, capture(listOf(second, first)))
        assertNotEquals(before.nonClassEntries, capture(listOf(second, first)).nonClassEntries)
        archive("first.jar", listOf("sample/Example.class" to "changed"))
        assertNotEquals(before, capture(listOf(first, second)))
        assertNotEquals(before.nonClassEntries, capture(listOf(first, second)).nonClassEntries)
        assertNotEquals(before, capture(listOf(second)))
    }

    @Test
    fun `matched application class changes in runtime directories and jars retain only nonclass evidence`() {
        val classes = Files.createDirectory(temporary.resolve("classes"))
        val library = Files.createDirectory(temporary.resolve("library"))
        fun compile(value: String): Path {
            write(classes, "sample/Example.class", value)
            write(library, "sample/Example.class", value)
            return archive("library.jar", listOf(
                "sample/Example.class" to value,
                "BOOT-INF/classes/sample/Example.class" to value,
                "WEB-INF/classes/sample/Example.class" to value,
                "messages.txt" to "unchanged",
            ))
        }
        val jar = compile("base")
        fun capture() = captureProductionInventory(classes, emptyList(), listOf(classes, library, jar), emptyList())
        val before = capture()
        compile("head")
        val after = capture()
        assertNotEquals(before.entries, after.entries)
        assertEquals(before.nonClassEntries, after.nonClassEntries)
        assertEquals(before.classPlacements, after.classPlacements)
        assertEquals(setOf("dependency/0/sample/Example.class", "dependency/1/sample/Example.class",
            "dependency/2/sample/Example.class", "dependency/2/BOOT-INF/classes/sample/Example.class",
            "dependency/2/WEB-INF/classes/sample/Example.class"), before.classPlacements["sample/Example.class"])
        write(library, "sample/Example.class", "external transformation")
        assertNotEquals(after.nonClassEntries, capture().nonClassEntries)
        compile("head")
        write(library, "messages.txt", "new runtime resource")
        assertNotEquals(after.nonClassEntries, capture().nonClassEntries)
    }

    @Test
    fun `runtime directory links are traversed at their logical locations`() {
        val target = Files.createDirectory(temporary.resolve("target"))
        write(target, "config.txt", "before")
        val root = Files.createDirectory(temporary.resolve("resources"))
        Files.createSymbolicLink(root.resolve("linked"), target)
        val linkedRoot = Files.createSymbolicLink(temporary.resolve("linked-root"), root)
        val missingClasses = temporary.resolve("classes")
        fun capture(directory: Path) = captureProductionInventory(missingClasses, listOf(directory), listOf(directory), emptyList())
        val before = capture(root)
        assertTrue("resource/0/linked/config.txt" in before.entries)
        assertEquals(before, capture(linkedRoot))
        write(target, "config.txt", "after")
        assertNotEquals(before.nonClassEntries, capture(root).nonClassEntries)
    }

    @Test
    fun `runtime directory link cycles fail instead of dropping linked inputs`() {
        val root = Files.createDirectory(temporary.resolve("resources"))
        Files.createSymbolicLink(root.resolve("cycle"), root)
        assertFailsWith<java.io.UncheckedIOException> {
            captureProductionInventory(temporary.resolve("classes"), listOf(root), emptyList(), emptyList())
        }
    }

    @Test
    fun `directory and plain file dependencies preserve contents without absolute locations`() {
        val first = Files.createDirectory(temporary.resolve("first"))
        val second = Files.createDirectory(temporary.resolve("second"))
        write(first, "A.class", "same")
        write(second, "A.class", "same")
        val binary = write(temporary, "dependency.bin", "original")
        fun capture(directory: Path) = captureProductionInventory(temporary.resolve("absent"), emptyList(),
            listOf(directory, binary), emptyList())
        val before = capture(first)
        assertEquals(before, capture(second))
        Files.writeString(binary, "changed")
        assertNotEquals(before, capture(first))
        write(first, "A.class", "changed")
        assertNotEquals(capture(first), capture(second))
    }

    @Test
    fun `archive identity ignores entry order compression timestamps and directory entries`() {
        val records = listOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n", "a.txt" to "content")
        val first = archive("first.jar", records, timestamp = 0, compression = 0)
        val second = archive("second.jar", records.reversed() + ("META-INF/" to ""),
            timestamp = 1_000_000_000_000, compression = 9)
        fun capture(artifact: Path) = captureProductionInventory(temporary.resolve("absent"), emptyList(),
            listOf(artifact), listOf(artifact))
        assertEquals(capture(first), capture(second))
    }

    @Test
    fun `packaged classes are excluded only at a matching JVM location and with identical bytes`() {
        val classes = Files.createDirectory(temporary.resolve("classes"))
        write(classes, "sample/Example.class", "original")
        write(classes, "BOOT-INF/classes/sample/Root.class", "same root bytes")
        write(classes, "sample/Root.class", "same root bytes")
        val artifact = archive("application.jar", listOf(
            "sample/Example.class" to "original",
            "BOOT-INF/classes/sample/Root.class" to "same root bytes",
            "sample/Root.class" to "same root bytes",
            "other/Root.class" to "same root bytes",
            "BOOT-INF/classes/sample/Example.class" to "original",
            "WEB-INF/classes/sample/Example.class" to "original",
            "embedded/sample/Example.class" to "original",
            "sample/Unknown.class" to "original",
            "BOOT-INF/classes/WEB-INF/classes/sample/Example.class" to "original",
            "META-INF/versions/17/sample/Example.class" to "original",
            "README.md" to "packaged document",
        ))
        val before = captureProductionInventory(classes, emptyList(), emptyList(), listOf(artifact))
        listOf("sample/Example.class", "BOOT-INF/classes/sample/Example.class", "WEB-INF/classes/sample/Example.class")
            .forEach { assertFalse("artifact/0/$it" in before.nonClassEntries) }
        listOf("embedded/sample/Example.class", "sample/Unknown.class", "README.md",
            "BOOT-INF/classes/WEB-INF/classes/sample/Example.class", "META-INF/versions/17/sample/Example.class")
            .forEach { assertTrue("artifact/0/$it" in before.nonClassEntries) }
        assertEquals(setOf("artifact/0/BOOT-INF/classes/sample/Root.class"),
            before.classPlacements["BOOT-INF/classes/sample/Root.class"])
        assertEquals(setOf("artifact/0/sample/Root.class"), before.classPlacements["sample/Root.class"])
        assertFalse("artifact/0/BOOT-INF/classes/sample/Root.class" in before.nonClassEntries)
        assertTrue("artifact/0/other/Root.class" in before.nonClassEntries)
        archive("application.jar", listOf("sample/Example.class" to "transformed"))
        val after = captureProductionInventory(classes, emptyList(), emptyList(), listOf(artifact))
        assertTrue("artifact/0/sample/Example.class" in after.nonClassEntries)
    }

    @Test
    fun `package manifests resources and empty artifact presence remain observable`() {
        val artifact = archive("application.jar", emptyList())
        fun capture(artifacts: List<Path>) = captureProductionInventory(temporary.resolve("classes"), emptyList(),
            emptyList(), artifacts)
        assertNotEquals(capture(emptyList()), capture(listOf(artifact)))
        archive("application.jar", listOf("META-INF/MANIFEST.MF" to "Main-Class: First\n", "text.txt" to "first"))
        val before = capture(listOf(artifact))
        archive("application.jar", listOf("META-INF/MANIFEST.MF" to "Main-Class: Second\n", "text.txt" to "first"))
        val manifestChanged = capture(listOf(artifact))
        assertNotEquals(before.nonClassEntries, manifestChanged.nonClassEntries)
        archive("application.jar", listOf("META-INF/MANIFEST.MF" to "Main-Class: Second\n", "text.txt" to "second"))
        assertNotEquals(manifestChanged.nonClassEntries, capture(listOf(artifact)).nonClassEntries)
    }

    @Test
    fun `application class packaging membership survives bytecode normalization`() {
        val classes = Files.createDirectory(temporary.resolve("classes"))
        write(classes, "sample/Example.class", "class")
        val artifact = archive("application.jar", listOf("sample/Example.class" to "class"))
        fun capture() = captureProductionInventory(classes, listOf(classes), emptyList(), listOf(artifact))
        val before = capture()
        assertEquals(setOf("resource/0/sample/Example.class", "artifact/0/sample/Example.class"),
            before.classPlacements["sample/Example.class"])
        archive("application.jar", emptyList())
        val excluded = capture()
        assertEquals(before.nonClassEntries, excluded.nonClassEntries)
        assertEquals(setOf("resource/0/sample/Example.class"), excluded.classPlacements["sample/Example.class"])
        archive("application.jar", listOf("BOOT-INF/classes/sample/Example.class" to "class"))
        val relocated = capture()
        assertEquals(before.nonClassEntries, relocated.nonClassEntries)
        assertEquals(setOf("resource/0/sample/Example.class", "artifact/0/BOOT-INF/classes/sample/Example.class"),
            relocated.classPlacements["sample/Example.class"])
        val unplaced = captureProductionInventory(classes, emptyList(), emptyList(), emptyList())
        assertEquals(mapOf("sample/Example.class" to emptySet()), unplaced.classPlacements)
    }

    @Test
    fun `missing dependencies artifacts and invalid output directories fail closed`() {
        val missing = temporary.resolve("missing")
        assertFailsWith<InputFailure> { captureProductionInventory(missing, emptyList(), listOf(missing), emptyList()) }
        assertFailsWith<InputFailure> { captureProductionInventory(missing, emptyList(), emptyList(), listOf(missing)) }
        val file = write(temporary, "file", "content")
        assertFailsWith<InputFailure> { captureProductionInventory(file, emptyList(), emptyList(), emptyList()) }
        assertFailsWith<InputFailure> { captureProductionInventory(missing, listOf(file), emptyList(), emptyList()) }
        assertFailsWith<InputFailure> { captureProductionInventory(missing, emptyList(), emptyList(), listOf(temporary)) }
        assertEquals(ProductionInventory(emptyMap(), emptyMap(), resourceConsumersKnown = true),
            captureProductionInventory(missing, listOf(missing), emptyList(), emptyList()))
    }

    @Test
    fun `duplicate archive entries fail instead of silently overwriting content evidence`() {
        val artifact = archive("duplicate.jar", listOf("a.txt" to "first", "b.txt" to "second"))
        val bytes = Files.readAllBytes(artifact)
        val oldName = "b.txt".toByteArray()
        bytes.indices.filter { index -> index + oldName.size <= bytes.size &&
            oldName.indices.all { bytes[index + it] == oldName[it] }
        }.forEach { bytes[it] = 'a'.code.toByte() }
        Files.write(artifact, bytes)
        val failure = assertFailsWith<InputFailure> {
            captureProductionInventory(temporary.resolve("classes"), emptyList(), emptyList(), listOf(artifact))
        }
        assertTrue(failure.message.contains("Duplicate production archive entry a.txt"))
    }

    private fun write(root: Path, name: String, value: String): Path {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        return Files.writeString(file, value)
    }

    private fun archive(
        name: String, entries: List<Pair<String, String>>, timestamp: Long = 0, compression: Int = 6,
    ): Path {
        val archive = temporary.resolve(name)
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.setLevel(compression)
            entries.forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path).apply { time = timestamp })
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return archive
    }
}
