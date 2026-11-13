package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.sootup.survivingLambdaTargets

import org.junit.jupiter.api.Test
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.MethodHandle
import sootup.core.jimple.common.constant.MethodType
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.java.core.JavaIdentifierFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LambdaBootstrapValidationTest {
    private val types = JavaIdentifierFactory.getInstance()
    private val implementation = types.getMethodSignature("Sample", "generated", "void", emptyList<String>())
    private val bootstrap = types.getMethodSignature("java.lang.invoke.LambdaMetafactory", "metafactory",
        "java.lang.invoke.CallSite", listOf("java.lang.invoke.MethodHandles\$Lookup", "java.lang.String",
            "java.lang.invoke.MethodType", "java.lang.invoke.MethodType", "java.lang.invoke.MethodHandle",
            "java.lang.invoke.MethodType"))
    private val target = types.getMethodSignature(JDynamicInvokeExpr.INVOKEDYNAMIC_DUMMY_CLASS_NAME,
        "run", "java.lang.Runnable", emptyList<String>())
    private val methodType = MethodType(implementation.subSignature, types.getClassType("java.lang.invoke.MethodType"))
    private val handle = MethodHandle(implementation, MethodHandle.Kind.REF_INVOKE_STATIC,
        types.getClassType("java.lang.invoke.MethodHandle"))
    private val call = JDynamicInvokeExpr(bootstrap, listOf(methodType, handle, methodType), target, STATIC_HANDLE, emptyList())

    @Test fun `factory ownership requires exact standard bootstrap and correctly typed arguments`() {
        assertEquals(setOf("Sample.generated()V"), targets(call))
        val otherOwner = types.getMethodSignature("ApplicationFactory", bootstrap.name, "java.lang.invoke.CallSite",
            bootstrap.parameterTypes.map { it.toString() })
        val otherName = types.getMethodSignature("java.lang.invoke.LambdaMetafactory", "altMetafactory",
            "java.lang.invoke.CallSite", bootstrap.parameterTypes.map { it.toString() })
        val otherDescriptor = types.getMethodSignature("java.lang.invoke.LambdaMetafactory", "metafactory",
            "java.lang.Object", bootstrap.parameterTypes.map { it.toString() })
        val integer = IntConstant.getInstance(1)
        val invalid = listOf(
            call.withBootstrapMethodSignature(otherOwner), call.withBootstrapMethodSignature(otherName),
            call.withBootstrapMethodSignature(otherDescriptor), call.withBootstrapArgs(emptyList()),
            call.withBootstrapArgs(listOf(integer, handle, methodType)),
            call.withBootstrapArgs(listOf(methodType, handle, integer)),
            call.withBootstrapArgs(listOf(methodType, integer, methodType)),
            JDynamicInvokeExpr(bootstrap, call.bootstrapArgs, target, VIRTUAL_HANDLE, emptyList()),
        )
        invalid.forEach { assertTrue(targets(it).isEmpty()) }
        val field = types.getFieldSignature("field", types.getClassType("Sample"), types.getClassType("java.lang.String"))
        val fieldHandle = MethodHandle(field, MethodHandle.Kind.REF_GET_FIELD, handle.type)
        assertTrue(targets(call.withBootstrapArgs(listOf(methodType, fieldHandle, methodType))).isEmpty())
        val constructor = MethodHandle(implementation, MethodHandle.Kind.REF_INVOKE_CONSTRUCTOR, handle.type)
        assertTrue(targets(call.withBootstrapArgs(listOf(methodType, constructor, methodType))).isEmpty())
    }

    private fun targets(invoke: JDynamicInvokeExpr): Set<String> {
        val position = StmtPositionInfo.getNoStmtPositionInfo()
        val statement = JInvokeStmt(invoke, position)
        val builder = Body.builder().setMethodSignature(implementation).setModifiers(setOf(MethodModifier.STATIC))
        builder.controlFlowGraph.putEdge(statement, JReturnVoidStmt(position))
        builder.controlFlowGraph.setStartingStmt(statement)
        return survivingLambdaTargets(builder.build())
    }

    companion object {
        private const val STATIC_HANDLE = 6
        private const val VIRTUAL_HANDLE = 5
    }
}
