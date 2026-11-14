package io.johnsonlee.graphfold.engine.bytecode.asm

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals

class DisposablePrimitiveMethodsTest {
    @TempDir lateinit var directory: Path

    @Test fun `legacy JVM subroutines remain outside the acyclic primitive contract`() {
        val owner = ClassNode().apply {
            name = "LegacyPredicate"
            version = Opcodes.V1_5
            access = Opcodes.ACC_PUBLIC
            superName = "java/lang/Object"
        }
        val subroutine = LabelNode()
        val method = MethodNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "value", "()I", null, null)
        method.instructions.add(JumpInsnNode(Opcodes.JSR, subroutine))
        method.instructions.add(InsnNode(Opcodes.ICONST_1))
        method.instructions.add(InsnNode(Opcodes.IRETURN))
        method.instructions.add(subroutine)
        method.instructions.add(VarInsnNode(Opcodes.ASTORE, 0))
        method.instructions.add(VarInsnNode(Opcodes.RET, 0))
        owner.methods.add(method)
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        owner.accept(writer)
        val bytes = writer.toByteArray()
        val loader = object : ClassLoader() {
            fun define(): Class<*> = defineClass("LegacyPredicate", bytes, 0, bytes.size)
        }
        val actual = loader.define().getDeclaredMethod("value").apply { isAccessible = true }
        assertEquals(1, actual.invoke(null), "This is valid legacy JVM bytecode, not a fabricated invalid jump")
        assertEquals(emptySet(), disposablePrimitiveMethods(owner))
    }

    @Test fun `only exact acyclic primitive computations qualify for disposable exposure calls`() {
        val source = directory.resolve("Predicates.java")
        Files.writeString(source, """
            public class Predicates {
                static int counter;
                private static boolean simple(int value) { return value > 0; }
                private static int largeConstant() { return 100000; }
                private static int arithmetic(int value) { return (value * 3 + 7) ^ (value >>> 2); }
                private static synchronized boolean synchronizedRead(int value) { return value > 0; }
                private static native boolean nativeRead(int value);
                private static boolean longInput(long value) { return value > 0; }
                private static long longOutput(int value) { return value; }
                private static boolean divide(int value) { return 10 / value > 0; }
                private static boolean remainder(int value) { return 10 % value > 0; }
                private static boolean write(int value) { counter++; return value > 0; }
                private static boolean loop(int value) { while (value > 0) value--; return value == 0; }
                private static boolean throwsAlways() { throw new IllegalStateException(); }
                private static boolean text() { String value = "constant"; return value.length() > 0; }
                private static boolean caught(int value) {
                    try { return 10 / value > 0; } catch (ArithmeticException failure) { return false; }
                }
                public static boolean externallyOverridableContract(int value) { return value > 0; }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", directory.toString(), source.toString()))
        val owner = ClassNode()
        ClassReader(Files.readAllBytes(directory.resolve("Predicates.class"))).accept(owner, ClassReader.SKIP_FRAMES)
        assertEquals(setOf("Predicates.simple(I)Z", "Predicates.largeConstant()I", "Predicates.arithmetic(I)I"),
            disposablePrimitiveMethods(owner))
    }
}
