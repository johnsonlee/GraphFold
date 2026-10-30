package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.jvmMethodDescriptor
import io.johnsonlee.graphite.sootup.GraphiteContext
import io.johnsonlee.graphite.sootup.GraphiteExtension
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.Body
import sootup.core.model.SootClass
import sootup.core.util.printer.NormalStmtPrinter
import java.util.IdentityHashMap

/** ServiceLoader entry point. All capture state belongs to a single loader invocation. */
class SnapshotExtension : GraphiteExtension {
    override fun visit(sootClass: SootClass, context: GraphiteContext) {
        current.get()?.capture(sootClass, context)
    }

    companion object {
        private val current = ThreadLocal<SnapshotSink>()

        internal fun <T> capture(sink: SnapshotSink, action: () -> T): T {
            check(current.get() == null) { "Nested Graphite capture is not supported" }
            current.set(sink)
            return try { action() } finally { current.remove() }
        }
    }
}

internal class SnapshotSink(private val ignoredFields: Set<String> = emptySet()) {
    val methods = linkedMapOf<String, MethodSnapshot>()
    val declarations = linkedMapOf<String, String>()

    fun capture(type: SootClass, context: GraphiteContext) {
        declarations[type.name] = listOf(
            type.modifiers.map { it.name }.sorted().toString(),
            type.superclass.map { it.toString() }.orElse(""),
            type.interfaces.map { it.toString() }.sorted().toString(),
            type.fields.map { "${it.signature}:${it.modifiers.map { modifier -> modifier.name }.sorted()}" }.sorted().toString(),
        ).joinToString("\n")
        for (method in type.methods) {
            val identity = context.toMethodDescriptor(method).identity
            val modifiers = method.modifiers.map { it.name }.toSortedSet()
            val body = if (method.hasBody()) method.body else null
            val calls = body?.stmts.orEmpty().mapNotNull { stmt ->
                if (!stmt.isInvokableStmt) null else stmt.asInvokableStmt().invokeExpr.orElse(null)?.methodSignature
            }.map { signature ->
                "${signature.declClassType.fullyQualifiedName}.${signature.name}" +
                    jvmMethodDescriptor(signature.parameterTypes.map { it.toString() }, signature.type.toString())
            }.toSet()
            val lines = body?.stmts.orEmpty().map { it.positionInfo.stmtPosition.firstLine }.filter { it > 0 }.toSet()
            val canonical = modifiers.joinToString(",") + "\n" + (body?.let { canonicalBody(it) } ?: "NO_BODY")
            val references = mutableListOf<String>()
            val normalized = modifiers.joinToString(",") + "\n" +
                (body?.let { canonicalBody(it, references, ignoredFields, identity) } ?: "NO_BODY")
            check(methods.put(identity, MethodSnapshot(identity, canonical, calls, modifiers, lines, normalized, references)) == null) {
                "Duplicate method identity $identity"
            }
        }
    }
}

internal fun canonicalBody(
    body: Body,
    references: MutableList<String>? = null,
    ignoredFields: Set<String> = emptySet(),
    identity: String = "",
): String {
    val removed = body.stmts.filter { pureInitializerStore(it, body, ignoredFields, identity) }.toSet()
    val statements = body.stmts.filterNot { it in removed }
    fun target(stmt: Stmt): Stmt = if (stmt in removed) target(body.controlFlowGraph.successors(stmt).single()) else stmt
    val indexes = IdentityHashMap<Stmt, Int>()
    statements.forEachIndexed { index, stmt -> indexes[stmt] = index }
    val locals = linkedMapOf<Local, Int>()
    return statements.joinToString("\n") { stmt ->
        val printer = object : NormalStmtPrinter() {
            init { graph = body.controlFlowGraph }
            override fun local(local: Local) {
                literal("v${locals.getOrPut(local) { locals.size }}:${local.type}")
            }
            override fun methodSignature(signature: sootup.core.signatures.MethodSignature) {
                if (references == null) super.methodSignature(signature) else {
                    literal("@member${references.size}")
                    references.add("${signature.declClassType.fullyQualifiedName}.${signature.name}" +
                        jvmMethodDescriptor(signature.parameterTypes.map { it.toString() }, signature.type.toString()))
                }
            }
            override fun fieldSignature(signature: sootup.core.signatures.FieldSignature) {
                if (references == null) super.fieldSignature(signature) else {
                    literal("@member${references.size}")
                    references.add(fieldIdentity(signature))
                }
            }
            override fun stmtRef(target: Stmt, branchTarget: Boolean) {
                literal("s${indexes.getValue(target(target))}")
            }
        }
        stmt.toString(printer)
        val successors = body.controlFlowGraph.successors(stmt).map { indexes.getValue(target(it)) }
        val exceptions = body.controlFlowGraph.exceptionalSuccessors(stmt).entries
            .map { "${it.key}:${indexes.getValue(target(it.value))}" }
        "${stmt.javaClass.simpleName}|$printer|next=$successors|catch=$exceptions"
    }
}

private fun fieldIdentity(signature: sootup.core.signatures.FieldSignature): String =
    "${signature.declClassType.fullyQualifiedName}.${signature.name}:" +
        jvmMethodDescriptor(emptyList(), signature.type.toString()).removePrefix("()")

/** Only writes on this constructor receiver or this class's static initializer can be erased safely. */
private fun pureInitializerStore(stmt: Stmt, body: Body, ignoredFields: Set<String>, identity: String): Boolean {
    if (!stmt.isJAssignStmt || ignoredFields.isEmpty()) return false
    val assignment = stmt.asJAssignStmt()
    val field = assignment.leftOp as? sootup.core.jimple.common.ref.JFieldRef
    val constant = assignment.rightOp
    val pure = constant is sootup.core.jimple.common.constant.NumericConstant<*> ||
        constant is sootup.core.jimple.common.constant.StringConstant || constant is sootup.core.jimple.common.constant.NullConstant
    val owner = identity.substringBefore('(').substringBeforeLast('.')
    return field != null && fieldIdentity(field.fieldSignature) in ignoredFields && pure &&
        body.controlFlowGraph.successors(stmt).size == 1 && field.fieldSignature.declClassType.fullyQualifiedName == owner &&
        when (field) {
            is sootup.core.jimple.common.ref.JStaticFieldRef -> identity.substringBefore('(').endsWith(".<clinit>")
            is sootup.core.jimple.common.ref.JInstanceFieldRef ->
                identity.substringBefore('(').endsWith(".<init>") && field.base == body.thisLocal
            else -> false
        }
}
