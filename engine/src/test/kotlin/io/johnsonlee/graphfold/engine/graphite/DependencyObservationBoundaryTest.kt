package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.DependencyObservationProof

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.assertFalse

class DependencyObservationBoundaryTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `all reflective method families are excluded without trusting their argument shapes`() {
        DependencyObservationProof(emptyList(), emptySet()).use { proof ->
            listOf("java/lang/Class", "CustomClassLoader", "java/lang/reflect/Method", "java/lang/invoke/MethodHandles",
                "kotlin/reflect/KClass", "kotlin/jvm/internal/Reflection").forEach { owner ->
                assertFalse(proof.proves(call(owner)), owner)
            }
        }
    }

    @Test
    fun `application fields missing initialization owners and malformed method bodies remain unknown`() {
        write("ApplicationField") { visitFieldInsn(Opcodes.GETSTATIC, "Application", "field", "Ljava/lang/Object;") }
        write("MissingField") { visitFieldInsn(Opcodes.GETSTATIC, "Missing", "field", "Ljava/lang/Object;") }
        write("MissingAllocation") { visitTypeInsn(Opcodes.NEW, "Missing") }
        write("MissingParent", parent = "Missing") { visitInsn(Opcodes.ACONST_NULL) }
        val empty = writer("Empty")
        empty.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", DESCRIPTOR, null, null).visitEnd()
        save("Empty", empty)
        for ((name, access) in listOf("NativeInitializer" to Opcodes.ACC_NATIVE, "EmptyInitializer" to 0)) {
            val writer = writer(name)
            writer.visitMethod(Opcodes.ACC_STATIC or access, "<clinit>", "()V", null, null).visitEnd()
            body(writer) { visitInsn(Opcodes.ACONST_NULL) }
            save(name, writer)
        }
        DependencyObservationProof(listOf(temporary), setOf("Application")).use { proof ->
            listOf("ApplicationField", "MissingField", "MissingAllocation", "MissingParent", "Empty",
                "NativeInitializer", "EmptyInitializer").forEach { name ->
                assertFalse(proof.proves(call(name)), name)
            }
        }
    }

    @Test
    fun `dynamic constants handles and mismatched or unsupported class declarations are rejected`() {
        val handle = Handle(Opcodes.H_INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false)
        write("HandleValue") { visitLdcInsn(handle) }
        val bootstrap = Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "nullConstant",
            "(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false)
        write("DynamicValue") { visitLdcInsn(ConstantDynamic("value", "Ljava/lang/Object;", bootstrap)) }
        write("Actual") { visitInsn(Opcodes.ACONST_NULL) }
        Files.copy(temporary.resolve("Actual.class"), temporary.resolve("WrongName.class"))
        val unsupported = Files.readAllBytes(temporary.resolve("Actual.class"))
        val majorOffset = 6
        unsupported[majorOffset] = 0
        unsupported[majorOffset + 1] = Byte.MAX_VALUE
        Files.write(temporary.resolve("Unsupported.class"), unsupported)
        DependencyObservationProof(listOf(temporary), emptySet()).use { proof ->
            listOf("HandleValue", "DynamicValue", "WrongName", "Unsupported").forEach { name ->
                assertFalse(proof.proves(call(name)), name)
            }
        }
    }

    @Test
    fun `instance helper chains cannot bypass the method depth budget`() {
        val owner = writer("Deep")
        val depth = 34
        for (index in 0..depth) {
            owner.visitMethod(Opcodes.ACC_PUBLIC, "value$index", DESCRIPTOR, null, null).apply {
                visitCode()
                if (index == depth) visitInsn(Opcodes.ACONST_NULL) else {
                    visitVarInsn(Opcodes.ALOAD, 0)
                    visitMethodInsn(Opcodes.INVOKEVIRTUAL, "Deep", "value${index + 1}", DESCRIPTOR, false)
                }
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        save("Deep", owner)
        DependencyObservationProof(listOf(temporary), emptySet()).use {
            assertFalse(it.proves(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "Deep", "value0", DESCRIPTOR, false)))
        }
    }

    private fun writer(name: String, parent: String = "java/lang/Object"): ClassWriter = ClassWriter(0).also {
        it.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, null, parent, null)
    }
    private fun write(name: String, parent: String = "java/lang/Object", code: MethodVisitor.() -> Unit) {
        val writer = writer(name, parent)
        body(writer, code)
        save(name, writer)
    }
    private fun body(writer: ClassWriter, code: MethodVisitor.() -> Unit) {
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", DESCRIPTOR, null, null).apply {
            visitCode()
            code()
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
    }
    private fun save(name: String, writer: ClassWriter) {
        writer.visitEnd()
        Files.write(temporary.resolve("$name.class"), writer.toByteArray())
    }
    private fun call(owner: String): MethodInsnNode = MethodInsnNode(Opcodes.INVOKESTATIC, owner, "value", DESCRIPTOR, false)

    private companion object { const val DESCRIPTOR = "()Ljava/lang/Object;" }
}
