package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.ProofStatus
import io.johnsonlee.graphfold.engine.safety.PropertyProof
import io.johnsonlee.graphfold.engine.safety.ReturnNullness
import org.objectweb.asm.Type

internal data class SafetyValue(
    val expression: String, val type: Type, val nullness: ReturnNullness = ReturnNullness.NOT_APPLICABLE,
    val integer: Int? = null, val string: String? = null, val exactType: String? = null,
    val stable: Boolean = true, val fresh: Boolean = false, val boxed: SafetyValue? = null,
) {
    companion object {
        fun integer(value: Int) = SafetyValue("int:$value", Type.INT_TYPE, integer = value)
        fun string(value: String) = SafetyValue("string:${value.length}:$value", Type.getObjectType("java/lang/String"),
            ReturnNullness.NON_NULL, string = value, exactType = "java/lang/String")
        fun unknown(type: Type, identity: String, stable: Boolean = false) = SafetyValue(identity, type,
            if (type.sort == Type.OBJECT || type.sort == Type.ARRAY) ReturnNullness.UNKNOWN else ReturnNullness.NOT_APPLICABLE,
            stable = stable)
        val NULL = SafetyValue("null", Type.getObjectType("java/lang/Object"), ReturnNullness.NULL)
        val VOID = SafetyValue("void", Type.VOID_TYPE)
    }
}

internal data class SafetyFacts(
    val effects: PropertyProof = proved("No externally visible effect in the supported instructions"),
    val exceptions: PropertyProof = proved("All evaluated instructions are nonthrowing under the stated JVM model"),
    val termination: PropertyProof = proved("Finite acyclic control flow and terminating resolved callees"),
    val dependencies: Set<String> = emptySet(), val residuals: Set<String> = emptySet(),
) {
    val normalCompletionProven: Boolean
        get() = exceptions.status == ProofStatus.PROVEN && termination.status == ProofStatus.PROVEN

    fun merge(other: SafetyFacts) = SafetyFacts(combine(effects, other.effects), combine(exceptions, other.exceptions),
        combine(termination, other.termination), dependencies + other.dependencies, residuals + other.residuals)
    fun retainResidual(identity: String, fingerprint: String): SafetyFacts {
        val evidence = "Unselected instructions outside the traversed paths are retained exactly: $identity [$fingerprint]"
        fun property(value: PropertyProof) = value.copy(evidence = value.evidence + evidence)
        return copy(effects = property(effects), exceptions = property(exceptions), termination = property(termination),
            residuals = residuals + "$identity:$fingerprint")
    }
    fun conditional(feasible: Boolean): SafetyFacts {
        fun property(value: PropertyProof) = if (!feasible && value.status == ProofStatus.REFUTED)
            value.copy(status = ProofStatus.UNPROVEN) else value
        return copy(effects = property(effects), exceptions = property(exceptions))
    }
    fun unknown(reason: String) = merge(SafetyFacts(unproved(reason), unproved(reason), unproved(reason)))
    companion object {
        private const val JVM_MODEL = "Supplied classpath and ordinary JVM execution; VM resource exhaustion and linkage failures excluded"
        fun proved(reason: String) = PropertyProof(ProofStatus.PROVEN, listOf(reason), listOf(JVM_MODEL))
        fun unproved(reason: String) = PropertyProof(ProofStatus.UNPROVEN, listOf(reason))
        fun refuted(reason: String) = PropertyProof(ProofStatus.REFUTED, listOf(reason))
        private fun combine(first: PropertyProof, second: PropertyProof): PropertyProof {
            val status = when {
                first.status == ProofStatus.REFUTED || second.status == ProofStatus.REFUTED -> ProofStatus.REFUTED
                first.status == ProofStatus.UNPROVEN || second.status == ProofStatus.UNPROVEN -> ProofStatus.UNPROVEN
                else -> ProofStatus.PROVEN
            }
            return PropertyProof(status, (first.evidence + second.evidence).distinct(), (first.assumptions + second.assumptions).distinct())
        }
    }
}

internal data class SafetyEvaluation(val value: SafetyValue?, val facts: SafetyFacts) {
    fun semanticKey(): String? = value?.takeIf {
        it.stable && listOf(facts.effects, facts.exceptions, facts.termination).all { property -> property.status == ProofStatus.PROVEN }
    }?.let { "return:${it.type.descriptor}:${it.expression}|residuals:${facts.residuals.sorted()}" }
}

internal data class SafetyState(
    val locals: MutableMap<Int, SafetyValue>, val stack: MutableList<SafetyValue> = mutableListOf(),
    var facts: SafetyFacts = SafetyFacts(), val nullFacts: MutableMap<String, ReturnNullness> = mutableMapOf(),
    val integerFacts: MutableMap<String, Int> = mutableMapOf(),
    val excludedIntegers: MutableMap<String, Set<Int>> = mutableMapOf(), var feasible: Boolean = true,
) {
    fun fork() = copy(locals = locals.toMutableMap(), stack = stack.toMutableList(), nullFacts = nullFacts.toMutableMap(),
        integerFacts = integerFacts.toMutableMap(), excludedIntegers = excludedIntegers.toMutableMap())
    fun resolve(value: SafetyValue): SafetyValue = value.copy(nullness = nullFacts[value.expression] ?: value.nullness,
        integer = integerFacts[value.expression] ?: value.integer)
    fun pop(): SafetyValue = resolve(stack.removeAt(stack.lastIndex))
    fun push(value: SafetyValue) { stack.add(resolve(value)) }
    fun unknown(reason: String): SafetyEvaluation = SafetyEvaluation(null, facts.unknown(reason))
    fun exception(reason: String, definite: Boolean = false) {
        val proof = if (definite && feasible) SafetyFacts.refuted(reason) else SafetyFacts.unproved(reason)
        facts = facts.merge(SafetyFacts(exceptions = proof))
        feasible = false // A later witness needs independently established normal completion of this operation.
    }
    fun receiver(value: SafetyValue, site: String): Boolean {
        if (value.nullness != ReturnNullness.NON_NULL) exception("$site: receiver ${value.expression} may be null",
            value.nullness == ReturnNullness.NULL)
        return value.nullness != ReturnNullness.NULL
    }
    fun effect(reason: String) {
        facts = facts.merge(SafetyFacts(effects = if (feasible) SafetyFacts.refuted(reason) else SafetyFacts.unproved(reason)))
    }
}

/** Switch defaults exclude a distinct constant; this cannot contradict an unknown selector's positive fact. */
internal fun SafetyState.excludingInteger(selector: SafetyValue, key: Int): SafetyState = fork().also { result ->
    result.excludedIntegers[selector.expression] = result.excludedIntegers[selector.expression].orEmpty() + key
    if (!selector.expression.startsWith("arg:")) result.feasible = false
}
