package io.johnsonlee.graphfold.engine.origin

import org.junit.jupiter.api.Test
import org.objectweb.asm.Type
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Value equality must retain provenance instead of conflating an ordinary ASM type with a proof. */
class OriginRenderValueTest {
    @Test fun `rendering provenance values do not equal a plain ASM value of the same type`() {
        val interpreter = assertIs<BasicInterpreter>(
            Class.forName("io.johnsonlee.graphfold.engine.origin.RenderInterpreter")
                .declaredConstructors.single().apply { isAccessible = true }
                .newInstance(OriginRenderTypes(emptyList(), emptySet()))
        )
        val type = Type.getObjectType("java/lang/String")
        val value = requireNotNull(interpreter.newValue(type))
        val same = requireNotNull(interpreter.newValue(type))
        listOf(BasicValue(type), null).forEach { unrelated -> assertFalse(value.equals(unrelated)) }
        assertEquals(value, same)
        assertEquals(value.hashCode(), same.hashCode())
    }
}
