package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.jvmMethodDescriptor
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.sootup.FoldedMethodObservation
import io.johnsonlee.graphite.sootup.MethodObservationEvidence
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.core.util.printer.NormalStmtPrinter
import java.util.IdentityHashMap

/**
 * Invocation-local folded-body consumer. Receipts are retained for final completeness reconciliation;
 * owned bodies are reduced to immutable comparison/effect facts during the callback, never retained.
 * The caller must discard this sink if Graphite's observed load fails.
 */
internal class SnapshotSink(
    private val ignoredFields: Set<String> = emptySet(),
    private val applicationOwners: Set<String> = emptySet(),
    private val dependencyResources: Boolean = false,
    private val trustedIntrinsics: Boolean = false,
) {
    val observations = linkedMapOf<String, MethodObservationEvidence>()
    val methods = linkedMapOf<String, MethodSnapshot>()
    val resourceCalls = linkedMapOf<String, Set<String>>()
    val resourceInitializers = linkedMapOf<String, Set<String>>()

    /** Only direct calls inspected by the existing resource model can discharge a resource dispatch gap. */
    fun modelsResourceCall(site: CallSiteNode): Boolean {
        val method = methods[site.caller.identity]
        val unmodeled = resourceCalls[site.caller.identity] ?: return false
        return (site.ordinal ?: -1) >= 0 && site.callee.identity in method?.calls.orEmpty() && site.callee.identity !in unmodeled
    }

    fun capture(observation: FoldedMethodObservation) {
        val evidence = observation.evidence
        val identity = evidence.method.identity
        check(identity !in observations) { "Duplicate method observation $identity" }
        observations[identity] = evidence
        val modifiers = evidence.modifiers ?: return
        val body = observation.snapshot?.body
        val available = when (evidence.status) {
            AnalysisStatus.ANALYZED, AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW -> body != null
            AnalysisStatus.ABSTRACT, AnalysisStatus.NATIVE, AnalysisStatus.NO_BODY -> body == null
            else -> false
        }
        if (available) captureMethod(identity, modifiers.toSet(), body)
    }

    private fun captureMethod(identity: String, modifiers: Set<MethodModifier>, body: Body?) {
        val canonicalModifiers = modifiers.map { it.name }.sorted().joinToString(",")
        // Dispatch evidence is reconciled in ordinary source analysis too. Keep the exact
        // resource contracts available independently of dependency-body expansion.
        resourceCalls[identity] = body?.let { dependencyResourceCalls(it, trustedIntrinsics) }.orEmpty()
        if (dependencyResources) {
            resourceInitializers[identity] = body?.let(::dependencyResourceInitializers).orEmpty()
        }
        val calls = body?.stmts.orEmpty().mapNotNull { stmt ->
            if (!stmt.isInvokableStmt) null else stmt.asInvokableStmt().invokeExpr.orElse(null)?.methodSignature
        }.map { signature ->
            "${signature.declClassType.fullyQualifiedName}.${signature.name}" +
                jvmMethodDescriptor(signature.parameterTypes.map { it.toString() }, signature.type.toString())
        }.toSet()
        val dynamicCalls = body?.stmts.orEmpty().filter { it.isInvokableStmt }.mapNotNull {
            it.asInvokableStmt().invokeExpr.orElse(null) as? JDynamicInvokeExpr
        }.map { call ->
            val signature = call.methodSignature
            signature.name + jvmMethodDescriptor(signature.parameterTypes.map { it.toString() }, signature.type.toString())
        }.toSet()
        val lines = body?.stmts.orEmpty().map { it.positionInfo.stmtPosition.firstLine }.filter { it > 0 }.toSet()
        val canonical = canonicalModifiers + "\n" + if (body == null) "NO_BODY" else canonicalBody(body)
        val references = mutableListOf<String>()
        val normalized = canonicalModifiers + "\n" +
            if (body == null) "NO_BODY" else canonicalBody(body, references, ignoredFields, identity)
        methods[identity] = MethodSnapshot(identity, canonical, calls, modifiers, lines, normalized, references,
            body?.let { resourceReads(it, applicationOwners) }.orEmpty(), dynamicCalls)
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
    // Reject all other lvalues, including any future field-reference subtype, before applying a receiver proof.
    return when (val field = assignment.leftOp) {
        is sootup.core.jimple.common.ref.JStaticFieldRef -> ignorableStore(field, stmt, body, ignoredFields, identity) &&
            identity.substringBefore('(').endsWith(".<clinit>")
        is sootup.core.jimple.common.ref.JInstanceFieldRef -> ignorableStore(field, stmt, body, ignoredFields, identity) &&
            identity.substringBefore('(').endsWith(".<init>") && field.base == body.thisLocal
        else -> false
    }
}

private fun ignorableStore(
    field: sootup.core.jimple.common.ref.JFieldRef, stmt: Stmt, body: Body, ignoredFields: Set<String>, identity: String,
): Boolean {
    val constant = stmt.asJAssignStmt().rightOp
    val pure = constant is sootup.core.jimple.common.constant.NumericConstant<*> ||
        constant is sootup.core.jimple.common.constant.StringConstant || constant is sootup.core.jimple.common.constant.NullConstant
    val owner = identity.substringBefore('(').substringBeforeLast('.')
    return fieldIdentity(field.fieldSignature) in ignoredFields && pure &&
        body.controlFlowGraph.successors(stmt).size == 1 && field.fieldSignature.declClassType.fullyQualifiedName == owner
}
