package io.johnsonlee.graphfold.engine.bytecode.asm

import org.junit.jupiter.api.Test
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApplicationReturnValuesTest {
    @Test
    fun `missing ambiguous primitive and overridable targets remain unknown`() {
        val owner = owner()
        owner.methods.add(literal("value", 0))
        owner.methods.add(literal("other", Opcodes.ACC_STATIC))
        owner.methods.add(literal("overload", Opcodes.ACC_STATIC).also { it.desc = "(I)Ljava/lang/Object;" })
        owner.methods.add(literal("native", Opcodes.ACC_NATIVE))
        owner.methods.add(literal("abstract", Opcodes.ACC_ABSTRACT))
        owner.methods.add(literal("duplicate", Opcodes.ACC_STATIC))
        owner.methods.add(literal("duplicate", Opcodes.ACC_STATIC))
        val resolver = resolver(owner)
        assertNull(resolver.resolve(call("value", owner = "Missing"), emptyList()))
        assertNull(resolver.resolve(call("missing"), emptyList()))
        assertNull(resolver.resolve(call("overload"), emptyList()))
        assertNull(resolver.resolve(call("duplicate"), emptyList()))
        assertNull(resolver.resolve(call("primitive").also { it.desc = "()I" }, emptyList()))
        assertNull(resolver.resolve(call("value", Opcodes.INVOKEVIRTUAL), listOf(receiver)))
        assertNull(resolver.resolve(call("native", Opcodes.INVOKESPECIAL), listOf(receiver)))
        assertNull(resolver.resolve(call("abstract", Opcodes.INVOKESPECIAL), listOf(receiver)))
    }

    @Test
    fun `special private final and final owner calls have exact targets and cache safe returns`() {
        val owner = owner()
        owner.methods.add(literal("special", 0))
        owner.methods.add(literal("private", Opcodes.ACC_PRIVATE))
        owner.methods.add(literal("final", Opcodes.ACC_FINAL))
        val resolver = resolver(owner)
        assertEquals(string, resolver.resolve(call("special", Opcodes.INVOKESPECIAL), listOf(receiver)))
        assertEquals(string, resolver.resolve(call("private", Opcodes.INVOKEVIRTUAL), listOf(receiver)))
        assertEquals(string, resolver.resolve(call("final", Opcodes.INVOKEVIRTUAL), listOf(receiver)))
        assertEquals(string, resolver.resolve(call("final", Opcodes.INVOKEVIRTUAL), listOf(receiver)))
        val closed = owner().also { it.access = Opcodes.ACC_FINAL; it.methods.add(literal("value", 0)) }
        assertEquals(string, resolver(closed).resolve(call("value", Opcodes.INVOKEVIRTUAL), listOf(receiver)))
    }

    @Test
    fun `malformed empty and unknown return flows cannot prove immutable values`() {
        val owner = owner()
        owner.methods.add(method("broken") { visitInsn(Opcodes.ARETURN) })
        owner.methods.add(method("throws") { visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ATHROW) })
        owner.methods.add(method("unknown") {
            visitTypeInsn(Opcodes.NEW, "java/lang/Object")
            visitInsn(Opcodes.ARETURN)
        })
        val resolver = resolver(owner)
        for (name in listOf("broken", "throws", "unknown")) {
            assertNull(resolver.resolve(call(name), emptyList()))
            assertNull(resolver.resolve(call(name), emptyList()))
        }
    }

    @Test
    fun `unreachable returns do not invalidate a proven live return`() {
        val owner = owner()
        owner.methods.add(method("value") {
            val target = Label()
            visitJumpInsn(Opcodes.GOTO, target)
            visitTypeInsn(Opcodes.NEW, "java/lang/Object")
            visitInsn(Opcodes.ARETURN)
            visitLabel(target)
            visitLdcInsn("text")
            visitInsn(Opcodes.ARETURN)
        })
        assertEquals(string, resolver(owner).resolve(call("value"), emptyList()))
    }

    @Test
    fun `recursion and helper depth limits fail closed without polluting later independent contexts`() {
        val owner = owner()
        owner.methods.add(method("recursive") {
            visitMethodInsn(Opcodes.INVOKESTATIC, "Sample", "recursive", "()Ljava/lang/Object;", false)
            visitInsn(Opcodes.ARETURN)
        })
        val depth = 34
        for (index in 0 until depth) owner.methods.add(method("deep$index") {
            visitMethodInsn(Opcodes.INVOKESTATIC, "Sample", "deep${index + 1}", "()Ljava/lang/Object;", false)
            visitInsn(Opcodes.ARETURN)
        })
        owner.methods.add(literal("deep$depth", Opcodes.ACC_STATIC))
        owner.methods.add(literal("independent", Opcodes.ACC_STATIC))
        val resolver = resolver(owner)
        assertNull(resolver.resolve(call("recursive"), emptyList()))
        assertNull(resolver.resolve(call("deep0"), emptyList()))
        assertEquals(string, resolver.resolve(call("independent"), emptyList()))
    }

    private fun resolver(owner: ClassNode): ApplicationReturnValues {
        lateinit var result: ApplicationReturnValues
        result = ApplicationReturnValues(mapOf(owner.name to owner), { bindings ->
            object : BasicInterpreter(Opcodes.ASM9) {
                override fun newValue(type: Type?): BasicValue? =
                    if (type?.sort == Type.OBJECT) BasicValue(type) else super.newValue(type)

                override fun newParameterValue(isInstanceMethod: Boolean, local: Int, type: Type): BasicValue? =
                    bindings[local] ?: super.newParameterValue(isInstanceMethod, local, type)

                override fun naryOperation(instruction: org.objectweb.asm.tree.AbstractInsnNode,
                    values: MutableList<out BasicValue>): BasicValue? =
                    (instruction as? MethodInsnNode)?.let { result.resolve(it, values) }
                        ?: super.naryOperation(instruction, values)
            }
        }, { it == string })
        return result
    }

    private fun owner(): ClassNode = ClassNode().also { it.name = "Sample"; it.superName = "java/lang/Object" }
    private fun literal(name: String, access: Int): MethodNode = method(name, access) {
        visitLdcInsn("text")
        visitInsn(Opcodes.ARETURN)
    }
    private fun method(name: String, access: Int = Opcodes.ACC_STATIC, body: MethodNode.() -> Unit): MethodNode =
        MethodNode(access, name, "()Ljava/lang/Object;", null, null).also {
            it.visitCode()
            it.body()
            it.visitMaxs(1, 1)
            it.visitEnd()
        }
    private fun call(name: String, opcode: Int = Opcodes.INVOKESTATIC, owner: String = "Sample"): MethodInsnNode =
        MethodInsnNode(opcode, owner, name, "()Ljava/lang/Object;", false)

    private companion object {
        val string = BasicValue(Type.getObjectType("java/lang/String"))
        val receiver = BasicValue(Type.getObjectType("Sample"))
    }
}
