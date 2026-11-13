package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot

import sootup.core.model.MethodModifier

/** Same-input pre-fold evidence; never inferred from the other revision's source or runtime observations. */
internal class FoldReachability {
    private var calls: Map<String, Set<String>>? = null

    fun capture(edges: Map<String, Set<String>>, complete: Boolean) {
        if (complete) calls = edges
    }

    fun witnessed(snapshot: ProgramSnapshot): Set<String> {
        val edges = calls
        return if (edges == null || !foldedCompletely(snapshot)) emptySet() else witnessed(snapshot, edges)
    }

    private fun foldedCompletely(snapshot: ProgramSnapshot): Boolean = snapshot.matchedRules.any { it > 0 } &&
        snapshot.diagnostics.isEmpty() && snapshot.methodDiagnostics.values.all { it.isEmpty() }

    private fun witnessed(snapshot: ProgramSnapshot, edges: Map<String, Set<String>>): Set<String> {
        val roots = snapshot.methods.values.filter {
            MethodModifier.PRIVATE !in it.modifiers && !it.identity.substringBefore('(').endsWith(".<clinit>") &&
                (snapshot.entryClasses == null || it.identity.substringBefore('(').substringBeforeLast('.') in snapshot.entryClasses)
        }.map { it.identity } + snapshot.externalEntries
        val byOwner = snapshot.methods.values.groupBy { it.identity.substringBefore('(').substringBeforeLast('.') }
        val pending = ArrayDeque(roots)
        val visited = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val method = pending.removeFirst()
            if (method !in snapshot.methods || !visited.add(method)) continue
            edges[method].orEmpty().forEach(pending::addLast)
            // The same constructor ownership expansion is used before and after folding.
            constructedMethods(method, byOwner).forEach { pending.addLast(it.identity) }
        }
        return visited - survivingReferences(snapshot, roots)
    }
}

/** Over-approximates surviving static references with the same revision's entry inventory. */
private fun survivingReferences(snapshot: ProgramSnapshot, roots: List<String>): Set<String> {
    val visited = linkedSetOf<String>()
    val pending = ArrayDeque(roots)
    val byOwner = snapshot.methods.values.groupBy { it.identity.substringBefore('(').substringBeforeLast('.') }
    while (pending.isNotEmpty()) {
        val identity = pending.removeFirst()
        val method = snapshot.methods[identity]?.takeIf { visited.add(identity) } ?: continue
        val owner = identity.substringBefore('(').substringBeforeLast('.')
        val calls = method.calls + method.lambdaTargets
        calls.forEach(pending::addLast)
        val referenced = method.references.filter { '(' !in it }.map { it.substringBefore(':').substringBeforeLast('.') }
        (setOf(owner) + referenced + snapshot.initializationDependencies[owner].orEmpty())
            .map { "$it.<clinit>()V" }.forEach(pending::addLast)
        calls.flatMap { constructedMethods(it, byOwner) }.forEach { pending.addLast(it.identity) }
    }
    return visited
}

/** Constructor references retain non-private anonymous/local callback bodies, including interface implementations. */
private fun constructedMethods(identity: String, byOwner: Map<String, List<MethodSnapshot>>): List<MethodSnapshot> =
    if (identity.substringBefore('(').endsWith(".<init>")) {
        byOwner[identity.substringBefore('(').substringBeforeLast('.')].orEmpty().filter { MethodModifier.PRIVATE !in it.modifiers }
    } else emptyList()
