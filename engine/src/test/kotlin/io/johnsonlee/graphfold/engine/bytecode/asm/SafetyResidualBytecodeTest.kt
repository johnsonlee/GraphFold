package io.johnsonlee.graphfold.engine.bytecode.asm

import org.junit.jupiter.api.Test
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SafetyResidualBytecodeTest {
    @Test
    fun `unvisited operands retain field method type local increment and allocation differences`() {
        val changes = listOf(
            IntInsnNode(Opcodes.BIPUSH, 7) to IntInsnNode(Opcodes.BIPUSH, 8),
            VarInsnNode(Opcodes.ILOAD, 1) to VarInsnNode(Opcodes.ILOAD, 2),
            TypeInsnNode(Opcodes.CHECKCAST, "Left") to TypeInsnNode(Opcodes.CHECKCAST, "Right"),
            FieldInsnNode(Opcodes.GETSTATIC, "Owner", "before", "I") to FieldInsnNode(Opcodes.GETSTATIC, "Owner", "after", "I"),
            MethodInsnNode(Opcodes.INVOKESTATIC, "Owner", "before", "()V", false) to
                MethodInsnNode(Opcodes.INVOKESTATIC, "Owner", "after", "()V", false),
            IincInsnNode(1, 1) to IincInsnNode(1, -1),
            MultiANewArrayInsnNode("[[I", 1) to MultiANewArrayInsnNode("[[I", 2),
            InsnNode(Opcodes.IADD) to InsnNode(Opcodes.ISUB),
        )
        changes.forEach { (before, after) -> assertNotEquals(fingerprint(before), fingerprint(after)) }
    }

    @Test
    fun `bootstrap arguments handles descriptors and floating raw bits remain part of residual identity`() {
        val handle = Handle(Opcodes.H_INVOKESTATIC, "Bootstrap", "link", "()Ljava/lang/Object;", false)
        assertNotEquals(fingerprint(InvokeDynamicInsnNode("value", "()I", handle, "before")),
            fingerprint(InvokeDynamicInsnNode("value", "()I", handle, "after")))
        val other = Handle(Opcodes.H_INVOKESTATIC, "Bootstrap", "other", "()Ljava/lang/Object;", false)
        assertNotEquals(fingerprint(LdcInsnNode(handle)), fingerprint(LdcInsnNode(other)))
        assertNotEquals(fingerprint(LdcInsnNode(Type.getObjectType("Before"))),
            fingerprint(LdcInsnNode(Type.getObjectType("After"))))
        assertNotEquals(fingerprint(LdcInsnNode(ConstantDynamic("value", "I", handle, "before"))),
            fingerprint(LdcInsnNode(ConstantDynamic("value", "I", handle, "after"))))
        assertNotEquals(fingerprint(LdcInsnNode(ConstantDynamic("value", "I", handle))),
            fingerprint(LdcInsnNode(ConstantDynamic("value", "I", handle, "argument"))))
        assertNotEquals(fingerprint(LdcInsnNode(0.0f)), fingerprint(LdcInsnNode(-0.0f)))
        assertNotEquals(fingerprint(LdcInsnNode(0.0)), fingerprint(LdcInsnNode(-0.0)))
    }

    @Test
    fun `switch keys ranges and target blocks cannot lose inactive control flow differences`() {
        assertNotEquals(controlFlow("lookup", false), controlFlow("lookup", true))
        assertNotEquals(controlFlow("table", false), controlFlow("table", true))
        assertNotEquals(controlFlow("jump", false), controlFlow("jump", true))
    }

    @Test
    fun `line frame and label metadata do not change unchanged residual code`() {
        val plain = MethodNode().apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            instructions.add(IntInsnNode(Opcodes.BIPUSH, 7))
            instructions.add(InsnNode(Opcodes.IRETURN))
        }
        val start = LabelNode()
        val decorated = MethodNode().apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            instructions.add(start)
            instructions.add(LineNumberNode(70, start))
            instructions.add(FrameNode(Opcodes.F_SAME, 0, null, 0, null))
            instructions.add(IntInsnNode(Opcodes.BIPUSH, 7))
            instructions.add(InsnNode(Opcodes.IRETURN))
        }
        assertEquals(SafetyResidualBytecode(plain).fingerprint(setOf(0)),
            SafetyResidualBytecode(decorated).fingerprint(setOf(0)))
        assertNull(SafetyResidualBytecode(decorated).fingerprint(setOf(0, 4, 5)))
        assertNull(SafetyResidualBytecode(MethodNode()).fingerprint(emptySet()))
    }

    @Test
    fun `a trailing target remains distinct from a target containing instructions`() {
        fun method(trailing: Boolean): MethodNode {
            val end = LabelNode()
            return MethodNode().apply {
                instructions.add(JumpInsnNode(Opcodes.GOTO, end))
                if (trailing) instructions.add(InsnNode(Opcodes.RETURN))
                instructions.add(end)
                if (!trailing) instructions.add(InsnNode(Opcodes.RETURN))
            }
        }
        assertNotEquals(SafetyResidualBytecode(method(true)).fingerprint(emptySet()),
            SafetyResidualBytecode(method(false)).fingerprint(emptySet()))
    }

    private fun fingerprint(instruction: AbstractInsnNode): String {
        val method = MethodNode().apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            instructions.add(instruction)
        }
        return assertNotNull(SafetyResidualBytecode(method).fingerprint(setOf(0)))
    }

    private fun controlFlow(kind: String, changed: Boolean): String {
        val first = LabelNode()
        val second = LabelNode()
        val branch = when (kind) {
            "lookup" -> LookupSwitchInsnNode(first, intArrayOf(if (changed) 2 else 1), arrayOf(second))
            "table" -> TableSwitchInsnNode(if (changed) 2 else 1, if (changed) 2 else 1, first, second)
            else -> JumpInsnNode(Opcodes.GOTO, if (changed) first else second)
        }
        val method = MethodNode().apply {
            instructions.add(branch)
            instructions.add(first)
            instructions.add(InsnNode(Opcodes.ICONST_0))
            instructions.add(InsnNode(Opcodes.IRETURN))
            instructions.add(second)
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.IRETURN))
        }
        return assertNotNull(SafetyResidualBytecode(method).fingerprint(emptySet()))
    }
}
