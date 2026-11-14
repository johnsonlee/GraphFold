package io.johnsonlee.graphfold.engine.bytecode.asm


import org.junit.jupiter.api.Test
import org.objectweb.asm.Type
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Checks the abstract-value boundary independently of bytecode verifier reachability. */
class ExternalObjectEscapeDomainTest {
    private val interpreter = assertIs<BasicInterpreter>(
        Class.forName("${ExternalObjectEscape::class.java.name}\$ApplicationTypes")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(setOf("sample/Dto"))
    )

    @Test fun `uninitialized values have no object identity and mismatched joins cannot acquire one`() {
        val absent = BasicValue.UNINITIALIZED_VALUE
        assertTrue(predicate("scalar", absent))
        assertFalse(predicate("observable", absent))
        assertFalse(predicate("application", absent))
        assertEquals(absent, interpreter.merge(BasicValue.INT_VALUE, BasicValue.REFERENCE_VALUE))
        assertEquals(absent, interpreter.merge(BasicValue.REFERENCE_VALUE, BasicValue.INT_VALUE))
    }

    @Test fun `scalar arrays cannot contain application objects while application arrays remain observable`() {
        val primitiveArray = BasicValue(Type.getType("[I"))
        val objectArray = BasicValue(Type.getType("[Lsample/Dto;"))
        assertFalse(predicate("observable", primitiveArray))
        assertFalse(predicate("application", primitiveArray))
        assertTrue(predicate("observable", objectArray))
        assertTrue(predicate("application", objectArray))
        assertFalse(predicate("observable", BasicValue(Type.getType("[[Ljava/lang/String;"))))
        assertTrue(predicate("observable", BasicValue(Type.getType("[Ljava/lang/Object;"))))
    }

    @Test fun `array class tokens retain unknown observability instead of being treated as scalar values`() {
        val token = requireNotNull(interpreter.newOperation(LdcInsnNode(Type.getType("[Lsample/Dto;"))))
        assertTrue(predicate("observable", token))
        assertFalse(predicate("scalar", token))
    }

    @Test fun `unknown call summaries and erased array loads retain unknown payloads`() {
        val call = MethodInsnNode(Opcodes.INVOKESTATIC, "Foreign", "value", "()Ljava/lang/Object;", false)
        val value = requireNotNull(interpreter.naryOperation(call, mutableListOf()))
        assertTrue(predicate("observable", value))
        val load = InsnNode(Opcodes.AALOAD)
        assertEquals(BasicValue.REFERENCE_VALUE, interpreter.binaryOperation(load, value, BasicValue.INT_VALUE))
        assertEquals(BasicValue.REFERENCE_VALUE,
            interpreter.binaryOperation(load, BasicValue.UNINITIALIZED_VALUE, BasicValue.INT_VALUE))
    }

    private fun predicate(name: String, value: BasicValue): Boolean = assertIs<Boolean>(
        interpreter.javaClass.getDeclaredMethod(name, BasicValue::class.java)
            .apply { isAccessible = true }.invoke(interpreter, value)
    )
}
