package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.ProofStatus
import io.johnsonlee.graphfold.engine.safety.ReturnNullness
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyConditionTest {
    private val argument = SafetyValue.unknown(Type.INT_TYPE, "arg:0", true)
    private val reference = SafetyValue.unknown(Type.getObjectType("java/lang/Object"), "arg:1", true)

    @Test
    fun `unsupported branch operators do not invent a known comparison result`() {
        assertNull(SafetyCondition(Opcodes.JSR, SafetyValue.integer(1), SafetyValue.integer(2)).known())
    }

    @Test
    fun `integer comparisons use signed JVM ordering including extreme values`() {
        val comparisons = listOf(
            listOf(Opcodes.IFLT, Opcodes.IF_ICMPLT) to listOf(true, false, false),
            listOf(Opcodes.IFLE, Opcodes.IF_ICMPLE) to listOf(true, true, false),
            listOf(Opcodes.IFGT, Opcodes.IF_ICMPGT) to listOf(false, false, true),
            listOf(Opcodes.IFGE, Opcodes.IF_ICMPGE) to listOf(false, true, true),
        )
        for ((opcodes, expected) in comparisons) for (opcode in opcodes) {
            val actual = listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE).map {
                SafetyCondition(opcode, SafetyValue.integer(it), SafetyValue.integer(0)).known()
            }
            assertEquals(expected, actual)
            assertNull(SafetyCondition(opcode, argument, SafetyValue.integer(0)).known())
            assertNull(SafetyCondition(opcode, SafetyValue.integer(0), argument).known())
        }
    }

    @Test
    fun `equality distinguishes constants from unknown values and preserves negation`() {
        for ((equal, unequal) in listOf(Opcodes.IFEQ to Opcodes.IFNE, Opcodes.IF_ICMPEQ to Opcodes.IF_ICMPNE)) {
            val zero = SafetyValue.integer(0)
            val knownZero = argument.copy(integer = 0)
            assertEquals(true, SafetyCondition(equal, knownZero, zero).known())
            assertEquals(false, SafetyCondition(unequal, knownZero, zero).known())
            assertEquals(false, SafetyCondition(equal, SafetyValue.integer(1), zero).known())
            assertEquals(true, SafetyCondition(unequal, SafetyValue.integer(1), zero).known())
            assertNull(SafetyCondition(equal, argument, zero).known())
            assertNull(SafetyCondition(unequal, zero, argument).known())
        }
    }

    @Test
    fun `reference identity and nullness do not invent aliases for unknown references`() {
        val nonnull = reference.copy(nullness = ReturnNullness.NON_NULL)
        val nullAlias = reference.copy(nullness = ReturnNullness.NULL)
        val cases = listOf(SafetyValue.NULL to nonnull, nonnull to SafetyValue.NULL,
            SafetyValue.NULL to nullAlias, nullAlias to SafetyValue.NULL)
        for ((left, right) in cases) {
            val expected = left.nullness == right.nullness
            assertEquals(expected, SafetyCondition(Opcodes.IF_ACMPEQ, left, right).known())
            assertEquals(!expected, SafetyCondition(Opcodes.IF_ACMPNE, left, right).known())
        }
        assertEquals(true, SafetyCondition(Opcodes.IF_ACMPEQ, reference, reference).known())
        assertNull(SafetyCondition(Opcodes.IF_ACMPEQ, reference, reference.copy(expression = "arg:2")).known())
        assertNull(SafetyCondition(Opcodes.IFNULL, reference, SafetyValue.NULL).known())
        assertNull(SafetyCondition(Opcodes.IF_ACMPNE, SafetyValue.NULL, reference).known())
        assertEquals(true, SafetyCondition(Opcodes.IFNULL, nullAlias, SafetyValue.NULL).known())
        assertEquals(true, SafetyCondition(Opcodes.IFNONNULL, nonnull, SafetyValue.NULL).known())
    }

    @Test
    fun `null guards refine a local snapshot without leaking facts into siblings or rereads`() {
        val original = SafetyState(mutableMapOf(0 to reference))
        val condition = SafetyCondition(Opcodes.IFNONNULL, reference, SafetyValue.NULL)
        val present = assertNotNull(condition.refine(original, true))
        val absent = assertNotNull(condition.refine(original, false))
        assertEquals(ReturnNullness.NON_NULL, present.resolve(reference).nullness)
        assertEquals(ReturnNullness.NULL, absent.resolve(reference).nullness)
        assertEquals(ReturnNullness.UNKNOWN, original.resolve(reference).nullness)
        val reversed = SafetyCondition(Opcodes.IF_ACMPEQ, SafetyValue.NULL, reference)
        assertEquals(ReturnNullness.NULL, assertNotNull(reversed.refine(original, true)).resolve(reference).nullness)
        val read = reference.copy(expression = "field:read1", stable = false)
        val guardedRead = assertNotNull(SafetyCondition(Opcodes.IFNULL, read, SafetyValue.NULL).refine(original, false))
        assertEquals(ReturnNullness.NON_NULL, guardedRead.resolve(read).nullness)
        assertEquals(ReturnNullness.UNKNOWN, guardedRead.resolve(read.copy(expression = "field:read2")).nullness)
        assertFalse(guardedRead.feasible, "An arbitrary field result does not itself establish a feasible witness")
        val aliases = assertNotNull(SafetyCondition(Opcodes.IF_ACMPEQ, reference, reference.copy(expression = "arg:2"))
            .refine(original, true))
        assertTrue(aliases.nullFacts.isEmpty())
        assertFalse(aliases.feasible)
    }

    @Test
    fun `integer guards exclude impossible branches without mutating the prior path`() {
        val original = SafetyState(mutableMapOf(0 to argument))
        val zero = SafetyValue.integer(0)
        val equality = SafetyCondition(Opcodes.IF_ICMPEQ, argument, zero)
        val notZero = assertNotNull(equality.refine(original, false))
        assertNull(equality.refine(notZero, true))
        val zeroPath = assertNotNull(equality.refine(original, true))
        assertEquals(0, zeroPath.resolve(argument).integer)
        assertNull(original.resolve(argument).integer)
        assertTrue(original.excludedIntegers.isEmpty())
        val reversed = SafetyCondition(Opcodes.IF_ICMPNE, zero, argument)
        assertEquals(0, assertNotNull(reversed.refine(original, false)).resolve(argument).integer)
        val notOne = assertNotNull(SafetyCondition(Opcodes.IF_ICMPEQ, SafetyValue.integer(1), argument).refine(notZero, false))
        assertEquals(setOf(0, 1), notOne.excludedIntegers[argument.expression])
        assertEquals(setOf(0), notZero.excludedIntegers[argument.expression])
        val symbolic = argument.copy(expression = "strlen(arg:1)")
        assertFalse(assertNotNull(SafetyCondition(Opcodes.IFNE, symbolic, zero).refine(original, true)).feasible)
        assertFalse(assertNotNull(SafetyCondition(Opcodes.IF_ICMPEQ, argument, argument.copy(expression = "arg:2"))
            .refine(original, true)).feasible)
        assertFalse(assertNotNull(SafetyCondition(Opcodes.IFGT, argument, zero).refine(original, true)).feasible)
    }

    @Test
    fun `negated equality and exchanged branches express the same relational result`() {
        val yes = evaluation(SafetyValue.integer(10))
        val no = evaluation(SafetyValue.integer(20))
        val equal = SafetyCondition(Opcodes.IF_ICMPEQ, argument, SafetyValue.integer(0))
        val unequal = SafetyCondition(Opcodes.IF_ICMPNE, SafetyValue.integer(0), argument)
        assertEquals(equal.key, unequal.key)
        assertEquals(equal.combine(yes, no).semanticKey(), unequal.combine(no, yes).semanticKey())
        val ordering = SafetyCondition(Opcodes.IF_ICMPLT, argument, SafetyValue.integer(0))
        assertNotEquals(ordering.key, SafetyCondition(Opcodes.IF_ICMPLT, SafetyValue.integer(0), argument).key)
        assertNotEquals(equal.key, ordering.key)
        assertNull(equal.copy(left = argument.copy(stable = false)).combine(yes, no).semanticKey())
        assertNull(equal.copy(right = SafetyValue.integer(0).copy(stable = false)).combine(yes, no).semanticKey())
        assertNull(equal.combine(yes.copy(value = yes.value?.copy(stable = false)), no).semanticKey())
        assertNull(equal.combine(yes, no.copy(value = no.value?.copy(stable = false))).semanticKey())
    }

    @Test
    fun `joins retain only shared nullness constants and exact types`() {
        val condition = SafetyCondition(Opcodes.IFNE, argument, SafetyValue.integer(0))
        val same = evaluation(SafetyValue.string("value"))
        assertEquals(same.value, condition.combine(same, same).value)
        val upcast = evaluation(assertNotNull(same.value).copy(type = Type.getObjectType("java/lang/Object")))
        val alias = assertNotNull(condition.combine(same, upcast).value)
        assertEquals(ReturnNullness.NON_NULL, alias.nullness)
        assertEquals("java/lang/String", alias.exactType)
        val differentString = evaluation(SafetyValue.string("other"))
        val joinedStrings = assertNotNull(condition.combine(same, differentString).value)
        assertNull(joinedStrings.string)
        assertEquals("java/lang/String", joinedStrings.exactType)
        val nullable = assertNotNull(condition.combine(same, evaluation(SafetyValue.NULL)).value)
        assertEquals(ReturnNullness.UNKNOWN, nullable.nullness)
        assertNull(nullable.exactType)
        val first = reference.copy(expression = "box1", nullness = ReturnNullness.NON_NULL,
            exactType = "java/lang/Integer", fresh = true, boxed = SafetyValue.integer(1))
        val second = first.copy(expression = "box2", boxed = SafetyValue.integer(2))
        val boxes = assertNotNull(condition.combine(evaluation(first), evaluation(second)).value)
        assertNull(boxes.boxed)
        assertFalse(boxes.fresh)
        val unstable = condition.combine(same, evaluation(assertNotNull(same.value).copy(stable = false)))
        assertNull(unstable.semanticKey())
    }

    @Test
    fun `joins retain unsupported branch evidence and never fabricate a return value`() {
        val condition = SafetyCondition(Opcodes.IFNE, argument, SafetyValue.integer(0))
        val safe = evaluation(SafetyValue.integer(1))
        val unknown = SafetyEvaluation(null, SafetyFacts().unknown("unsupported branch"))
        for (result in listOf(condition.combine(safe, unknown), condition.combine(unknown, safe))) {
            assertNull(result.value)
            assertNull(result.semanticKey())
            assertEquals(ProofStatus.UNPROVEN, result.facts.effects.status)
            assertTrue(result.facts.effects.evidence.contains("unsupported branch"))
        }
        assertEquals(safe, condition.combine(null, safe))
        assertEquals(safe, condition.combine(safe, null))
        assertFailsWith<IllegalArgumentException> { condition.combine(null, null) }
        assertEquals(safe.value, condition.combine(safe, safe).value)
    }

    private fun evaluation(value: SafetyValue) = SafetyEvaluation(value, SafetyFacts())
}
