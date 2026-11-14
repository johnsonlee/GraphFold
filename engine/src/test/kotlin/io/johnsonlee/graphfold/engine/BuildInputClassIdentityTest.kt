package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.origin.ORIGIN_MARKER
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildInputClassIdentityTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `verified provenance paths are removed without mutating original compiler classes`() {
        val base = Files.createDirectory(temporary.resolve("base"))
        val head = Files.createDirectory(temporary.resolve("head"))
        listOf(false, true).forEach { visible ->
            val before = write(base, marker = "/checkout/base/Example.kt", visibleMarker = visible)
            val after = write(head, marker = "/checkout/head/Example.kt", visibleMarker = visible)
            val bytes = Files.readAllBytes(before)
            assertFalse(bytes.contentEquals(Files.readAllBytes(after)))
            assertTrue(proof().unchangedClasses(base, head))
            assertTrue(bytes.contentEquals(Files.readAllBytes(before)))
        }
    }

    @Test
    fun `real instructions annotations and source metadata are not erased by class identity comparison`() {
        val base = Files.createDirectory(temporary.resolve("base"))
        val head = Files.createDirectory(temporary.resolve("head"))
        write(base, marker = "/base/Example.kt", body = "same", annotation = "same", sourceFile = "Example.kt")
        write(head, marker = "/head/Example.kt", body = "changed", annotation = "same", sourceFile = "Example.kt")
        assertFalse(proof().unchangedClasses(base, head))
        write(head, marker = "/head/Example.kt", body = "same", annotation = "changed", sourceFile = "Example.kt")
        assertFalse(proof().unchangedClasses(base, head))
        write(head, marker = "/head/Example.kt", body = "same", annotation = "same", sourceFile = "Different.kt")
        assertFalse(proof().unchangedClasses(base, head))
    }

    @Test
    fun `user string values matching a provenance path remain observable`() {
        val base = Files.createDirectory(temporary.resolve("base"))
        val head = Files.createDirectory(temporary.resolve("head"))
        write(base, marker = "/base/Example.kt", body = "/base/Example.kt")
        write(head, marker = "/head/Example.kt", body = "/head/Example.kt")
        assertFalse(proof().unchangedClasses(base, head))
        write(head, marker = "/head/Example.kt", body = "/base/Example.kt")
        assertTrue(proof().unchangedClasses(base, head))
    }

    @Test
    fun `classes without injected markers compare normally and removals or path moves differ`() {
        val base = Files.createDirectory(temporary.resolve("base"))
        val head = Files.createDirectory(temporary.resolve("head"))
        write(base)
        val after = write(head)
        Files.writeString(base.resolve("ignored.txt"), "outside copied application classes")
        Files.createDirectory(base.resolve("empty.class"))
        assertTrue(proof().unchangedClasses(base, head))
        Files.move(after, after.resolveSibling("Moved.class"))
        assertFalse(proof().unchangedClasses(base, head))
        Files.delete(after.resolveSibling("Moved.class"))
        assertFalse(proof().unchangedClasses(base, head))
        assertFalse(proof().unchangedClasses(head, base))
    }

    @Test
    fun `class equality cannot bypass missing inventory resources or packaging membership gaps`() {
        val absent = temporary.resolve("not-read-when-inventory-blocks")
        val inventory = ContentInventory(emptyMap(), emptyMap())
        assertFalse(InputChangeProof(emptyList(), null, inventory).unchangedClasses(absent, absent))
        val resourceChanged = inventory.copy(nonClassEntries = mapOf("resource/0/config" to "changed"))
        assertFalse(InputChangeProof(emptyList(), inventory, resourceChanged).unchangedClasses(absent, absent))
        val base = inventory.copy(classPlacements = mapOf("Example.class" to setOf("artifact/0/Example.class")))
        val head = inventory.copy(classPlacements = mapOf("Example.class" to emptySet()))
        assertFalse(InputChangeProof(emptyList(), base, head).unchangedClasses(absent, absent))
    }

    private fun proof(): InputChangeProof {
        val inventory = ContentInventory(emptyMap(), emptyMap())
        return InputChangeProof(emptyList(), inventory, inventory)
    }

    private fun write(
        root: Path, marker: String? = null, visibleMarker: Boolean = false, body: String = "value",
        annotation: String? = null, sourceFile: String = "Example.kt",
    ): Path {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Example", null, "java/lang/Object", null)
        writer.visitSource(sourceFile, null)
        marker?.let { value ->
            writer.visitAnnotation(ORIGIN_MARKER, visibleMarker).apply { visit("source", value); visitEnd() }
        }
        annotation?.let { value ->
            writer.visitAnnotation("Lsample/UserAnnotation;", true).apply { visit("value", value); visitEnd() }
            writer.visitAnnotation("Lsample/InvisibleUserAnnotation;", false).apply { visit("value", value); visitEnd() }
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(body)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitEnd()
        val file = root.resolve("sample/Example.class")
        Files.createDirectories(file.parent)
        return Files.write(file, writer.toByteArray())
    }
}
