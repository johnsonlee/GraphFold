package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.ProofStatus
import io.johnsonlee.graphfold.engine.safety.ReturnNullness
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SafetyContractBoundaryTest {
    @Test
    fun `literal string operations preserve exact results and unsupported methods have no contract`() {
        val length = call("java/lang/String", "length", "()I")
        assertEquals(3, safetyContract(length, SafetyValue.string("abc"), emptyList())?.value?.integer)
        val unknown = SafetyValue("input", Type.getObjectType("java/lang/String"), ReturnNullness.NON_NULL)
        assertEquals("strlen(input)", safetyContract(length, unknown, emptyList())?.value?.expression)
        assertNull(safetyContract(length, null, emptyList()))
        assertNull(safetyContract(call("java/lang/String", "intern", "()Ljava/lang/String;"), unknown, emptyList()))
    }

    @Test
    fun `string equality separates null literals exact nonstrings and symbolic strings`() {
        val equals = call("java/lang/String", "equals", "(Ljava/lang/Object;)Z")
        val literal = SafetyValue.string("value")
        fun equality(receiver: SafetyValue, argument: SafetyValue) =
            assertNotNull(safetyContract(equals, receiver, listOf(argument))?.value)
        assertEquals(0, equality(literal, SafetyValue.NULL).integer)
        assertEquals(1, equality(literal, SafetyValue.string("value")).integer)
        assertEquals(0, equality(literal, SafetyValue.string("different")).integer)
        val objectValue = SafetyValue("object", Type.getObjectType("example/Object"), ReturnNullness.NON_NULL,
            exactType = "example/Object")
        assertEquals(0, equality(literal, objectValue).integer)
        val symbolic = SafetyValue("text", Type.getObjectType("java/lang/String"), ReturnNullness.NON_NULL,
            exactType = "java/lang/String")
        assertNull(equality(literal, symbolic).integer)
        assertNull(equality(symbolic, literal).integer)
        assertFalse(equality(symbolic.copy(stable = false), symbolic).stable)
        assertFalse(equality(symbolic, symbolic.copy(stable = false, exactType = null)).stable)
    }

    @Test
    fun `boxing contract retains primitive payload but never applies to a nonstatic invocation`() {
        val boxing = call("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", Opcodes.INVOKESTATIC)
        val primitive = SafetyValue.integer(7)
        val boxed = assertNotNull(safetyContract(boxing, null, listOf(primitive))?.value)
        assertEquals(ReturnNullness.NON_NULL, boxed.nullness)
        assertEquals(primitive, boxed.boxed)
        val unboxing = call("java/lang/Integer", "intValue", "()I")
        assertEquals(primitive, safetyContract(unboxing, boxed, emptyList())?.value)
        val parameter = SafetyValue("parameter", boxed.type, ReturnNullness.NON_NULL)
        assertEquals("unbox:java/lang/Integer.intValue()I(parameter)",
            safetyContract(unboxing, parameter, emptyList())?.value?.expression)
        assertNull(safetyContract(unboxing, null, emptyList()))
        assertNull(safetyContract(boxing.apply { opcode = Opcodes.INVOKEVIRTUAL }, parameter, listOf(primitive)))
    }

    @Test
    fun `Kotlin null checks distinguish known null from unsupported nullness without assuming annotations`() {
        val check = call("kotlin/jvm/internal/Intrinsics", "checkNotNull", "(Ljava/lang/Object;)V", Opcodes.INVOKESTATIC)
        val known = assertNotNull(safetyContract(check, null, listOf(SafetyValue.string("safe"))))
        assertEquals(ProofStatus.PROVEN, known.facts.exceptions.status)
        assertEquals(SafetyValue.VOID, known.value)
        val nullValue = assertNotNull(safetyContract(check, null, listOf(SafetyValue.NULL)))
        assertEquals(ProofStatus.REFUTED, nullValue.facts.exceptions.status)
        assertNull(nullValue.value)
        val unknown = SafetyValue.unknown(Type.getObjectType("java/lang/Object"), "parameter")
        assertEquals(ProofStatus.UNPROVEN, safetyContract(check, null, listOf(unknown))?.facts?.exceptions?.status)
        assertNull(safetyContract(check.apply { opcode = Opcodes.INVOKEVIRTUAL }, unknown, listOf(unknown)))
    }

    @Test
    fun `Object initialization contract requires invokespecial and unknown library names remain unsupported`() {
        val initialization = call("java/lang/Object", "<init>", "()V", Opcodes.INVOKESPECIAL)
        assertEquals(SafetyValue.VOID, safetyContract(initialization, SafetyValue("this", Type.getObjectType("java/lang/Object"),
            ReturnNullness.NON_NULL), emptyList())?.value)
        assertNull(safetyContract(initialization.apply { opcode = Opcodes.INVOKESTATIC }, null, emptyList()))
        assertNull(safetyContract(call("example/Library", "length", "()I"), SafetyValue.string("safe"), emptyList()))
    }

    private fun call(owner: String, name: String, descriptor: String, opcode: Int = Opcodes.INVOKEVIRTUAL) =
        MethodInsnNode(opcode, owner, name, descriptor, false)
}
