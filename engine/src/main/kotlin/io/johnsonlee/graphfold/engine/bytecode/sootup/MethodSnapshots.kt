package io.johnsonlee.graphfold.engine.bytecode.sootup


import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphite.core.jvmMethodDescriptor
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.core.util.printer.NormalStmtPrinter
import java.util.IdentityHashMap

/** Reduce a folded SootUp body to static comparison evidence without retaining the body. */
internal class MethodSnapshots(
    private val applicationOwners: Set<String>,
    private val dependencyResources: Boolean,
    private val trustedIntrinsics: Boolean,
    private val primitiveMethods: Set<String>,
) {
    val methods = linkedMapOf<String, MethodSnapshot>()
    val discardedCalls = linkedMapOf<String, Set<String>>()
    val resourceCalls = linkedMapOf<String, Set<String>>()
    val resourceInitializers = linkedMapOf<String, Set<String>>()

    fun capture(identity: String, modifiers: Set<MethodModifier>, body: Body?, authorized: Set<ExposureBranchKey>) {
        val canonicalModifiers = modifiers.map { it.name }.sorted().joinToString(",")
        // Dispatch evidence is reconciled in ordinary source analysis too. Keep the exact
        // resource contracts available independently of dependency-body expansion.
        resourceCalls[identity] = body?.let { dependencyResourceCalls(it, trustedIntrinsics) }.orEmpty()
        if (dependencyResources) {
            resourceInitializers[identity] = body?.let(::dependencyResourceInitializers).orEmpty()
        }
        val disposable = body?.let {
            disposableExposureStatements(it, identity, primitiveMethods, authorized)
        }.orEmpty()
        val calls = body?.stmts.orEmpty().filterNot { it in disposable }.mapNotNull(::invokedIdentity).toSet()
        discardedCalls[identity] = disposable.mapNotNull(::invokedIdentity).toSet() - calls
        val dynamicCalls = body?.stmts.orEmpty().filter { it.isInvokableStmt }.mapNotNull {
            it.asInvokableStmt().invokeExpr.orElse(null) as? JDynamicInvokeExpr
        }.map { call ->
            val signature = call.methodSignature
            signature.name + jvmMethodDescriptor(signature.parameterTypes.map { it.toString() }, signature.type.toString())
        }.toSet()
        val lines = body?.stmts.orEmpty().map { it.positionInfo.stmtPosition.firstLine }.filter { it > 0 }.toSet()
        val canonical = canonicalModifiers + "\n" + if (body == null) "NO_BODY" else canonicalBody(body, disposable = disposable)
        val references = mutableListOf<String>()
        val normalized = canonicalModifiers + "\n" +
            if (body == null) "NO_BODY" else canonicalBody(body, references, disposable)
        methods[identity] = MethodSnapshot(identity, canonical, calls, modifiers, lines, normalized, references,
            body?.let { resourceReads(it, applicationOwners) }.orEmpty(), dynamicCalls,
            body?.let(::survivingLambdaTargets).orEmpty())
    }
}

internal fun canonicalBody(
    body: Body,
    references: MutableList<String>? = null,
    disposable: Set<Stmt> = emptySet(),
): String {
    val removed = disposable
    val statements = body.stmts.filterNot { it in removed }
    fun target(stmt: Stmt): Stmt = if (stmt in removed) target(body.controlFlowGraph.successors(stmt).first()) else stmt
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

private fun invokedIdentity(statement: Stmt): String? = statement.takeIf { it.isInvokableStmt }
    ?.asInvokableStmt()?.invokeExpr?.orElse(null)?.methodSignature?.let { signature ->
        "${signature.declClassType.fullyQualifiedName}.${signature.name}" +
            jvmMethodDescriptor(signature.parameterTypes.map { it.toString() }, signature.type.toString())
    }
