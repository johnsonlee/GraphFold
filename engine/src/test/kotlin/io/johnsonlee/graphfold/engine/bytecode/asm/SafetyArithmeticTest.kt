package io.johnsonlee.graphfold.engine.bytecode.asm

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SafetyArithmeticTest {
    @Test
    fun `integer constants preserve JVM wraparound division remainder and bit semantics`() {
        val cases = listOf(
            Operation(Opcodes.IADD, Int.MAX_VALUE, 1, Int.MIN_VALUE),
            Operation(Opcodes.ISUB, Int.MIN_VALUE, 1, Int.MAX_VALUE),
            Operation(Opcodes.IMUL, Int.MAX_VALUE, 2, -2),
            Operation(Opcodes.IDIV, -7, 3, -2), Operation(Opcodes.IDIV, 7, -3, -2),
            Operation(Opcodes.IDIV, Int.MIN_VALUE, -1, Int.MIN_VALUE),
            Operation(Opcodes.IREM, -7, 3, -1), Operation(Opcodes.IREM, 7, -3, 1),
            Operation(Opcodes.IREM, Int.MIN_VALUE, -1, 0),
            Operation(Opcodes.IAND, 0b1100, 0b1010, 0b1000),
            Operation(Opcodes.IOR, 0b1100, 0b1010, 0b1110),
            Operation(Opcodes.IXOR, 0b1100, 0b1010, 0b0110),
            Operation(Opcodes.ISHL, 1, 32, 1), Operation(Opcodes.ISHL, 1, -1, Int.MIN_VALUE),
            Operation(Opcodes.ISHR, Int.MIN_VALUE, 31, -1), Operation(Opcodes.ISHR, -8, 33, -4),
            Operation(Opcodes.IUSHR, -1, 1, Int.MAX_VALUE), Operation(Opcodes.IUSHR, Int.MIN_VALUE, 31, 1),
        )
        cases.forEach { case ->
            assertEquals(SafetyValue.integer(case.expected), safetyIntegerOperation(case.opcode,
                SafetyValue.integer(case.left), SafetyValue.integer(case.right)), case.toString())
        }
    }

    @Test
    fun `zero divisor never fabricates an integer result for either division or remainder`() {
        for (opcode in listOf(Opcodes.IDIV, Opcodes.IREM)) {
            val result = assertNotNull(safetyIntegerOperation(opcode, SafetyValue.integer(7), SafetyValue.integer(0)))
            assertNull(result.integer, "The interpreter must discharge the divisor obligation separately")
            assertNotEquals(SafetyValue.integer(0), result)
        }
    }

    @Test
    fun `neutral identities preserve the original value including unstable read identity`() {
        val inputs = listOf(SafetyValue.unknown(Type.INT_TYPE, "arg:0", true),
            SafetyValue.unknown(Type.INT_TYPE, "field:read1", false))
        inputs.forEach { value ->
            val zero = SafetyValue.integer(0)
            val one = SafetyValue.integer(1)
            assertEquals(value, safetyIntegerOperation(Opcodes.IADD, zero, value))
            assertEquals(value, safetyIntegerOperation(Opcodes.IADD, value, zero))
            assertEquals(value, safetyIntegerOperation(Opcodes.ISUB, value, zero))
            assertEquals(value, safetyIntegerOperation(Opcodes.IMUL, one, value))
            assertEquals(value, safetyIntegerOperation(Opcodes.IMUL, value, one))
        }
    }

    @Test
    fun `symbolic operations preserve operator argument order and stability of both operands`() {
        val left = SafetyValue.unknown(Type.INT_TYPE, "arg:0", true)
        val right = SafetyValue.unknown(Type.INT_TYPE, "arg:1", true)
        val results = INTEGER_OPERATIONS.keys.map { opcode ->
            val result = assertNotNull(safetyIntegerOperation(opcode, left, right))
            assertNull(result.integer)
            assertEquals(true, result.stable)
            assertNotEquals(left, result)
            assertNotEquals(right, result)
            assertEquals(false, assertNotNull(safetyIntegerOperation(opcode, left.copy(stable = false), right)).stable)
            assertEquals(false, assertNotNull(safetyIntegerOperation(opcode, left, right.copy(stable = false))).stable)
            result.expression
        }
        assertEquals(INTEGER_OPERATIONS.size, results.distinct().size)
        for (opcode in listOf(Opcodes.ISUB, Opcodes.IDIV, Opcodes.IREM, Opcodes.ISHL)) {
            assertNotEquals(safetyIntegerOperation(opcode, left, right), safetyIntegerOperation(opcode, right, left))
        }
        assertNotEquals(left, safetyIntegerOperation(Opcodes.IMUL, left, SafetyValue.integer(0)))
        for (opcode in listOf(Opcodes.IADD, Opcodes.IMUL, Opcodes.ISUB)) {
            val constant = SafetyValue.integer(2)
            val mixed = assertNotNull(safetyIntegerOperation(opcode, constant, right))
            assertNull(mixed.integer)
            assertNotEquals(constant, mixed)
            assertNotEquals(right, mixed)
            assertNotEquals(safetyIntegerOperation(opcode, SafetyValue.integer(3), right), mixed)
            assertNotEquals(left, safetyIntegerOperation(opcode, left, constant))
        }
    }

    @Test
    fun `unsupported numeric operations remain unsupported with constant or symbolic operands`() {
        val values = listOf(SafetyValue.integer(1), SafetyValue.unknown(Type.INT_TYPE, "arg:0", true))
        for (left in values) for (right in values) {
            assertNull(safetyIntegerOperation(Opcodes.FADD, left, right))
        }
    }

    private fun safetyIntegerOperation(opcode: Int, left: SafetyValue, right: SafetyValue): SafetyValue? =
        INTEGER_OPERATIONS[opcode]?.evaluate(left, right)

    private data class Operation(val opcode: Int, val left: Int, val right: Int, val expected: Int)
}
