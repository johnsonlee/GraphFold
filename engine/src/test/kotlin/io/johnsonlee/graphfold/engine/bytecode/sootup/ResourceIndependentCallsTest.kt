package io.johnsonlee.graphfold.engine.bytecode.sootup

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.ClassConstant
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.expr.JVirtualInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.java.core.JavaIdentifierFactory

class ResourceIndependentCallsTest {
    private val types = JavaIdentifierFactory.getInstance()

    @Test fun `concat requires the exact platform bootstrap and value only arguments`() {
        val bootstrap = types.getMethodSignature("java.lang.invoke.StringConcatFactory", "makeConcat", "java.lang.invoke.CallSite",
            listOf("java.lang.invoke.MethodHandles\$Lookup", "java.lang.String", "java.lang.invoke.MethodType"))
        val target = types.getMethodSignature(JDynamicInvokeExpr.INVOKEDYNAMIC_DUMMY_CLASS_NAME, "makeConcat", "java.lang.String",
            listOf("int"))
        val call = JDynamicInvokeExpr(bootstrap, emptyList(), target, STATIC_HANDLE, listOf(IntConstant.getInstance(1)))
        val model = ResourceIndependentCalls(emptyMap())
        assertTrue(model.matches(call))
        val numericConstant = call.withBootstrapArgs(listOf(IntConstant.getInstance(1)))
        assertTrue(model.matches(numericConstant))
        val classConstant = ClassConstant("Ljava/lang/String;", types.getClassType("java.lang.Class"))
        assertFalse(model.matches(call.withBootstrapArgs(listOf(classConstant))))
        val otherBootstrap = types.getMethodSignature("ApplicationBootstrap", "makeConcat", "java.lang.invoke.CallSite",
            bootstrap.parameterTypes.map { it.toString() })
        assertFalse(model.matches(call.withBootstrapMethodSignature(otherBootstrap)))
        val wrongSignature = types.getMethodSignature("java.lang.invoke.StringConcatFactory", "unknown", "java.lang.invoke.CallSite",
            bootstrap.parameterTypes.map { it.toString() })
        assertFalse(model.matches(call.withBootstrapMethodSignature(wrongSignature)))
        assertFalse(model.matches(JDynamicInvokeExpr(bootstrap, emptyList(), target, VIRTUAL_HANDLE, listOf(IntConstant.getInstance(1)))))
        val objectTarget = types.getMethodSignature(JDynamicInvokeExpr.INVOKEDYNAMIC_DUMMY_CLASS_NAME, "makeConcat", "java.lang.String",
            listOf("java.lang.Object"))
        // SootUp 3.0.1 withMethodSignature ignores its argument for dynamic calls; construct the distinct call explicitly.
        val objectCall = JDynamicInvokeExpr(bootstrap, emptyList(), objectTarget, STATIC_HANDLE,
            listOf(Local("payload", types.getClassType("java.lang.Object"))))
        assertFalse(model.matches(objectCall))
        val objectReturn = types.getMethodSignature(JDynamicInvokeExpr.INVOKEDYNAMIC_DUMMY_CLASS_NAME, "makeConcat", "java.lang.Object",
            listOf("int"))
        assertFalse(model.matches(JDynamicInvokeExpr(bootstrap, emptyList(), objectReturn, STATIC_HANDLE,
            listOf(IntConstant.getInstance(1)))))
    }

    @Test fun `standard output follows unique aliases but rejects cycles unknown receivers and callbacks`() {
        val stream = Local("out", types.getClassType("java.io.PrintStream"))
        val alias = Local("alias", stream.type)
        val system = types.getClassType("java.lang.System")
        val field = JStaticFieldRef(types.getFieldSignature("out", system, stream.type))
        val signature = types.getMethodSignature("java.io.PrintStream", "println", "void", listOf("java.lang.String"))
        val text = StringConstant("hello", types.getClassType("java.lang.String"))
        val call = JVirtualInvokeExpr(alias, signature, listOf(text))
        assertTrue(ResourceIndependentCalls(mapOf(alias to stream, stream to field)).matches(call))
        assertFalse(ResourceIndependentCalls(mapOf(alias to stream, stream to alias)).matches(call))
        assertFalse(ResourceIndependentCalls(mapOf<Value, Value?>(alias to null)).matches(call))
        assertFalse(ResourceIndependentCalls(emptyMap()).matches(call))
        assertFalse(ResourceIndependentCalls(emptyMap()).matches(JStaticInvokeExpr(signature, listOf(text))))
        listOf("custom" to system, "out" to types.getClassType("Application")).forEach { (name, owner) ->
            val other = JStaticFieldRef(types.getFieldSignature(name, owner, stream.type))
            assertFalse(ResourceIndependentCalls(mapOf(alias to other)).matches(call))
        }
        val wrongType = JStaticFieldRef(types.getFieldSignature("out", system, "java.lang.Object"))
        assertFalse(ResourceIndependentCalls(mapOf(alias to wrongType)).matches(call))
        val objectSignature = types.getMethodSignature("java.io.PrintStream", "println", "void", listOf("java.lang.Object"))
        val model = ResourceIndependentCalls(mapOf(alias to field))
        assertTrue(model.matches(call.withMethodSignature(objectSignature)))
        assertFalse(model.matches(call.withMethodSignature(objectSignature).withArgs(emptyList())))
        val applicationObject = Local("object", types.getClassType("Application"))
        assertFalse(model.matches(call.withMethodSignature(objectSignature).withArgs(listOf(applicationObject))))
    }

    companion object {
        private const val STATIC_HANDLE = 6
        private const val VIRTUAL_HANDLE = 5
    }
}
