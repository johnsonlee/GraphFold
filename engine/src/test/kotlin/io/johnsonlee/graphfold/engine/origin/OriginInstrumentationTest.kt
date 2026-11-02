package io.johnsonlee.graphfold.engine.origin

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginInstrumentationTest {
    @TempDir lateinit var temporary: Path

    @Test fun `unverified fallback removes only compiler instrumentation without interpreting metadata`() {
        val classes = Files.createDirectories(temporary.resolve("classes/sample"))
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Original", null, "java/lang/Object", null)
        writer.visitAnnotation(ORIGIN_MARKER, false).apply { visit("source", "/unverified/checkout"); visitEnd() }
        writer.visitAnnotation("Lkotlin/Metadata;", true).apply {
            visit("mv", intArrayOf(99, 0, 0)); visit("k", 1); visitEnd()
        }
        writer.visitAnnotation("Lsample/User;", false).apply { visit("value", "sample.Original"); visitEnd() }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode(); visitLdcInsn("sample.Original"); visitInsn(Opcodes.ARETURN); visitMaxs(1, 0); visitEnd()
        }
        writer.visitEnd()
        val bytes = writer.toByteArray()
        val input = classes.resolve("Original.class")
        Files.write(input, bytes)
        Files.writeString(classes.resolve("input.txt"), "uninterpreted resource")
        val result = stripOriginInstrumentation(classes.parent, temporary.resolve("copy"))
        val node = ClassNode().also { ClassReader(Files.readAllBytes(result.resolve("sample/Original.class"))).accept(it, 0) }
        assertEquals("sample/Original", node.name)
        assertEquals(listOf("Lsample/User;"), node.invisibleAnnotations.map { it.desc })
        assertEquals(listOf("Lkotlin/Metadata;"), node.visibleAnnotations.map { it.desc })
        assertEquals(listOf(99, 0, 0), node.visibleAnnotations.single().values[1])
        assertEquals("uninterpreted resource", Files.readString(result.resolve("sample/input.txt")))
        assertContentEquals(bytes, Files.readAllBytes(input), "Original compilation must remain unchanged")
        assertEquals("sample.Original", node.methods.single().instructions
            .filterIsInstance<org.objectweb.asm.tree.LdcInsnNode>().single().cst)
        assertTrue(node.methods.single().instructions.last.opcode == Opcodes.ARETURN)
    }

    @Test fun `empty compilation still produces a readable fallback directory`() {
        val classes = Files.createDirectory(temporary.resolve("classes"))
        val result = stripOriginInstrumentation(classes, temporary.resolve("copy"))
        assertTrue(Files.isDirectory(result))
        Files.list(result).use { assertEquals(0L, it.count()) }
    }
}
