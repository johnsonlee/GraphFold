package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Label
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Archives are decoded without executing a JVM verifier, so malformed bodies must never receive safety keys. */
class StaticSafetyMalformedInputTest {
    @TempDir lateinit var temporary: Path

    @Test fun `undefined local read and increment in packaged bytes fail closed`() {
        val read = analyze("UndefinedRead") { visitVarInsn(Opcodes.ILOAD, 0); visitInsn(Opcodes.IRETURN) }
        val increment = analyze("UndefinedIncrement") {
            visitIincInsn(0, 1); visitVarInsn(Opcodes.ILOAD, 0); visitInsn(Opcodes.IRETURN)
        }
        listOf(read, increment).forEach(::assertUnproven)
    }

    @Test fun `falling out of a body and unsupported return-address local cannot acquire a key`() {
        val falloff = analyze("Falloff") { visitInsn(Opcodes.NOP) }
        val legacyReturn = analyze("LegacyReturn") { visitVarInsn(Opcodes.RET, 0) }
        listOf(falloff, legacyReturn).forEach(::assertUnproven)
    }

    @Test fun `missing cyclic and absent superclass inventories cannot certify static calls`() {
        listOf("Missing", "Target", null).forEachIndexed { index, superclass ->
            val caller = classBytes("Caller", "java/lang/Object") {
                visitMethodInsn(Opcodes.INVOKESTATIC, "Target", "value", "()I", false)
                visitInsn(Opcodes.IRETURN)
            }
            val target = classBytes("Target", superclass) { visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.IRETURN) }
            val artifact = archive("Hierarchy$index", mapOf("Caller" to caller, "Target" to target))
            val result = StaticSafetyAnalysis(artifact, emptyList()).use { it.summaries().getValue("Caller.value()I") }
            assertUnproven(result)
        }
    }

    @Test fun `valid legacy subroutine remains explicitly unsupported without consuming a nonexistent operand`() {
        val result = analyze("LegacySubroutine") {
            val subroutine = Label()
            visitJumpInsn(Opcodes.JSR, subroutine)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IRETURN)
            visitLabel(subroutine)
            visitVarInsn(Opcodes.ASTORE, 0)
            visitVarInsn(Opcodes.RET, 0)
        }
        assertUnproven(result)
    }

    private fun analyze(name: String, body: MethodVisitor.() -> Unit): MethodSafetySummary {
        val artifact = archive(name, mapOf(name to classBytes(name, "java/lang/Object", body)))
        return StaticSafetyAnalysis(artifact, emptyList()).use { it.summaries().getValue("$name.value()I") }
    }

    private fun classBytes(name: String, superclass: String?, body: MethodVisitor.() -> Unit): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, name, null, superclass, null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null)
        method.visitCode()
        method.body()
        method.visitMaxs(2, 1)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun archive(name: String, classes: Map<String, ByteArray>): Path {
        val artifact = temporary.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
            classes.forEach { (owner, bytes) ->
                jar.putNextEntry(JarEntry("$owner.class"))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        return artifact
    }

    private fun assertUnproven(summary: MethodSafetySummary) {
        assertEquals(ProofStatus.UNPROVEN, summary.effectFree.status)
        assertEquals(ProofStatus.UNPROVEN, summary.nonThrowing.status)
        assertEquals(ProofStatus.UNPROVEN, summary.terminating.status)
        assertNull(summary.semanticKey)
    }
}
