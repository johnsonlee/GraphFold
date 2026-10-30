package io.johnsonlee.graphfold.engine.origin

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginHierarchyTest {
    @TempDir lateinit var temporary: Path

    @Test fun `dependency superclass inherits Serializable through platform ArrayList`() {
        val dependency = library()
        val anonymous = anonymous("library/Base")
        val hierarchy = OriginHierarchy(listOf(anonymous), listOf(dependency))
        assertEquals(OriginSubtype.YES, hierarchy.serializable(anonymous.name))
        assertTrue(originNameObservability(listOf(anonymous), OWNERS, listOf(dependency))
            .any { "observable through Serializable" in it })
    }

    @Test fun `resolved library interface without Serializable is safe`() {
        val anonymous = anonymous("java/lang/Object").apply { interfaces.add("library/Safe") }
        val dependency = library()
        assertEquals(OriginSubtype.NO, OriginHierarchy(listOf(anonymous), listOf(dependency)).serializable(anonymous.name))
        assertTrue(originNameObservability(listOf(anonymous), OWNERS, listOf(dependency)).isEmpty())
    }

    @Test fun `unavailable external ancestor cannot establish nonserializability`() {
        val anonymous = anonymous("missing/Parent")
        assertEquals(OriginSubtype.UNKNOWN, OriginHierarchy(listOf(anonymous), emptyList()).serializable(anonymous.name))
        assertTrue(originNameObservability(listOf(anonymous), OWNERS).single().contains("Unable to verify Serializable"))
    }

    @Test fun `directory dependencies resolve and missing entries remain unknown on repeated queries`() {
        val directory = Files.createDirectories(temporary.resolve("directory"))
        val parent = anonymous("java/lang/Object").apply { name = "library/Parent" }
        val file = directory.resolve("library/Parent.class")
        Files.createDirectories(file.parent)
        Files.write(file, bytes(parent))
        val child = anonymous("library/Parent")
        val hierarchy = OriginHierarchy(listOf(child), listOf(directory, temporary.resolve("ignored.txt")))
        assertEquals(OriginSubtype.NO, hierarchy.serializable(child.name))
        repeat(2) { assertEquals(OriginSubtype.UNKNOWN, hierarchy.serializable("library/Missing")) }
    }

    @Test fun `wrong owner and cyclic dependency bytecode never establish safe ancestry`() {
        val directory = Files.createDirectories(temporary.resolve("directory"))
        val file = directory.resolve("library/Parent.class")
        Files.createDirectories(file.parent)
        Files.write(file, bytes(anonymous("java/lang/Object").apply { name = "library/Other" }))
        assertEquals(OriginSubtype.UNKNOWN,
            OriginHierarchy(listOf(anonymous("library/Parent")), listOf(directory)).serializable("sample/Host\$1"))
        val first = anonymous("library/Second").apply { name = "library/First" }
        val second = anonymous("library/First").apply { name = "library/Second" }
        assertEquals(OriginSubtype.UNKNOWN, OriginHierarchy(listOf(first, second), emptyList()).serializable(first.name))
    }

    @Test fun `missing archives and ambiguous multi release ancestry block normalization`() {
        val child = anonymous("library/Parent")
        assertEquals(OriginSubtype.UNKNOWN,
            OriginHierarchy(listOf(child), listOf(temporary.resolve("missing.jar"))).serializable(child.name))
        val jar = temporary.resolve("multiple.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { archive ->
            listOf("library/Parent.class", "META-INF/versions/17/library/Parent.class").forEach { entry ->
                archive.putNextEntry(JarEntry(entry))
                archive.write(bytes(anonymous("java/lang/Object").apply { name = "library/Parent" }))
                archive.closeEntry()
            }
        }
        assertEquals(OriginSubtype.UNKNOWN, OriginHierarchy(listOf(child), listOf(jar)).serializable(child.name))
    }

    @Test fun `absent class in a readable archive does not mask a later dependency directory`() {
        val jar = temporary.resolve("empty.jar")
        JarOutputStream(Files.newOutputStream(jar)).close()
        val directory = Files.createDirectories(temporary.resolve("directory/library"))
        Files.write(directory.resolve("Parent.class"), bytes(anonymous("java/lang/Object").apply { name = "library/Parent" }))
        assertEquals(OriginSubtype.NO,
            OriginHierarchy(listOf(anonymous("library/Parent")), listOf(jar, directory.parent)).serializable("sample/Host\$1"))
    }

    private fun bytes(node: ClassNode): ByteArray = ClassWriter(0).also(node::accept).toByteArray()

    private fun library(): Path {
        val base = temporary.resolve("Base.java")
        val safe = temporary.resolve("Safe.java")
        Files.writeString(base, "package library; public class Base extends java.util.ArrayList<String> {}")
        Files.writeString(safe, "package library; public interface Safe { int value(); }")
        val output = Files.createDirectories(temporary.resolve("library"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "17", "-d", output.toString(), base.toString(), safe.toString()))
        val jar = temporary.resolve("library.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { archive ->
            Files.walk(output).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
                archive.putNextEntry(JarEntry(output.relativize(file).toString().replace('\\', '/')))
                archive.write(Files.readAllBytes(file))
                archive.closeEntry()
            } }
        }
        return jar
    }

    private fun anonymous(parent: String) = ClassNode().apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = "sample/Host\$1"
        superName = parent
    }

    private companion object {
        val OWNERS = mapOf("sample.Host\$1" to "sample.Stable")
    }
}
