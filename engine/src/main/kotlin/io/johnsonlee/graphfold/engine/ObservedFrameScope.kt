package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import sootup.core.model.MethodModifier

/** Current-thread captures require a surviving renamed frame below them in this revision's folded call graph. */
internal class ObservedFrameScope(private val snapshot: ProgramSnapshot, private val reachable: Set<String>) {
    private val methodsByOwner = snapshot.methods.values.groupBy { owner(it.identity) }
    private val scopes = mutableMapOf<Set<String>, Set<String>>()

    fun contains(observer: String, owners: Set<String>): Boolean = observer in scopes.getOrPut(owners) {
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(owners.flatMap { methodsByOwner[it].orEmpty() }.map { it.identity })
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current in reachable && visited.add(current)) {
                pending.addAll(successors(snapshot.methods.getValue(current)))
            }
        }
        visited
    }

    private fun successors(method: MethodSnapshot): Set<String> {
        val constructed = method.calls.filter { it.substringBefore('(').endsWith(".<init>") }.map(::owner).toSet()
        val referenced = method.references.filter { '(' !in it }.map { it.substringBefore(':').substringBeforeLast('.') }
        val initialized = setOf(owner(method.identity)) + constructed + referenced + method.calls.map(::owner)
        val initializers = (initialized + initialized.flatMap { snapshot.initializationDependencies[it].orEmpty() })
            .map { "$it.<clinit>()V" }
        // A constructed callback may execute synchronously through an opaque consumer before its caller returns.
        // Conservatively retain public callback methods rather than assuming all such invocations are deferred.
        val callbacks = constructed.flatMap { methodsByOwner[it].orEmpty() }
            .filter { MethodModifier.PRIVATE !in it.modifiers }.map { it.identity }
        return method.calls + initializers + callbacks
    }

    private fun owner(identity: String): String = identity.substringBefore('(').substringBeforeLast('.')
}
