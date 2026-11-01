package io.johnsonlee.graphfold.engine.graphite

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalObjectEscapeSentinelTest {
    @Test
    fun `immutable proof remains a reference without aliasing real or uninitialized ASM values`() {
        val real = BasicValue(Type.getObjectType("graphfold/ImmutableValueGraph"))
        assertTrue(ImmutableGraphValue.isReference)
        assertNull(BasicInterpreter().newValue(Type.VOID_TYPE))
        listOf(real, BasicValue.UNINITIALIZED_VALUE).forEach { other ->
            assertNotEquals<BasicValue>(other, ImmutableGraphValue)
            assertNotEquals<BasicValue>(ImmutableGraphValue, other)
        }
        assertEquals(3, hashSetOf(real, ImmutableGraphValue, BasicValue.UNINITIALIZED_VALUE, ImmutableGraphValue).size)
    }

    @Test
    fun `application class matching the former immutable sentinel remains an observable object`() {
        val dto = ClassNode().also {
            it.name = "graphfold/ImmutableValueGraph"
            it.superName = "java/lang/Object"
            it.access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL
        }
        val caller = ClassNode().also {
            it.name = "sample/Entry"
            it.superName = "java/lang/Object"
            it.access = Opcodes.ACC_PUBLIC
        }
        caller.methods.add(MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "expose", "(Lgraphfold/ImmutableValueGraph;)V", null, null,
        ).also {
            it.visitCode()
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitMethodInsn(Opcodes.INVOKESTATIC, "external/Encoder", "encode", "(Ljava/lang/Object;)V", false)
            it.visitInsn(Opcodes.RETURN)
            it.visitMaxs(1, 1)
            it.visitEnd()
        })
        val gap = ExternalObjectEscape(mapOf(dto.name to dto, caller.name to caller))
            .methodDiagnostics().values.single().single()
        assertFalse(gap.globalObservation)
        assertEquals(setOf("graphfold.ImmutableValueGraph"), gap.observedOwners)
        assertEquals("external.Encoder.encode(Ljava/lang/Object;)V", gap.callee)
    }
}
