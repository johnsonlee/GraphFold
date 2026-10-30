package io.johnsonlee.graphfold.engine.origin

import org.junit.jupiter.api.Test
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.assertTrue

class OriginRenderingTest {
    @Test fun `reference string concat is observable but primitive concat and unrelated dynamic calls are safe`() {
        val bootstrap = Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false)
        assertTrue(diagnostics(InvokeDynamicInsnNode("makeConcatWithConstants", "(Ljava/lang/Object;)Ljava/lang/String;",
            bootstrap, "value=\u0001")).isNotEmpty())
        assertTrue(diagnostics(InvokeDynamicInsnNode("makeConcatWithConstants", "(I)Ljava/lang/String;",
            bootstrap, "value=\u0001")).isEmpty())
        val lambdaBootstrap = Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;", false)
        assertTrue(diagnostics(InvokeDynamicInsnNode("run", "()Ljava/lang/Runnable;", lambdaBootstrap,
            Type.getMethodType("()V"), Handle(Opcodes.H_INVOKESTATIC, "sample/Host\$1", "body", "()V", false),
            Type.getMethodType("()V"))).isEmpty())
    }

    @Test fun `object rendering entry points are guarded and primitive array formatting remains safe`() {
        val observable = listOf(
            Triple("java/lang/Object", "toString", "()Ljava/lang/String;"),
            Triple("java/util/Objects", "toString", "(Ljava/lang/Object;)Ljava/lang/String;"),
            Triple("java/lang/StringBuffer", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuffer;"),
            Triple("java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"),
            Triple("java/io/PrintWriter", "print", "(Ljava/lang/Object;)V"),
        )
        observable.forEach { (owner, name, descriptor) ->
            val opcode = if (owner == "java/util/Objects") Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL
            assertTrue(diagnostics(MethodInsnNode(opcode, owner, name, descriptor, false)).isNotEmpty(), name)
        }
        assertTrue(diagnostics(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "toString",
            "([I)Ljava/lang/String;", false)).isEmpty())
        assertTrue(diagnostics(MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Intrinsics", "checkNotNull",
            "(Ljava/lang/Object;)V", false)).isEmpty())
    }

    @Test fun `without an identity change runtime name observability does not create an origin diagnostic`() {
        val node = node(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false))
        assertTrue(originNameObservability(listOf(node), emptyMap()).isEmpty())
        assertTrue(originNameObservability(listOf(node), mapOf("sample.Host\$1" to "sample.Host\$1")).isEmpty())
    }

    private fun diagnostics(instruction: org.objectweb.asm.tree.AbstractInsnNode): List<String> =
        originNameObservability(listOf(node(instruction)), mapOf("sample.Host\$1" to "sample.Stable"))

    private fun node(instruction: org.objectweb.asm.tree.AbstractInsnNode) = ClassNode().apply {
        name = "sample/Host\$1"
        superName = "java/lang/Object"
        methods.add(MethodNode(Opcodes.ACC_PUBLIC, "render", "()V", null, null).apply {
            instructions.add(instruction)
            instructions.add(InsnNode(Opcodes.RETURN))
        })
    }
}
