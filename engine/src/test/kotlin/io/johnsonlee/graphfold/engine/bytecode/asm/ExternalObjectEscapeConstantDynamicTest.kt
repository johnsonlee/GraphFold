package io.johnsonlee.graphfold.engine.bytecode.asm


import org.junit.jupiter.api.Test
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalObjectEscapeConstantDynamicTest {
    @Test
    fun `primitive dynamic constants cannot hide bootstrap behavior in an apparently bounded DTO`() {
        val dto = owner("sample/Dto")
        val constant = LdcInsnNode(1)
        dto.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).also {
            it.visitCode()
            it.instructions.add(constant)
            it.visitInsn(Opcodes.IRETURN)
            it.visitMaxs(1, 0)
            it.visitEnd()
        })
        val caller = owner("sample/Entry")
        caller.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "expose", "(Lsample/Dto;)V", null, null).also {
            it.visitCode()
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitMethodInsn(Opcodes.INVOKESTATIC, "external/Encoder", "encode", "(Ljava/lang/Object;)V", false)
            it.visitInsn(Opcodes.RETURN)
            it.visitMaxs(1, 1)
            it.visitEnd()
        })
        val escapes = ExternalObjectEscape(mapOf(dto.name to dto, caller.name to caller))
        val literalGap = escapes.methodDiagnostics().getValue("sample.Entry.expose(Lsample/Dto;)V").single()
        assertFalse(literalGap.globalObservation)
        assertEquals(setOf("sample.Dto"), literalGap.observedOwners)

        constant.cst = ConstantDynamic("answer", "I", Handle(
            Opcodes.H_INVOKESTATIC, "external/Bootstrap", "compute",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false,
        ))
        val dynamicGap = escapes.methodDiagnostics().getValue("sample.Entry.expose(Lsample/Dto;)V").single()
        assertTrue(dynamicGap.globalObservation)
        assertTrue(dynamicGap.observedOwners.isEmpty())
    }

    @Test fun `method handle literals cannot narrow an externally inspected DTO to its declared fields`() {
        val dto = owner("sample/Dto")
        val constant = LdcInsnNode("plain value")
        dto.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "inspect", "()V", null, null).also {
            it.visitCode()
            it.instructions.add(constant)
            it.visitInsn(Opcodes.POP)
            it.visitInsn(Opcodes.RETURN)
            it.visitMaxs(1, 0)
            it.visitEnd()
        })
        val caller = owner("sample/Entry")
        caller.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "expose", "(Lsample/Dto;)V", null, null).also {
            it.visitCode()
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitMethodInsn(Opcodes.INVOKESTATIC, "external/Encoder", "encode", "(Ljava/lang/Object;)V", false)
            it.visitInsn(Opcodes.RETURN)
            it.visitMaxs(1, 1)
            it.visitEnd()
        })
        val escapes = ExternalObjectEscape(mapOf(dto.name to dto, caller.name to caller))
        assertFalse(escapes.methodDiagnostics().getValue("sample.Entry.expose(Lsample/Dto;)V").single().globalObservation)
        constant.cst = Handle(Opcodes.H_INVOKESTATIC, "external/Callback", "run", "()V", false)
        val gap = escapes.methodDiagnostics().getValue("sample.Entry.expose(Lsample/Dto;)V").single()
        assertTrue(gap.globalObservation)
        assertTrue(gap.observedOwners.isEmpty())
    }

    private fun owner(name: String) = ClassNode().also {
        it.version = Opcodes.V17
        it.name = name
        it.access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL
        it.superName = "java/lang/Object"
    }
}
