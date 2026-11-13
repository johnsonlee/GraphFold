package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DependencyInputCacheTest {
    @TempDir lateinit var directory: Path
    private val cache = DependencyInputCache()
    private var run = 0

    @Test fun `independent scopes reuse identical copies without mixing their definitions`() {
        val first = jar("first", mapOf("lib/Value.class" to type("lib/Value")))
        val second = jar("second", mapOf("lib/Value.class" to type("lib/Value"), "value.txt" to byteArrayOf(1)))
        val apps = listOf(application("one"), application("two"))
        val old = project(listOf(first, second), apps[0])
        val reused = project(listOf(first, second), apps[1])
        assertEquals(old, reused)
        assertTrue(entries(reused[0]).contains("lib/Value.class"))
        assertFalse(entries(reused[1]).contains("lib/Value.class"))
        assertTrue(entries(reused[1]).contains("value.txt"))
        assertFalse(Files.exists(directory.resolve("run-2")), "A cache hit does not rewrite the projected archives")
    }

    @Test fun `cached identical copies cannot conceal a different preceding definition`() {
        val first = jar("first", mapOf("lib/Value.class" to type("lib/Value")))
        val same = jar("same", mapOf("lib/Value.class" to type("lib/Value")))
        val other = jar("other", mapOf("lib/Value.class" to type("lib/Value", 1)))
        assertFalse(entries(project(listOf(first, same))[1]).contains("lib/Value.class"))
        assertFailsWith<InputFailure> { project(listOf(other, same)) }
        assertFailsWith<InputFailure> { project(listOf(same, other)) }
        assertTrue(entries(project(listOf(other)).single()).contains("lib/Value.class"))
    }

    @Test fun `application copies participate without coupling unrelated application classes`() {
        val bytes = type("lib/Value")
        val dependency = jar("library", mapOf("lib/Value.class" to bytes))
        val app = application("one")
        Files.createDirectories(app.resolve("lib"))
        Files.write(app.resolve("lib/Value.class"), bytes)
        val omitted = project(listOf(dependency), app).single()
        assertFalse("lib/Value.class" in entries(omitted))
        Files.write(app.resolve("one.class"), type("one", 2))
        assertEquals(omitted, project(listOf(dependency), app).single())
        Files.write(app.resolve("lib/Value.class"), type("lib/Value", 3))
        assertFailsWith<InputFailure> { project(listOf(dependency), app) }
        Files.delete(app.resolve("lib/Value.class"))
        assertTrue("lib/Value.class" in entries(project(listOf(dependency), app).single()))
    }

    @Test fun `content changes invalidate cache even with identical size and timestamp`() {
        val dependency = jar("library", mapOf("lib/Value.class" to type("lib/Value"), "value.txt" to byteArrayOf(1)))
        val timestamp = Files.getLastModifiedTime(dependency)
        val size = Files.size(dependency)
        val old = project(listOf(dependency)).single()
        jar("library", mapOf("lib/Value.class" to type("lib/Value"), "value.txt" to byteArrayOf(2)))
        Files.setLastModifiedTime(dependency, timestamp)
        assertEquals(size, Files.size(dependency))
        val fresh = project(listOf(dependency)).single()
        assertNotEquals(old, fresh)
        JarFile(fresh.toFile()).use { assertContentEquals(byteArrayOf(2), it.getInputStream(it.getJarEntry("value.txt")).readBytes()) }
    }

    @Test fun `directory resources and names invalidate the effective view`() {
        val dependency = Files.createDirectories(directory.resolve("dependency"))
        Files.createDirectories(dependency.resolve("lib"))
        Files.write(dependency.resolve("module-info.class"), module())
        Files.write(dependency.resolve("lib/Value.class"), type("lib/Value"))
        Files.createDirectories(dependency.resolve("META-INF/versions/9/lib"))
        Files.write(dependency.resolve("META-INF/versions/9/lib/Value.class"), type("lib/Value", 1))
        val old = project(listOf(dependency)).single()
        assertEquals(old, project(listOf(dependency)).single())
        Files.writeString(dependency.resolve("resource.txt"), "one")
        val added = project(listOf(dependency)).single()
        assertNotEquals(old, added)
        Files.move(dependency.resolve("resource.txt"), dependency.resolve("renamed.txt"))
        val renamed = project(listOf(dependency)).single()
        assertNotEquals(added, renamed)
        assertTrue("renamed.txt" in entries(renamed))
        assertFalse(entries(renamed).any { it.startsWith("META-INF/versions/") })
    }

    @Test fun `misplaced entries are rejected without reading application files outside their root`() {
        val dependency = jar("legacy", mapOf("../Outside.class" to type("Outside"), "empty/" to byteArrayOf()))
        val app = application("one")
        Files.write(app.parent.resolve("Outside.class"), type("Outside"))
        repeat(2) {
            val failure = assertFailsWith<InputFailure> { project(listOf(dependency), app) }
            assertTrue(failure.message.contains("Static class identity differs from archive path"))
        }
    }

    private fun project(dependencies: List<Path>, application: Path? = null) =
        normalizeDependencyInputs(dependencies, directory.resolve("run-${++run}"),
            applicationClasses = application, cache = cache)

    private fun application(name: String): Path = Files.createDirectories(directory.resolve("app-$name")).also {
        Files.write(it.resolve("$name.class"), type(name))
    }

    private fun entries(path: Path) = JarFile(path.toFile()).use { jar -> jar.entries().asSequence().map { it.name }.toSet() }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path = directory.resolve("$name.jar").also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val manifest = "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".toByteArray()
            (mapOf(JarFile.MANIFEST_NAME to manifest) + entries).forEach { (entry, bytes) ->
                zip.putNextEntry(ZipEntry(entry).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun module(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
        visitModule("sample.module", 0, null).visitEnd()
        visitEnd()
    }.toByteArray()

    private fun type(name: String, value: Int = 0): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "VALUE", "I", null, value).visitEnd()
        visitEnd()
    }.toByteArray()
}
