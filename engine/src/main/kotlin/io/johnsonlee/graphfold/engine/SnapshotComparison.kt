package io.johnsonlee.graphfold.engine

import sootup.core.model.MethodModifier
import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind

/** Conservative structural proof over each obligation's dependencies. Unknown frontend edges block upstream. */
internal class SnapshotComparison(private val base: ProgramSnapshot, originalHead: ProgramSnapshot) {
    private val alignment = PrivateMemberAlignment(base, originalHead)
    private val head = alignment.aligned()
    private val entryPoints = base.methods.values.filter {
        MethodModifier.PRIVATE !in it.modifiers && !it.identity.substringBefore('(').endsWith(".<clinit>") &&
            (base.entryClasses == null || methodOwner(it.identity) in base.entryClasses)
    }.map { it.identity }.toSet() + base.externalEntries + head.externalEntries + head.overrides.filter {
        methodOwner(it) in base.entryClasses.orEmpty()
    }
    private val baseReachable = reachable(base)
    private val headReachable = reachable(head)
    private val allMethods = base.methods.keys + head.methods.keys
    private val reachableMethods = baseReachable + headReachable
    private val reachableByOwner = reachableMethods.groupBy(::methodOwner)
    private val methodsByOwner = allMethods.groupBy(::methodOwner)
    private val baseFields = base.fields.values.groupBy { fieldOwner(it.identity) }
    private val headFields = head.fields.values.groupBy { fieldOwner(it.identity) }
    private val nestedOwners = (base.enclosingClasses.entries + head.enclosingClasses.entries)
        .groupBy({ it.value }, { it.key })
    private val nestedMethods = mutableMapOf<String, List<String>>()
    private val initializationDifferences = initializationDifferences()

    fun isUnreachable(obligation: ProofObligation): Boolean = roots(aligned(obligation)).isEmpty() &&
        (obligation.kind == SymbolKind.METHOD || !ownerReachable(obligation.owner))

    fun differences(original: ProofObligation): List<String> {
        val obligation = aligned(original)
        val missingOwner = obligation.owner !in base.declarations && obligation.owner !in head.declarations
        val missingMethod = obligation.kind == SymbolKind.METHOD &&
            obligation.identity !in base.methods && obligation.identity !in head.methods
        if (missingOwner || missingMethod) return listOf("Unrepresented declaration ${obligation.identity}")
        val methods = dependencies(roots(obligation))
        val changed = methods.filter { method ->
            base.methods[method]?.referenceCanonical != head.methods[method]?.referenceCanonical ||
                base.methods[method]?.references != head.methods[method]?.references ||
                base.declarations[method] != head.declarations[method]
        }
        // SOURCE-retained annotations can change generated declarations without changing the annotated method's IR.
        val owners = methods.map(::methodOwner).toSet() +
            if (obligation.kind == SymbolKind.METHOD || !ownerReachable(obligation.owner)) emptySet() else setOf(obligation.owner)
        return (changed + owners.filter { classDeclaration(base, it) != classDeclaration(head, it) } +
            initializationChanges(obligation)).distinct().sorted()
    }

    private fun initializationChanges(obligation: ProofObligation): List<String> =
        initializationDifferences[obligation.owner].orEmpty()

    private fun initializationDifferences(): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        (base.initializationDependencies.keys + head.initializationDependencies.keys).filter(::ownerReachable).forEach { owner ->
            val old = base.initializationDependencies[owner].orEmpty()
            val new = head.initializationDependencies[owner].orEmpty()
            val changed = (old - new) + (new - old)
            if (changed.isNotEmpty()) {
                val message = "Class initialization dependencies of $owner changed: $old -> $new"
                (changed + owner).forEach { result.getOrPut(it) { mutableListOf() }.add(message) }
            }
        }
        return result
    }

    private fun aligned(obligation: ProofObligation): ProofObligation =
        obligation.copy(identity = alignment.names[obligation.identity] ?: obligation.identity)

    private fun classDeclaration(snapshot: ProgramSnapshot, owner: String): String? {
        val shape = snapshot.classShapes[owner] ?: return snapshot.declarations[owner]
        val fields = (if (snapshot === base) baseFields else headFields)[owner].orEmpty().filter {
            it.identity !in snapshot.ignoredFields
        }.sortedBy { it.identity }.map { listOf(it.identity, it.declaration, it.constant) }
        return "$shape\n$fields"
    }

    private fun roots(obligation: ProofObligation): List<String> {
        // A dead private source method must not make an unrelated public generated class relevant.
        if (obligation.kind == SymbolKind.METHOD && !isReachable(obligation.identity)) return emptyList()
        return when (obligation.kind) {
            SymbolKind.METHOD -> (listOf(obligation.identity) + reachableByOwner[obligation.owner].orEmpty()
                .filter { syntheticDependency(it, obligation) } + nestedMethods(obligation.owner)).filter(::isReachable).distinct()
            SymbolKind.FIELD -> reachableMethods.toList() // Constant inlining can cross owner boundaries.
            SymbolKind.CLASS -> (reachableByOwner[obligation.owner].orEmpty() + nestedMethods(obligation.owner)).distinct()
        }
    }

    /** Union both folded graphs: removal of an edge must not hide its old behavior. */
    private fun dependencies(roots: List<String>): Set<String> {
        val visited = linkedSetOf<String>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val calls = base.methods[current]?.calls.orEmpty() + head.methods[current]?.calls.orEmpty()
            calls.filter { it in allMethods }.forEach(pending::addLast)
            val owner = methodOwner(current)
            val initializedOwners = setOf(owner) + base.initializationDependencies[owner].orEmpty() +
                head.initializationDependencies[owner].orEmpty()
            initializedOwners.map { "$it.<clinit>()V" }.filter { it in allMethods && it !in visited }.forEach(pending::addLast)
            // Constructed objects can escape and have public methods invoked by code outside the current method.
            // Constructor calls alone do not represent these callbacks (e.g. a returned anonymous Runnable).
            val constructed = current.substringBefore('(').endsWith(".<init>")
            methodsByOwner[owner].orEmpty().filter { candidate ->
                candidate.substringBefore('(').endsWith(".<clinit>") || constructed && isReachable(candidate)
            }.forEach { if (it !in visited) pending.addLast(it) }
        }
        return visited
    }

    private fun ownerReachable(owner: String): Boolean = owner in reachableByOwner

    private fun isReachable(identity: String): Boolean = identity in reachableMethods

    private fun methodOwner(identity: String): String = identity.substringBefore('(').substringBeforeLast('.')

    private fun fieldOwner(identity: String): String = identity.substringBefore(':').substringBeforeLast('.')

    private fun nestedMethods(owner: String): List<String> = nestedMethods.getOrPut(owner) {
        val pending = ArrayDeque(nestedOwners[owner].orEmpty())
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val child = pending.removeFirst()
            if (visited.add(child)) pending.addAll(nestedOwners[child].orEmpty())
        }
        visited.flatMap { reachableByOwner[it].orEmpty() }
    }

    private fun syntheticDependency(identity: String, obligation: ProofObligation): Boolean =
        methodOwner(identity) == obligation.owner &&
            (base.methods[identity]?.modifiers.orEmpty() + head.methods[identity]?.modifiers.orEmpty()).contains(MethodModifier.SYNTHETIC)

    private fun reachable(snapshot: ProgramSnapshot): Set<String> {
        val byOwner = snapshot.methods.values.groupBy { methodOwner(it.identity) }
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(entryPoints.filter { it in snapshot.methods })
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (visited.add(current)) {
                val method = snapshot.methods.getValue(current)
                method.calls.filter { it in snapshot.methods }.forEach(pending::addLast)
                val owner = methodOwner(current)
                val initializedOwners = setOf(owner) + snapshot.initializationDependencies[owner].orEmpty()
                initializedOwners.map { "$it.<clinit>()V" }.filter { it in snapshot.methods }.forEach(pending::addLast)
                val referencedOwners = method.references
                    .filter { '(' !in it }.map { it.substringBefore(':').substringBeforeLast('.') }.toSet()
                val constructedOwners = method.calls
                    .filter { it.substringBefore('(').endsWith(".<init>") }.map(::methodOwner).toSet()
                (setOf(owner) + referencedOwners + constructedOwners).flatMap { byOwner[it].orEmpty() }.filter { candidate ->
                    ((methodOwner(candidate.identity) == owner || methodOwner(candidate.identity) in referencedOwners) &&
                        candidate.identity.substringBefore('(').endsWith(".<clinit>")) ||
                        (methodOwner(candidate.identity) in constructedOwners && MethodModifier.PRIVATE !in candidate.modifiers)
                }.map { it.identity }.forEach(pending::addLast)
            }
        }
        return visited
    }
}
