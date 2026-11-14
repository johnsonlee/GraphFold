package io.johnsonlee.graphfold.engine.graphite


import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.Graph
import sootup.core.model.MethodModifier

/**
 * Propagates resource effects over captured folded methods, keeping library methods out of application roots.
 * Missing bodies and unknown initialization targets produce wildcard effects rather than an absence proof.
 */
internal class DependencyResourceAnalysis(
    graph: Graph,
    private val methods: Map<String, MethodSnapshot>,
    calls: Map<String, Set<String>>,
    private val initialization: Map<String, Set<String>>,
    initializers: Map<String, Set<String>>? = null,
    private val fieldOwner: (String) -> String? = { null },
    private val inheritedMethods: Map<String, Set<String>?> = emptyMap(),
    private val entryInitializers: Map<String, Set<String>?> = emptyMap(),
) {
    private val effects = methods.mapValues { (_, method) -> method.resourceReads.toMutableSet() }.toMutableMap()
    private val dependents = mutableMapOf<String, MutableSet<String>>()

    init {
        val sites = graph.nodes(CallSiteNode::class.java).groupBy { it.caller.identity }
        methods.forEach { (identity, method) ->
            if (method.canonical.endsWith("\nNO_BODY") ||
                method.modifiers.any { it == MethodModifier.ABSTRACT || it == MethodModifier.NATIVE }) {
                unknown(identity)
            }
            val invoked = calls[identity] ?: method.calls.also { unknown(identity) }
            val targets = callTargets(invoked, sites[identity].orEmpty())
            targets.forEach { target -> depend(identity, target) }
            inheritedMethods[owner(identity)]?.forEach { depend(identity, it) }
            if (owner(identity) in inheritedMethods && inheritedMethods[owner(identity)] == null) unknown(identity)
            // Modeled methods still initialize their declaring classes. Fields can trigger initialization without calls.
            val operations = if (initializers != null) initializers.getValue(identity) else (method.references + method.calls)
                .map { if (':' in it) it else owner(it) }.toSet()
            val owners = operations + targets.map(::owner) + owner(identity)
            owners.forEach { initialize(identity, it) }
        }
        propagate()
    }

    fun reads(identity: String): List<ResourceRead> {
        val reads = effects[identity] ?: return listOf(UNKNOWN)
        return reads.toList()
    }

    /** Resource-only API evidence for a type with no declared reachable method, without executing its private constructors. */
    fun entryReads(owner: String): List<ResourceRead> {
        val inherited = inheritedMethods[owner]
        val fields = entryInitializers[owner]
        return if (inherited == null || fields == null) listOf(UNKNOWN) else {
            (inherited.flatMap(::reads) + fields.flatMap(::initializationReads)).distinct()
        }
    }

    private fun initializationReads(owner: String): List<ResourceRead> {
        val owners = initialization[owner].orEmpty() + owner
        return owners.filterNot { it in PLATFORM_INITIALIZATION }.flatMap { current ->
            when {
                current !in initialization -> listOf(UNKNOWN)
                "$current.<clinit>()V" in methods -> reads("$current.<clinit>()V")
                else -> emptyList()
            }
        }
    }

    private fun callTargets(invoked: Set<String>, sites: List<CallSiteNode>): Set<String> {
        val targets = invoked.toMutableSet()
        val selected = sites.filter { site -> site.ordinal?.let { it >= 0 } == true &&
            invoked.any { member(it) == member(site.callee.identity) } }
        // Keep unresolved declared targets even when the graph supplies candidates; candidates are not an exhaustiveness proof.
        targets += selected.map { it.callee.identity }
        val selectedIds = selected.map { it.id }.toMutableSet()
        // Follow origin chains: callback targets can be derived from an already-derived virtual invocation.
        var changed = true
        while (changed) {
            changed = false
            sites.filter { it.origin in selectedIds }.forEach { site ->
                targets += site.callee.identity
                if (selectedIds.add(site.id)) changed = true
            }
        }
        return targets
    }

    private fun initialize(identity: String, request: String) {
        val owner = if (':' in request) PLATFORM_FIELDS[request] ?: fieldOwner(request) else request
        // These are fixed platform initialization contracts, not a trust rule for a package or dependency JAR.
        if (owner in PLATFORM_INITIALIZATION) return
        if (owner == null) {
            unknown(identity)
        } else {
            (initialization[owner].orEmpty() + owner).filterNot { it in PLATFORM_INITIALIZATION }
                .forEach { initializeType(identity, it) }
        }
    }

    private fun initializeType(identity: String, owner: String) {
        if (owner !in initialization) unknown(identity)
        val initializer = "$owner.<clinit>()V"
        if (initializer in methods) depend(identity, initializer)
    }

    private fun depend(identity: String, target: String) {
        if (target in methods) dependents.getOrPut(target) { mutableSetOf() }.add(identity) else unknown(identity)
    }

    private fun unknown(identity: String) {
        effects.getValue(identity).add(UNKNOWN)
    }

    private fun propagate() {
        val pending = ArrayDeque(effects.filterValues { it.isNotEmpty() }.keys)
        val queued = pending.toMutableSet()
        while (pending.isNotEmpty()) {
            val target = pending.removeFirst()
            queued.remove(target)
            dependents[target].orEmpty().forEach { caller ->
                if (effects.getValue(caller).addAll(effects.getValue(target)) && queued.add(caller)) pending.addLast(caller)
            }
        }
    }

    private fun member(identity: String): String = identity.substringBefore('(').substringAfterLast('.') +
        identity.substringAfter('(', "").let { "($it" }

    private fun owner(identity: String): String = identity.substringBefore('(').substringBefore(':').substringBeforeLast('.')

    private companion object {
        val UNKNOWN = ResourceRead(null, null)
        val PLATFORM_FIELDS = mapOf(
            "java.lang.System.out:Ljava/io/PrintStream;" to "java.lang.System",
            "java.lang.System.err:Ljava/io/PrintStream;" to "java.lang.System",
        )
        val PLATFORM_INITIALIZATION = setOf(
            "java.lang.Object", "java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.Class",
            "java.lang.System", "java.io.PrintStream", "java.lang.Boolean", "java.lang.Byte", "java.lang.Character",
            "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "java.util.ResourceBundle",
        )
    }
}
