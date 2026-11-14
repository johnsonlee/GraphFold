package io.johnsonlee.graphfold.engine.bytecode.sootup

import io.johnsonlee.graphite.core.jvmMethodDescriptor
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.constant.NullConstant
import sootup.core.jimple.common.expr.AbstractBinopExpr
import sootup.core.jimple.common.expr.AbstractConditionExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JNeExpr
import sootup.core.types.ReferenceType
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.model.Body

/** Erase only dead total computations after folding makes both conditional successors identical. */
internal fun disposableExposureStatements(
    body: Body, identity: String, primitiveMethods: Set<String>, authorized: Set<ExposureBranchKey>,
): Set<Stmt> {
    val removed = linkedSetOf<Stmt>()
    val producers = mutableSetOf<Local>()
    val unique = body.stmts.mapNotNull(::exposureBranchKey).groupingBy { it }.eachCount()
    val allowed = authorized.filter { unique[it] == 1 }.toSet()
    fun target(statement: Stmt): Stmt = if (statement in removed) target(body.controlFlowGraph.successors(statement).first())
        else statement
    var changed: Boolean
    do {
        changed = false
        val retained = body.stmts.filterNot { it in removed }
        retained.forEach { statement ->
            val successors = body.controlFlowGraph.successors(statement).map(::target).distinct()
            val noException = body.controlFlowGraph.exceptionalSuccessors(statement).isEmpty()
            val redundant = exposureBranchKey(statement) in allowed && successors.size == 1 &&
                totalExposureCondition(statement.asJIfStmt().condition)
            val assignment = unusedExposureAssignment(statement, retained, producers)
            val disposable = assignment != null && disposableValue(assignment.rightOp, identity, primitiveMethods, body)
            val dispensable = redundant || disposable
            if (noException && successors.size == 1 && dispensable) {
                producers.addAll(statement.uses.filterIsInstance<Local>())
                removed.add(statement)
                changed = true
            }
        }
    } while (changed)
    return removed
}

private fun unusedExposureAssignment(statement: Stmt, retained: List<Stmt>, producers: Set<Local>): JAssignStmt? {
    if (!statement.isJAssignStmt) return null
    val assignment = statement.asJAssignStmt()
    val used = retained.any { it !== statement && assignment.leftOp in it.uses }
    return assignment.takeIf { it.leftOp is Local && it.leftOp in producers && !used }
}

private fun disposableValue(value: Value, caller: String, primitiveMethods: Set<String>, body: Body): Boolean {
    val localValue = value is Local || value is StringConstant || value is NullConstant
    return localValue || totalPrimitive(value) || literalStringPredicate(value, body) ||
        disposablePrimitiveCall(value, caller, primitiveMethods)
}

private fun disposablePrimitiveCall(value: Value, caller: String, primitiveMethods: Set<String>): Boolean {
    val call = value as? JStaticInvokeExpr ?: return false
    val signature = call.methodSignature
    val owner = caller.substringBefore('(').substringBeforeLast('.')
    val method = "${signature.declClassType.fullyQualifiedName}.${signature.name}" +
        jvmMethodDescriptor(signature.parameterTypes.map { it.toString() }, signature.type.toString())
    return signature.declClassType.fullyQualifiedName == owner && method in primitiveMethods && call.args.all(::totalPrimitive)
}

/** JVM reference identity tests neither dereference operands nor dispatch application equality methods. */
private fun totalExposureCondition(value: AbstractConditionExpr): Boolean = totalPrimitive(value) ||
    ((value is JEqExpr || value is JNeExpr) &&
        referenceOperand(value.op1) && referenceOperand(value.op2))

private fun referenceOperand(value: Value): Boolean = value is NullConstant || value is Local && value.type is ReferenceType

private fun totalPrimitive(value: Value): Boolean = when (value) {
    is Local -> value.type.toString() in INTEGER_TYPES
    is IntConstant -> true
    is AbstractBinopExpr -> value.javaClass.simpleName in TOTAL_OPERATORS &&
        totalPrimitive(value.op1) && totalPrimitive(value.op2)
    else -> false
}

private val INTEGER_TYPES = setOf("boolean", "byte", "char", "short", "int")
private val TOTAL_OPERATORS = setOf("JEqExpr", "JNeExpr", "JGeExpr", "JGtExpr", "JLeExpr", "JLtExpr",
    "JAddExpr", "JSubExpr", "JMulExpr", "JAndExpr", "JOrExpr", "JXorExpr", "JShlExpr", "JShrExpr", "JUshrExpr")
