package io.johnsonlee.graphfold.engine.bytecode.asm

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyClassFilesBoundaryTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `application definitions take precedence and declared dependency ordering is preserved`() {
        val application = directory("application", mapOf("App" to definition("App")))
        val first = directory("first", mapOf("Library" to definition("Library", "First")))
        val second = archive("second", mapOf("Library.class" to definition("Library", "Second"),
            "App.class" to definition("App", "Shadow")))
        SafetyClassFiles(application, listOf(first, second)).use { files ->
            assertEquals("java/lang/Object", files.find("App")?.superName)
            assertEquals("First", files.find("Library")?.superName)
            assertEquals("First", files.find("Library")?.superName)
            assertNull(files.find("kotlin/Unit"), "An available host class is not a declared dependency")
            assertNull(files.find("missing/Type"))
        }
        SafetyClassFiles(application, listOf(second, first)).use { files ->
            assertEquals("Second", files.find("Library")?.superName)
        }
    }

    @Test
    fun `archive application inventory ignores resources and directories and resolves inherited exact signatures`() {
        val app = archive("application", mapOf("folder/" to byteArrayOf(), "notes.txt" to byteArrayOf(1),
            "Child.class" to definition("Child", "Parent")))
        val dependencies = directory("dependencies", mapOf(
            "Parent" to definition("Parent", method = "value"),
            "java/lang/Object" to definition("java/lang/Object", null), "Cycle" to definition("Cycle", "Cycle")))
        SafetyClassFiles(app, listOf(dependencies)).use { files ->
            assertEquals(setOf("Child"), files.applications.keys)
            assertEquals("Parent", files.method("Child", "value", "()I")?.first?.name)
            assertNull(files.method("Child", "value", "()J"))
            assertNull(files.method("Child", "other", "()I"))
            assertNull(files.method("Cycle", "missing", "()V"))
            assertNull(files.method("Absent", "missing", "()V"))
        }
    }

    @Test
    fun `a declared abstract override does not borrow its parent implementation`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "Child", null, "Parent", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "value", "()I", null, null).visitEnd()
        writer.visitEnd()
        val app = directory("application", mapOf("Child" to writer.toByteArray()))
        val parent = directory("parent", mapOf("Parent" to definition("Parent", method = "value", static = false)))
        SafetyClassFiles(app, listOf(parent)).use { files ->
            val resolved = assertNotNull(files.method("Child", "value", "()I"))
            assertEquals("Child", resolved.first.name)
            assertTrue(resolved.second.access and Opcodes.ACC_ABSTRACT != 0)
            assertEquals(0, resolved.second.instructions.size())
        }
    }

    @Test
    fun `contracts cannot trust application or declared dependency shadows of platform types`() {
        val app = directory("application", mapOf("java/lang/String" to definition("java/lang/String")))
        val dependency = archive("shadow", mapOf("java/lang/Integer.class" to definition("java/lang/Integer")))
        SafetyClassFiles(app, listOf(dependency)).use { files ->
            assertFalse(files.supportsContract("java/lang/String"))
            assertFalse(files.supportsContract("java/lang/Integer"))
            assertTrue(files.supportsContract("java/lang/Object"))
            assertNull(files.find("java/lang/Object"), "Standard narrow contracts do not imply host bytecode fallback")
            assertFalse(files.supportsContract("custom/Library"))
        }
    }

    @Test
    fun `Kotlin null contracts require one byte-identical declared runtime definition`() {
        val owner = "kotlin/jvm/internal/Intrinsics"
        val actual = EngineRuntimeClasses.requireBytes(owner)
        val trusted = directory("trusted", mapOf(owner to actual))
        val shadow = archive("shadow", mapOf("$owner.class" to definition(owner)))
        val empty = directory("empty", emptyMap())
        SafetyClassFiles(empty, listOf(trusted)).use { assertTrue(it.supportsContract(owner)) }
        SafetyClassFiles(empty, listOf(shadow)).use { assertFalse(it.supportsContract(owner)) }
        SafetyClassFiles(empty, listOf(trusted, trusted)).use { assertFalse(it.supportsContract(owner)) }
        SafetyClassFiles(empty, emptyList()).use { assertFalse(it.supportsContract(owner)) }
        SafetyClassFiles(trusted, emptyList()).use { assertFalse(it.supportsContract(owner)) }
        assertNotNull(EngineRuntimeClasses.bytes(owner))
    }

    private fun directory(name: String, classes: Map<String, ByteArray>): Path {
        val root = Files.createDirectory(temporary.resolve(name))
        Files.createDirectory(root.resolve("empty"))
        Files.writeString(root.resolve("ignored.txt"), "not a class")
        classes.forEach { (owner, bytes) ->
            val file = root.resolve("$owner.class")
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        return root
    }

    private fun archive(name: String, entries: Map<String, ByteArray>): Path {
        val path = temporary.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (entry, bytes) -> jar.putNextEntry(JarEntry(entry)); jar.write(bytes); jar.closeEntry() }
        }
        return path
    }

    private fun definition(
        owner: String, parent: String? = "java/lang/Object", method: String? = null, static: Boolean = true,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, parent, null)
        val access = Opcodes.ACC_PUBLIC or (if (static) Opcodes.ACC_STATIC else 0)
        if (method != null) writer.visitMethod(access, method, "()I", null, null).apply {
            visitCode(); visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.IRETURN); visitMaxs(1, if (static) 0 else 1); visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
