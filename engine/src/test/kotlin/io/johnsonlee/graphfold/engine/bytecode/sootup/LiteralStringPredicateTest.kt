package io.johnsonlee.graphfold.engine.bytecode.sootup

import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.expr.JVirtualInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.jimple.common.stmt.FallsThroughStmt
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.java.core.JavaIdentifierFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteralStringPredicateTest {
    @TempDir lateinit var directory: Path
    private val types = JavaIdentifierFactory.getInstance()
    private val string = types.getClassType("java.lang.String")
    private val receiver = Local("receiver", string)
    private val alias = Local("alias", string)
    private val literal = StringConstant("Android", string)
    private val signature = types.getMethodSignature("java.lang.String", "equals", "boolean", listOf("java.lang.Object"))
    private val call = JVirtualInvokeExpr(receiver, signature, listOf(literal))

    @Test fun `real javac IR distinguishes literals from unknown fields allocations and conditional receiver assignments`() {
        val source = directory.resolve("Predicates.java")
        Files.writeString(source, """
            public class Predicates {
                static String field = "Android";
                static boolean literal(String value) { return "Android".equals(value); }
                static boolean alias(String value) { String a="Android"; String b=a; return b.equals(value); }
                static boolean unknown(String receiver, String value) { return receiver.equals(value); }
                static boolean field(String value) { return field.equals(value); }
                static boolean allocated(String value) { return new String("Android").equals(value); }
                static boolean reassigned(boolean choose, String receiver, String value) {
                    if (choose) receiver="Android"; return receiver.equals(value);
                }
            }
        """.trimIndent())
        val classes = Files.createDirectory(directory.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val observed = mutableMapOf<String, Boolean>()
        JavaProjectLoader(LoaderConfig(), false).loadObserved(classes) { observation ->
            observation.snapshot?.body?.let { body ->
                predicates(body).singleOrNull()?.let { accepted -> observed[observation.evidence.method.name] = accepted }
            }
        }
        assertEquals(mapOf("literal" to true, "alias" to true, "unknown" to false,
            "field" to false, "allocated" to false, "reassigned" to false), observed)
    }

    @Test fun `only the exact virtual String equals contract may erase a predicate`() {
        val body = body(listOf(receiver to literal))
        assertTrue(literalStringPredicate(call, body))
        val alternatives = listOf(
            types.getMethodSignature("java.lang.Object", "equals", "boolean", listOf("java.lang.Object")),
            types.getMethodSignature("java.lang.String", "equalsIgnoreCase", "boolean", listOf("java.lang.String")),
            types.getMethodSignature("java.lang.String", "equals", "boolean", listOf("java.lang.String")),
            types.getMethodSignature("java.lang.String", "equals", "int", listOf("java.lang.Object")),
        )
        alternatives.forEach { assertFalse(literalStringPredicate(call.withMethodSignature(it), body)) }
        assertFalse(literalStringPredicate(call.withArgs(emptyList()), body))
        assertFalse(literalStringPredicate(JStaticInvokeExpr(signature, call.args), body))
        assertFalse(literalStringPredicate(IntConstant.getInstance(1), body))
    }

    @Test fun `untrusted cyclic or multiple IR definitions never become literal receiver evidence`() {
        assertTrue(literalStringPredicate(call, body(listOf(receiver to alias, alias to literal))))
        assertFalse(literalStringPredicate(call, body(emptyList())))
        assertFalse(literalStringPredicate(call, body(listOf(receiver to alias, alias to receiver))))
        assertFalse(literalStringPredicate(call, body(listOf(receiver to literal, receiver to StringConstant("iOS", string)))))
        val field = JStaticFieldRef(types.getFieldSignature("field", types.getClassType("Predicates"), string))
        assertFalse(literalStringPredicate(call, body(listOf(receiver to field))))
    }

    private fun predicates(body: Body): List<Boolean> = body.stmts.filter { it.isInvokableStmt }.mapNotNull { statement ->
        statement.asInvokableStmt().invokeExpr.orElse(null)?.takeIf { it.methodSignature.name == "equals" }
    }.map { literalStringPredicate(it, body) }

    private fun body(definitions: List<Pair<Local, Value>>): Body {
        val position = StmtPositionInfo.getNoStmtPositionInfo()
        val statements: List<Stmt> = definitions.map { (local, value) -> JAssignStmt(local, value, position) } +
            listOf(JInvokeStmt(call, position), JReturnVoidStmt(position))
        val builder = Body.builder().setMethodSignature(types.getMethodSignature("Contract", "probe", "void", emptyList()))
            .setModifiers(setOf(MethodModifier.STATIC)).setLocals(setOf(receiver, alias))
        statements.zipWithNext().forEach { (left, right) -> builder.controlFlowGraph.putEdge(left as FallsThroughStmt, right) }
        builder.controlFlowGraph.setStartingStmt(statements.first())
        return builder.build()
    }
}
