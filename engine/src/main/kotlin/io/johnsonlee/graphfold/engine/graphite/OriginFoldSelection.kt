package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites
import org.objectweb.asm.commons.Remapper

/** Evaluate user selectors against original identities; only typed selected keys cross the normalization boundary. */
internal fun originFoldSelection(
    graph: Graph, rules: List<FoldRule>, owners: Map<String, String>,
    resolve: (Graph, FoldSites, Int) -> FoldSites, audit: RuleAuditCapture,
): List<FoldSites> = rules.mapIndexed { index, rule ->
    runCatching {
        val selected = when (rule) {
            is FoldSites -> resolve(graph, rule, index)
            is ConstantFold -> {
                val sites = constantSelection(graph, rule)
                audit.selected(index, sites)
                constantSites(rule, sites)
            }
        }
        remapOriginSelection(selected, owners)
    }.onFailure { audit.failed(index, it) }.getOrThrow()
}

/** Pattern-matched candidates before argument constraints are evaluated by the fold pass. */
internal fun constantSelection(graph: Graph, rule: ConstantFold): List<CallSiteNode> =
    graph.nodes(CallSiteNode::class.java).filter { (it.ordinal ?: -1) >= 0 && rule.matchesCallSite(it.foldProperties()) }.toList()

private fun constantSites(rule: ConstantFold, sites: List<CallSiteNode>): FoldSites = FoldSites(
    select = rule.cypher,
    selected = sites.map { CallSiteKey(it.caller.signature, it.caller.descriptor, it.callee.signature,
        it.callee.descriptor, requireNotNull(it.ordinal)) }.toSet(),
    value = rule.value, arguments = rule.arguments,
)

internal fun remapOriginSelection(rule: FoldSites, owners: Map<String, String>): FoldSites {
    val remapper = object : Remapper(org.objectweb.asm.Opcodes.ASM9) {
        override fun map(internalName: String): String = owners[internalName.replace('/', '.')]
            ?.replace('.', '/') ?: internalName
    }
    fun type(name: String): String {
        val element = name.removeSuffix("[]")
        return if (element != name) type(element) + "[]" else owners[name] ?: name
    }
    fun signature(value: String): String {
        val opening = value.indexOf('(')
        require(opening > 0 && value.endsWith(')')) { "Invalid selected method signature: $value" }
        val method = value.substring(0, opening)
        val owner = method.substringBeforeLast('.')
        val parameters = value.substring(opening + 1, value.lastIndex)
        return type(owner) + "." + method.substringAfterLast('.') + "(" +
            parameters.takeIf(String::isNotEmpty)?.split(',')?.joinToString(",", transform = ::type).orEmpty() + ")"
    }
    fun key(value: CallSiteKey) = CallSiteKey(
        signature(value.caller), remapper.mapMethodDesc(value.callerDescriptor),
        signature(value.callee), remapper.mapMethodDesc(value.calleeDescriptor), value.ordinal,
    )
    return rule.copy(
        selected = requireNotNull(rule.selected).map(::key).toSet(),
        resultTypes = rule.resultTypes.mapKeys { key(it.key) }.mapValues { type(it.value) },
        provenance = null,
    )
}

private fun CallSiteNode.foldProperties(): Map<String, String> = mapOf(
    "caller_class" to caller.declaringClass.className, "caller_name" to caller.name,
    "caller_signature" to caller.signature, "caller_descriptor" to caller.descriptor,
    "callee_class" to callee.declaringClass.className, "callee_name" to callee.name,
    "callee_signature" to callee.signature, "callee_descriptor" to callee.descriptor,
    "ordinal" to requireNotNull(ordinal).toString(),
)
