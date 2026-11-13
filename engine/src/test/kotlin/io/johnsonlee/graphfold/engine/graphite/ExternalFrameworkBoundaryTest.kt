package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.ExternalObjectEscape

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Framework names provide no substitute for a supplied-bytecode observation proof. */
class ExternalFrameworkBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = ["org/springframework/boot/SpringApplication", "jakarta/servlet/ServletResponse",
        "com/fasterxml/jackson/databind/ObjectMapper"])
    fun `unproved external boundary retains its observation gap`(external: String) {
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "(L$external;)V", null, null)
        method.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
        method.instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, external, "toString", "()Ljava/lang/String;", false))
        method.instructions.add(InsnNode(Opcodes.POP))
        method.instructions.add(InsnNode(Opcodes.RETURN))
        method.maxStack = 1
        method.maxLocals = 1
        val owner = ClassNode().also { it.name = "Application"; it.superName = "java/lang/Object"; it.methods.add(method) }
        val gaps = ExternalObjectEscape(mapOf(owner.name to owner), { false }).methodDiagnostics().values.flatten()
        assertEquals(1, gaps.size)
        assertTrue(gaps.single().globalObservation)
        assertTrue(external in gaps.single().message)
    }
}
