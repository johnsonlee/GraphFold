package io.johnsonlee.graphfold.engine.graphite

import org.junit.jupiter.api.Test
import org.objectweb.asm.Type
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Checks the abstract-value boundary independently of bytecode verifier reachability. */
class ExternalObjectEscapeDomainTest {
    private val interpreter = Class.forName("${ExternalObjectEscape::class.java.name}\$ApplicationTypes")
        .declaredConstructors.single().apply { isAccessible = true }.newInstance(setOf("sample/Dto")) as BasicInterpreter

    @Test fun `uninitialized values have no object identity and mismatched joins cannot acquire one`() {
        val absent = BasicValue.UNINITIALIZED_VALUE
        assertTrue(predicate("scalar", absent))
        assertFalse(predicate("observable", absent))
        assertFalse(predicate("application", absent))
        assertEquals(absent, interpreter.merge(BasicValue.INT_VALUE, BasicValue.REFERENCE_VALUE))
        assertEquals(absent, interpreter.merge(BasicValue.REFERENCE_VALUE, BasicValue.INT_VALUE))
    }

    @Test fun `primitive arrays stay observable without falsely naming an application owner`() {
        val primitiveArray = BasicValue(Type.getType("[I"))
        val objectArray = BasicValue(Type.getType("[Lsample/Dto;"))
        assertTrue(predicate("observable", primitiveArray))
        assertFalse(predicate("application", primitiveArray))
        assertTrue(predicate("observable", objectArray))
        assertTrue(predicate("application", objectArray))
    }

    @Test fun `array class tokens retain unknown observability instead of being treated as scalar values`() {
        val token = requireNotNull(interpreter.newOperation(LdcInsnNode(Type.getType("[Lsample/Dto;"))))
        assertTrue(predicate("observable", token))
        assertFalse(predicate("scalar", token))
    }

    private fun predicate(name: String, value: BasicValue): Boolean = interpreter.javaClass
        .getDeclaredMethod(name, BasicValue::class.java).apply { isAccessible = true }.invoke(interpreter, value) as Boolean
}
