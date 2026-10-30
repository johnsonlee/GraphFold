package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelegateMetadataRuntimeTest {
    @TempDir lateinit var directory: Path
    private val names = listOf("kotlin/jvm/internal/Reflection", "kotlin/jvm/internal/ReflectionFactory")

    @Test
    fun `exploded pinned runtime accepts unrelated classes and resources`() {
        names.forEach { name ->
            val path = directory.resolve("$name.class")
            Files.createDirectories(path.parent)
            Files.write(path, trustedBytes(name))
        }
        Files.write(directory.resolve("Unrelated.class"), unrelatedClass())
        Files.writeString(directory.resolve("README.txt"), "Runtime resources")
        assertTrue(DelegateMetadataRuntime.trusted(listOf(directory)))
    }

    @Test
    fun `nested jar with exactly one pinned adapter definition is trusted`() {
        archive(directory.resolve("stdlib.jar"), names.associate { "$it.class" to trustedBytes(it) })
        Files.writeString(directory.resolve("NOTICE"), "Not a class")
        assertTrue(DelegateMetadataRuntime.trusted(listOf(directory)))
    }

    @Test
    fun `multi release adapter duplicates cannot pass byte identity verification`() {
        val entries = names.associate { "$it.class" to trustedBytes(it) }.toMutableMap()
        entries["META-INF/versions/17/${names.first()}.class"] = trustedBytes(names.first())
        val jar = directory.resolve("multi-release.jar")
        archive(jar, entries)
        assertFalse(DelegateMetadataRuntime.trusted(listOf(jar)))
    }

    @Test
    fun `archive entry name cannot impersonate a missing adapter class`() {
        val jar = directory.resolve("spoofed.jar")
        archive(jar, mapOf("${names.first()}.class" to trustedBytes(names.first()),
            "${names.last()}.class" to unrelatedClass()))
        assertFalse(DelegateMetadataRuntime.trusted(listOf(jar)))
    }

    private fun trustedBytes(name: String): ByteArray =
        requireNotNull(Unit::class.java.getResourceAsStream("/$name.class")).use { it.readBytes() }

    private fun unrelatedClass(): ByteArray = ClassWriter(0).also {
        it.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Unrelated", null, "java/lang/Object", null)
        it.visitEnd()
    }.toByteArray()

    private fun archive(path: Path, entries: Map<String, ByteArray>) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }
}
