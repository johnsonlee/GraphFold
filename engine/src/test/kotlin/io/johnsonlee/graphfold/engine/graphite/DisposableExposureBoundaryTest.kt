package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.sootup.ExposureBranchKey
import io.johnsonlee.graphfold.engine.bytecode.sootup.disposableExposureStatements
import io.johnsonlee.graphfold.engine.bytecode.sootup.exposureBranchKey

import org.junit.jupiter.api.Test
import sootup.core.jimple.basic.SimpleStmtPositionInfo
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.FloatConstant
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.NullConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.AbstractConditionExpr
import sootup.core.jimple.common.expr.JAddExpr
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JGeExpr
import sootup.core.jimple.common.expr.JNeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.core.types.PrimitiveType
import sootup.java.core.JavaIdentifierFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Defensive folded-IR contract tests supplement the executable source goldens. */
class DisposableExposureBoundaryTest {
    private val types = JavaIdentifierFactory.getInstance()
    private val position = SimpleStmtPositionInfo(1)
    private val integer = Local("value", PrimitiveType.getInt())
    private val reference = Local("context", types.getClassType("java.lang.Object"))
    private val zero = IntConstant.getInstance(0)
    private val nullValue = NullConstant.getInstance()
    private val signature = types.getMethodSignature("Exposure", "quote", "void", emptyList<String>())

    @Test fun `missing or ambiguous post-fold branch identity cannot inherit authorization`() {
        val branch = JIfStmt(JEqExpr(integer, zero), position)
        val body = body(branch)
        val key = requireNotNull(exposureBranchKey(branch))
        assertTrue(normalize(body, setOf(key.copy(position = "missing"))).isEmpty())
        val duplicate = JIfStmt(JEqExpr(integer, zero), position)
        assertTrue(normalize(body(branch, duplicate = duplicate), setOf(key)).isEmpty())
    }

    @Test fun `exceptional continuation and nonlocal writes survive normalization`() {
        val branch = JIfStmt(JEqExpr(integer, zero), position)
        assertTrue(normalize(body(branch, exceptional = true), setOf(requireNotNull(exposureBranchKey(branch)))).isEmpty())
        val field = JStaticFieldRef(types.getFieldSignature("counter", types.getClassType("Exposure"), PrimitiveType.getInt()))
        val store = JAssignStmt(field, zero, position)
        val removed = normalize(body(branch, store), setOf(requireNotNull(exposureBranchKey(branch))))
        assertEquals(setOf(branch), removed)
    }

    @Test fun `discardable producers are limited to seeded total values`() {
        val text = StringConstant("literal", types.getClassType("java.lang.String"))
        for (value in listOf<Value>(text, nullValue, reference, JAddExpr(zero, IntConstant.getInstance(1)))) {
            val target = if (value is JAddExpr) integer else Local("copy", reference.type)
            val condition = if (target === integer) JEqExpr(integer, zero) else JEqExpr(target, nullValue)
            val branch = JIfStmt(condition, position)
            val assignment = JAssignStmt(target, value, position)
            assertEquals(setOf(branch, assignment), normalize(body(branch, assignment), setOf(requireNotNull(exposureBranchKey(branch)))))
        }
    }

    @Test fun `seeded producer remains when fallback stores its value`() {
        val assignment = JAssignStmt(integer, zero, position)
        val branch = JIfStmt(JEqExpr(integer, zero), position)
        val field = JStaticFieldRef(types.getFieldSignature("counter", types.getClassType("Exposure"), PrimitiveType.getInt()))
        val store = JAssignStmt(field, integer, position)
        val exit = JReturnVoidStmt(position)
        val builder = Body.builder().setMethodSignature(signature).setModifiers(setOf(MethodModifier.STATIC))
            .setLocals(setOf(integer))
        builder.controlFlowGraph.putEdge(assignment, branch)
        builder.controlFlowGraph.putEdge(store, exit)
        builder.controlFlowGraph.setEdges(branch, listOf(store, store))
        builder.controlFlowGraph.setStartingStmt(assignment)
        assertEquals(setOf(branch), normalize(builder.build(), setOf(requireNotNull(exposureBranchKey(branch)))))
    }

    @Test fun `foreign initialization and unsupported helper arguments remain observable`() {
        val foreign = types.getMethodSignature("Foreign", "exposed", "int", emptyList<String>())
        val own = types.getMethodSignature("Exposure", "exposed", "int", listOf("java.lang.Object"))
        val calls = listOf(JStaticInvokeExpr(foreign, emptyList()), JStaticInvokeExpr(own, listOf(reference)))
        for (call in calls) {
            val branch = JIfStmt(JEqExpr(integer, zero), position)
            val assignment = JAssignStmt(integer, call, position)
            val methods = setOf("Foreign.exposed()I", "Exposure.exposed(Ljava/lang/Object;)I")
            val removed = normalize(body(branch, assignment), setOf(requireNotNull(exposureBranchKey(branch))), methods)
            assertEquals(setOf(branch), removed)
        }
    }

    @Test fun `unsupported numeric and reference operand shapes never grant totality`() {
        val floating = Local("floating", PrimitiveType.getFloat())
        val floatZero = FloatConstant.getInstance(0f)
        val text = StringConstant("literal", types.getClassType("java.lang.String"))
        val conditions = listOf<AbstractConditionExpr>(
            JGeExpr(floating, floatZero), JEqExpr(floating, floatZero), JNeExpr(nullValue, text),
            // Malformed mixed operands are defensive rejection cases, not executable production claims.
            JEqExpr(nullValue, integer), JEqExpr(integer, floatZero),
        )
        conditions.forEach { condition ->
            val branch = JIfStmt(condition, position)
            assertTrue(normalize(body(branch), setOf(requireNotNull(exposureBranchKey(branch)))).isEmpty())
        }
        val assignment = JAssignStmt(integer, JAddExpr(zero, floatZero), position)
        val branch = JIfStmt(JEqExpr(integer, zero), position)
        assertFalse(assignment in normalize(body(branch, assignment), setOf(requireNotNull(exposureBranchKey(branch)))))
    }

    private fun normalize(body: Body, keys: Set<ExposureBranchKey>, methods: Set<String> = emptySet()): Set<Stmt> =
        disposableExposureStatements(body, "Exposure.quote()V", methods, keys)

    private fun body(
        branch: JIfStmt, assignment: JAssignStmt? = null, duplicate: JIfStmt? = null, exceptional: Boolean = false,
    ): Body {
        val builder = Body.builder().setMethodSignature(signature).setModifiers(setOf(MethodModifier.STATIC))
        val exit = JReturnVoidStmt(position)
        val next = duplicate ?: exit
        assignment?.let { builder.controlFlowGraph.putEdge(it, branch) }
        builder.controlFlowGraph.setEdges(branch, listOf(next, next))
        duplicate?.let { builder.controlFlowGraph.setEdges(it, listOf(exit, exit)) }
        builder.controlFlowGraph.setStartingStmt(assignment ?: branch)
        if (exceptional) builder.controlFlowGraph.addExceptionalEdge(branch, types.getClassType("java.lang.Exception"), exit)
        val locals = (listOfNotNull<Stmt>(assignment, branch, duplicate)).flatMap { it.usesAndDefs }.filterIsInstance<Local>().toSet()
        return builder.setLocals(locals).build()
    }
}
