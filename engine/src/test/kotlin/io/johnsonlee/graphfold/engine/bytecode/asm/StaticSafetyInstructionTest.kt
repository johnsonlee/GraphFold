package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Emitted instructions are analysis inputs, not execution-based safety certificates. */
class StaticSafetyInstructionTest {
    @TempDir lateinit var temporary: Path

    @Test fun `numeric constants and category two local slots retain exact values`() {
        val values = listOf(0L, 1L, 9876543210L, 0.0f, 1.0f, 2.0f, -0.0f, 3.5f, 0.0, 1.0, -0.0, 3.5)
        values.forEachIndexed { index, value ->
            val type = when (value) { is Long -> Type.LONG_TYPE; is Float -> Type.FLOAT_TYPE; else -> Type.DOUBLE_TYPE }
            val shortOpcode = when (value) {
                0L -> Opcodes.LCONST_0
                1L -> Opcodes.LCONST_1
                0.0f -> Opcodes.FCONST_0
                1.0f -> Opcodes.FCONST_1
                2.0f -> Opcodes.FCONST_2
                0.0 -> Opcodes.DCONST_0
                1.0 -> Opcodes.DCONST_1
                else -> null
            }
            val direct = analyze("Numeric$index", "()${type.descriptor}") {
                visitLdcInsn(value)
                visitInsn(type.getOpcode(Opcodes.IRETURN))
            }
            val stored = analyze("Stored$index", "()${type.descriptor}") {
                if (shortOpcode == null) visitLdcInsn(value) else visitInsn(shortOpcode)
                visitVarInsn(type.getOpcode(Opcodes.ISTORE), 2)
                visitVarInsn(type.getOpcode(Opcodes.ILOAD), 2)
                visitInsn(type.getOpcode(Opcodes.IRETURN))
            }
            assertComplete(direct)
            assertComplete(stored)
            assertEquals(direct.semanticKey, stored.semanticKey, value.toString())
        }
    }

    @Test fun `stack rearrangements and increments preserve the selected integer`() {
        val direct = analyze("Direct", "()I") { visitIntInsn(Opcodes.SIPUSH, 321); visitInsn(Opcodes.IRETURN) }
        val rearranged = analyze("Rearranged", "()I") {
            visitInsn(Opcodes.NOP)
            visitIntInsn(Opcodes.BIPUSH, 12)
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.POP)
            visitIntInsn(Opcodes.SIPUSH, 320)
            visitInsn(Opcodes.SWAP)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ISTORE, 0)
            visitIincInsn(0, 1)
            visitVarInsn(Opcodes.ILOAD, 0)
            visitInsn(Opcodes.IRETURN)
        }
        assertComplete(rearranged)
        assertEquals(direct.semanticKey, rearranged.semanticKey)
    }

    @Test fun `switch selectors distinguish cases and retain a total default`() {
        listOf(false, true).forEach { table ->
            val symbolic = switchSummary("Symbolic$table", table, null)
            assertComplete(symbolic)
            val first = switchSummary("First$table", table, 1)
            val second = switchSummary("Second$table", table, 2)
            val otherwise = switchSummary("Default$table", table, 3)
            listOf(first, second, otherwise).forEach(::assertComplete)
            assertNotEquals(first.semanticKey, second.semanticKey)
            assertNotEquals(second.semanticKey, otherwise.semanticKey)
        }
    }

    @Test fun `casts prove null exact string and Object but reject an unknown narrowing target`() {
        val nullCast = analyze("NullCast", "()Ljava/lang/String;") {
            visitInsn(Opcodes.ACONST_NULL); visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String"); visitInsn(Opcodes.ARETURN)
        }
        val exactCast = analyze("ExactCast", "()Ljava/lang/String;") {
            visitLdcInsn("known"); visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String"); visitInsn(Opcodes.ARETURN)
        }
        val objectCast = analyze("ObjectCast", "(Ljava/lang/Object;)Ljava/lang/Object;") {
            visitVarInsn(Opcodes.ALOAD, 0); visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object"); visitInsn(Opcodes.ARETURN)
        }
        listOf(nullCast, exactCast, objectCast).forEach(::assertComplete)
        val narrowing = analyze("Narrowing", "(Ljava/lang/Object;)Ljava/lang/String;") {
            visitVarInsn(Opcodes.ALOAD, 0); visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String"); visitInsn(Opcodes.ARETURN)
        }
        assertEquals(ProofStatus.UNPROVEN, narrowing.nonThrowing.status)
        assertNull(narrowing.semanticKey)
    }

    @Test fun `instanceof handles null exact and symbolic operands without inventing equality`() {
        val nullType = analyze("NullType", "()Z") {
            visitInsn(Opcodes.ACONST_NULL); visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String"); visitInsn(Opcodes.IRETURN)
        }
        val exactType = analyze("ExactType", "()Z") {
            visitLdcInsn("known"); visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String"); visitInsn(Opcodes.IRETURN)
        }
        val unknownType = analyze("UnknownType", "(Ljava/lang/Object;)Z") {
            visitVarInsn(Opcodes.ALOAD, 0); visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String"); visitInsn(Opcodes.IRETURN)
        }
        listOf(nullType, exactType, unknownType).forEach(::assertComplete)
        assertNotEquals(nullType.semanticKey, exactType.semanticKey)
        assertNotEquals(exactType.semanticKey, unknownType.semanticKey)
    }

    @Test fun `array length is safe after a guard but null array and unsupported array operations have no key`() {
        val guarded = analyze("GuardedArray", "([I)I") {
            val present = Label()
            visitVarInsn(Opcodes.ALOAD, 0); visitJumpInsn(Opcodes.IFNONNULL, present)
            visitInsn(Opcodes.ICONST_0); visitInsn(Opcodes.IRETURN)
            visitLabel(present); visitVarInsn(Opcodes.ALOAD, 0); visitInsn(Opcodes.ARRAYLENGTH); visitInsn(Opcodes.IRETURN)
        }
        assertComplete(guarded)
        val nullArray = analyze("NullArray", "()I") {
            visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARRAYLENGTH); visitInsn(Opcodes.IRETURN)
        }
        assertEquals(ProofStatus.REFUTED, nullArray.nonThrowing.status)
        val allocation = analyze("ArrayAllocation", "()[I") {
            visitInsn(Opcodes.ICONST_1); visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT); visitInsn(Opcodes.ARETURN)
        }
        val read = analyze("ArrayRead", "([I)I") {
            visitVarInsn(Opcodes.ALOAD, 0); visitInsn(Opcodes.ICONST_0); visitInsn(Opcodes.IALOAD); visitInsn(Opcodes.IRETURN)
        }
        val referenceArray = analyze("ReferenceArray", "()[Ljava/lang/String;") {
            visitInsn(Opcodes.ICONST_1); visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String"); visitInsn(Opcodes.ARETURN)
        }
        val dimensions = analyze("Dimensions", "()[[I") {
            visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.ICONST_1)
            visitMultiANewArrayInsn("[[I", 2); visitInsn(Opcodes.ARETURN)
        }
        listOf(allocation, read, referenceArray, dimensions).forEach {
            assertEquals(ProofStatus.UNPROVEN, it.nonThrowing.status)
            assertNull(it.semanticKey)
        }
    }

    @Test fun `class literals and unsupported numeric operations fail closed`() {
        val literal = analyze("ClassLiteral", "()Ljava/lang/Class;") {
            visitLdcInsn(Type.getObjectType("java/lang/String")); visitInsn(Opcodes.ARETURN)
        }
        val wideAdd = analyze("WideAdd", "(J)J") {
            visitVarInsn(Opcodes.LLOAD, 0); visitInsn(Opcodes.LCONST_1); visitInsn(Opcodes.LADD); visitInsn(Opcodes.LRETURN)
        }
        listOf(literal, wideAdd).forEach {
            assertEquals(ProofStatus.UNPROVEN, it.effectFree.status)
            assertNull(it.semanticKey)
        }
    }

    @Test fun `exhausted analysis budget does not manufacture a termination certificate`() {
        val result = analyze("LargeStraightLine", "()I") {
            repeat(10001) { visitInsn(Opcodes.NOP) }
            visitInsn(Opcodes.ICONST_0); visitInsn(Opcodes.IRETURN)
        }
        assertEquals(ProofStatus.UNPROVEN, result.terminating.status)
        assertNull(result.semanticKey)
    }

    @Test fun `integer negation and remainder retain overflow and division hazards`() {
        val minimum = analyze("MinimumNegation", "()I") {
            visitLdcInsn(Int.MIN_VALUE); visitInsn(Opcodes.INEG); visitInsn(Opcodes.IRETURN)
        }
        val unchanged = analyze("MinimumConstant", "()I") { visitLdcInsn(Int.MIN_VALUE); visitInsn(Opcodes.IRETURN) }
        assertComplete(minimum)
        assertEquals(unchanged.semanticKey, minimum.semanticKey)
        val symbolic = analyze("SymbolicNegation", "(I)I") {
            visitVarInsn(Opcodes.ILOAD, 0); visitInsn(Opcodes.INEG); visitInsn(Opcodes.IRETURN)
        }
        assertComplete(symbolic)
        listOf(Opcodes.IDIV, Opcodes.IREM).forEach { opcode ->
            val zero = analyze("ZeroDivisor$opcode", "()I") {
                visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.ICONST_0); visitInsn(opcode); visitInsn(Opcodes.IRETURN)
            }
            assertEquals(ProofStatus.REFUTED, zero.nonThrowing.status)
            assertNull(zero.semanticKey)
            val nonzero = analyze("NonzeroDivisor$opcode", "(I)I") {
                visitVarInsn(Opcodes.ILOAD, 0); visitInsn(Opcodes.ICONST_2); visitInsn(opcode); visitInsn(Opcodes.IRETURN)
            }
            assertComplete(nonzero)
        }
    }

    @Test fun `each compact integer constant equals its full constant encoding`() {
        (-1..5).forEach { value ->
            val compact = analyze("Compact${value + 1}", "()I") {
                visitInsn(Opcodes.ICONST_0 + value); visitInsn(Opcodes.IRETURN)
            }
            val full = analyze("Full${value + 1}", "()I") { visitLdcInsn(value); visitInsn(Opcodes.IRETURN) }
            assertComplete(compact)
            assertEquals(full.semanticKey, compact.semanticKey)
        }
    }

    @Test fun `known conditional edges preserve a precise return while retaining the unused branch`() {
        listOf(0, 1).forEach { selector ->
            val result = analyze("KnownBranch$selector", "()I") {
                val target = Label()
                visitLdcInsn(selector); visitJumpInsn(Opcodes.IFEQ, target)
                visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.IRETURN)
                visitLabel(target); visitInsn(Opcodes.ICONST_2); visitInsn(Opcodes.IRETURN)
            }
            assertComplete(result)
            val key = assertNotNull(result.semanticKey)
            val expected = if (selector == 0) 2 else 1
            assertEquals("return:I:int:$expected", key.substringBefore('|'))
            assertNotEquals("[]", key.substringAfter("|residuals:"))
        }
    }

    @Test fun `explicit throw has no normal return or totality key`() {
        val result = analyze("ExplicitThrow", "()I") { visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ATHROW) }
        assertEquals(ProofStatus.REFUTED, result.nonThrowing.status)
        assertNull(result.semanticKey)
    }

    private fun switchSummary(name: String, table: Boolean, selector: Int?) = analyze(name, "(I)I") {
        val first = Label()
        val second = Label()
        val otherwise = Label()
        if (selector == null) visitVarInsn(Opcodes.ILOAD, 0) else visitLdcInsn(selector)
        if (table) visitTableSwitchInsn(1, 2, otherwise, first, second)
        else visitLookupSwitchInsn(otherwise, intArrayOf(1, 2), arrayOf(first, second))
        visitLabel(first); visitIntInsn(Opcodes.BIPUSH, 10); visitInsn(Opcodes.IRETURN)
        visitLabel(second); visitIntInsn(Opcodes.BIPUSH, 20); visitInsn(Opcodes.IRETURN)
        visitLabel(otherwise); visitIntInsn(Opcodes.BIPUSH, 30); visitInsn(Opcodes.IRETURN)
    }

    private fun analyze(name: String, descriptor: String, body: MethodVisitor.() -> Unit): MethodSafetySummary {
        val classes = Files.createDirectory(temporary.resolve(name))
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", descriptor, null, null)
        method.visitCode()
        method.body()
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        Files.write(classes.resolve("$name.class"), writer.toByteArray())
        return StaticSafetyAnalysis(classes, emptyList()).use { it.summaries().getValue("$name.value$descriptor") }
    }

    private fun assertComplete(summary: MethodSafetySummary) {
        assertEquals(ProofStatus.PROVEN, summary.effectFree.status, summary.identity)
        assertEquals(ProofStatus.PROVEN, summary.nonThrowing.status, summary.identity)
        assertEquals(ProofStatus.PROVEN, summary.terminating.status, summary.identity)
        assertNotNull(summary.semanticKey, summary.identity)
    }
}
