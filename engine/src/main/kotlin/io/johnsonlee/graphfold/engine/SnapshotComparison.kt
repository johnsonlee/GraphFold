package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead

import sootup.core.model.MethodModifier
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind

/** Conservative structural proof over each obligation's dependencies. Unknown frontend edges block upstream. */
internal class SnapshotComparison(
    private val base: ProgramSnapshot, originalHead: ProgramSnapshot, val originsVerified: Boolean = true,
) {
    private val alignment = PrivateMemberAlignment(if (originsVerified) base else originalHead, originalHead)
    private val head = alignment.aligned()
    val resourceDependenciesAnalyzed: Boolean get() = base.resourceDependenciesAnalyzed && head.resourceDependenciesAnalyzed
    private val entryPoints = base.methods.values.filter {
        MethodModifier.PRIVATE !in it.modifiers && !it.identity.substringBefore('(').endsWith(".<clinit>") &&
            (base.entryClasses == null || methodOwner(it.identity) in base.entryClasses)
    }.map { it.identity }.toSet() + head.overrides.filter {
        methodOwner(it) in base.entryClasses.orEmpty()
    }
    private val baseReachable = reachable(base)
    private val headReachable = reachable(head)
    private val witnessedBase = reachable(base, directOnly = true)
    private val witnessedHead = reachable(head, directOnly = true)
    private val witnessedReachable = witnessedBase + witnessedHead
    private val baseFrames by lazy { ObservedFrameScope(base, baseReachable) }
    private val headFrames by lazy { ObservedFrameScope(head, headReachable) }
    private val allMethods = base.methods.keys + head.methods.keys
    private val reachableMethods = baseReachable + headReachable
    private val reachableByOwner = reachableMethods.groupBy(::methodOwner)
    private val methodsByOwner = allMethods.groupBy(::methodOwner)
    private val baseFields = base.fields.values.groupBy { fieldOwner(it.identity) }
    private val headFields = head.fields.values.groupBy { fieldOwner(it.identity) }
    private val nestedOwners = (base.enclosingClasses.entries + head.enclosingClasses.entries)
        .groupBy({ it.value }, { it.key })
    private val nestedMethods = mutableMapOf<String, List<String>>()
    private val controlledOwners = methodsByOwner.keys.filterTo(linkedSetOf()) { owner ->
        ownerRemovedByControls(base, owner) && ownerRemovedByControls(head, owner)
    }
    private val structuralComparisonOwners = (base.structuralClassShapes.keys + head.structuralClassShapes.keys)
        .filterTo(hashSetOf()) { owner ->
        listOf(base, head).any { snapshot ->
            snapshot.structuralClassShapes[owner]?.nestedTypes.orEmpty().any { it.first in controlledOwners }
        }
    }
    private val initializationDifferences = initializationDifferences()
    private val changedMethods = allMethods.filterTo(hashSetOf()) { method ->
        val before = base.methods[method]
        val after = head.methods[method]
        before == null || after == null || before.referenceCanonical != after.referenceCanonical ||
            before.references != after.references || base.declarations[method] != head.declarations[method]
    }
    private val witnessedFields by lazy {
        val typed = addedFieldWitnesses(base, head, changedMethods intersect witnessedHead) +
            addedFieldWitnesses(head, base, changedMethods intersect witnessedBase)
        // Source fields are owner-qualified names without descriptors. Multiple JVM
        // declarations sharing that source name cannot be joined unambiguously.
        (base.fields.keys + head.fields.keys).groupBy { it.substringBefore(':') }
            .filterValues { it.size == 1 && it.single() in typed }.keys
    }
    private val dispatchCoverage by lazy { DispatchCoverage(base, head) }

    fun isUnreachable(obligation: ProofObligation): Boolean = aligned(obligation).let {
        (it.kind == SymbolKind.METHOD && removedBySelectedControls(it.identity, base, head) ||
            it.kind != SymbolKind.METHOD && it.owner in controlledOwners) && roots(it).isEmpty()
    }

    fun differences(original: ProofObligation): List<String> {
        val obligation = aligned(original)
        val missingOwner = obligation.owner !in base.declarations && obligation.owner !in head.declarations
        val missingMethod = obligation.kind == SymbolKind.METHOD &&
            obligation.identity !in base.methods && obligation.identity !in head.methods
        if (missingOwner || missingMethod) return listOf("Unrepresented declaration ${obligation.identity}")
        val methods = dependencies(roots(obligation))
        val changed = methods.filter { it in changedMethods }
        // SOURCE-retained annotations can change generated declarations without changing the annotated method's IR.
        val owners = methods.map(::methodOwner).toSet() +
            if (obligation.kind == SymbolKind.METHOD) emptySet() else setOf(obligation.owner)
        return (changed + owners.filter {
            val explicit = it == obligation.owner && obligation.kind != SymbolKind.METHOD
            classDeclaration(base, it, explicit) != classDeclaration(head, it, explicit)
        } +
            initializationDifferences[obligation.owner].orEmpty()).distinct().sorted()
    }

    /** Input-output proof covers every supplied application declaration, including generated and uncalled code. */
    fun wholeProgramDifferences(): List<String> = (allMethods.filter {
        it in changedMethods && (isReachable(it) || !removedBySelectedControls(it, base, head))
    } + (base.classShapes.keys + head.classShapes.keys + base.declarations.keys.filter { '(' !in it } +
        head.declarations.keys.filter { '(' !in it }).filter {
        classDeclaration(base, it, includeIgnored = true) != classDeclaration(head, it, includeIgnored = true)
    } + initializationDifferences.values.flatten()).distinct().sorted()

    /** Retains both versions: a removed resource reader can still expose a changed base value. */
    fun reachableSnapshots(): List<io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot> =
        baseReachable.mapNotNull(base.methods::get) + headReachable.mapNotNull(head.methods::get)

    /** Existing exposed types may have only inherited APIs or static fields, without a declared callable method. */
    val resourceEntryReads: List<io.johnsonlee.graphfold.engine.snapshot.ResourceRead> by lazy {
        val owners = base.entryClasses.orEmpty() + reachableByOwner.keys +
            (base.externalEntries + head.externalEntries).map(::methodOwner)
        owners.flatMap { base.resourceEntryReads[it].orEmpty() + head.resourceEntryReads[it].orEmpty() }
    }

    /** Only surviving gaps on a change's dependencies/callers invalidate its proof. */
    fun analysisGaps(original: ProofObligation? = null, resourceProof: Boolean = false): List<String> {
        val obligation = original?.let(::aligned)
        val relevant = relevantMethods(obligation)
        val owners = relevant.map(::methodOwner).toSet() + listOfNotNull(obligation?.owner)
        val global = obligation == null || obligation.kind != SymbolKind.METHOD ||
            !isReachable(obligation.identity) || opaqueNormalization
        val compared = if (obligation == null) reachableMethods else dependencies(roots(obligation))
        // A surviving method/initialization change is evidence of an uncontrolled production change.
        // Missing dispatch targets cannot erase that surviving difference, but cannot prove any
        // other obligation controlled. Metadata-only differences retain the conservative gap.
        val witnessedDifference = obligation?.let(::hasDifferenceWitness) == true
        val dispatchCovered = !resourceProof && originsVerified && !opaqueNormalization &&
            (witnessedDifference || dispatchCoverage.covers(compared))
        val covered: (MethodAnalysisGap) -> Boolean = {
            coveredGap(it, resourceProof, dispatchCovered)
        }
        return (activeGaps(base, baseReachable, relevant, owners, global, covered) +
            activeGaps(head, headReachable, relevant, owners, global, covered)).distinct().sorted()
    }

    private fun coveredGap(gap: MethodAnalysisGap, resources: Boolean, dispatch: Boolean): Boolean =
        resources && gap.resourceEffectModeled || dispatch && gap.dispatchOnly ||
            !resources && literalApplicationComparison && (gap.externalObservationOnly || gap.dispatchOnly && completeLiteralInventory)

    /** External execution need not be predicted when every application body retains its literal identity.
     * No private-member pairing, opaque name erasure, metadata change or omitted method may use this proof.
     * Body differences still participate in every obligation and the whole-input comparison.
     */
    private val literalApplicationComparison: Boolean by lazy {
        !opaqueNormalization && base.methods.keys == head.methods.keys && base.declarations == head.declarations &&
            base.controlRemovedMethods.isEmpty() && head.controlRemovedMethods.isEmpty()
    }

    /** Dispatch-independent comparison needs inventory evidence for every compared method, not just a partial graph. */
    private val completeLiteralInventory: Boolean by lazy {
        listOf(base, head).all { snapshot ->
            snapshot.diagnostics.isEmpty() && snapshot.declarations.keys.containsAll(snapshot.methods.keys) &&
                snapshot.methodDiagnostics.values.flatten().all { it.dispatchOnly || it.externalObservationOnly }
        } && base.initializationDependencies == head.initializationDependencies
    }

    private fun hasDifferenceWitness(obligation: ProofObligation): Boolean =
        obligation.identity in changedMethods && obligation.identity in witnessedReachable ||
            obligation.kind == SymbolKind.METHOD &&
            (lambdaDifferenceWitness(base, witnessedBase, changedMethods, obligation) ||
                lambdaDifferenceWitness(head, witnessedHead, changedMethods, obligation)) ||
            obligation.kind == SymbolKind.FIELD && obligation.identity in witnessedFields ||
            obligation.kind == SymbolKind.CLASS && initializationWitness(obligation.owner) ||
            obligation.kind == SymbolKind.METHOD &&
            interfaceInitializationWitness(base, head, witnessedBase, witnessedHead, obligation)

    private fun initializationWitness(owner: String): Boolean {
        val old = base.initializationDependencies[owner].orEmpty()
        val current = head.initializationDependencies[owner].orEmpty()
        return (current - old).isNotEmpty() && witnessedHead.any { methodOwner(it) == owner } ||
            (old - current).isNotEmpty() && witnessedBase.any { methodOwner(it) == owner }
    }

    private fun relevantMethods(obligation: ProofObligation?): Set<String> {
        if (obligation == null) return reachableMethods
        val closure = dependencies(roots(obligation)).toMutableSet()
        // Opaque consumers can inspect private state without a direct call to that state.
        closure.addAll(reachableByOwner[obligation.owner].orEmpty())
        val pending = ArrayDeque(closure)
        while (pending.isNotEmpty()) {
            callers[pending.removeFirst()].orEmpty().forEach { if (closure.add(it)) pending.addLast(it) }
        }
        return closure
    }

    // Opaque code can invoke private callbacks, inspect unread state or observe normalized names/nest metadata.
    // Ordinary reachable method bodies are compared without these erasures: unrelated opaque callers add no
    // missing dependency to that proof. Whole-program, field/class and unreachable-method proofs stay global.
    private val opaqueNormalization: Boolean by lazy {
        alignment.names.isNotEmpty() || base.originalOwners != head.originalOwners || allMethods.any { method ->
            method !in changedMethods && base.methods.getValue(method).canonical != head.methods.getValue(method).canonical
        }
    }

    private fun activeGaps(
        snapshot: ProgramSnapshot, reachable: Set<String>, relevant: Set<String>, owners: Set<String>, global: Boolean,
        covered: (MethodAnalysisGap) -> Boolean,
    ): List<String> =
        snapshot.methodDiagnostics.filterKeys { it in reachable }.flatMap { (identity, gaps) ->
            gaps.filter { gap ->
                val frameActive = !gap.requiresObservedFrame ||
                    (if (snapshot === base) baseFrames else headFrames).contains(identity, gap.observedOwners)
                val observes = gap.globalObservation && global || identity in relevant || gap.observedOwners.any { it in owners }
                val method = snapshot.methods.getValue(identity)
                !covered(gap) &&
                    frameActive && observes && (gap.callee == null || gap.callee in method.calls) &&
                    (gap.dynamicCall == null || gap.dynamicCall in method.dynamicCalls)
            }.map { it.message }
        }.distinct().sorted()

    private val callers: Map<String, Set<String>> by lazy {
        val result = mutableMapOf<String, MutableSet<String>>()
        reachableMethods.forEach { caller ->
            (base.methods[caller]?.calls.orEmpty() + head.methods[caller]?.calls.orEmpty()).forEach { callee ->
                result.getOrPut(callee) { linkedSetOf() }.add(caller)
            }
        }
        result
    }

    private fun initializationDifferences(): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        (base.initializationDependencies.keys + head.initializationDependencies.keys).forEach { owner ->
            val old = base.initializationDependencies[owner].orEmpty()
            val new = head.initializationDependencies[owner].orEmpty()
            val changed = (old - new) + (new - old)
            if (changed.isNotEmpty() && owner !in controlledOwners) {
                val message = "Class initialization dependencies of $owner changed: $old -> $new"
                (changed + owner).forEach { result.getOrPut(it) { mutableListOf() }.add(message) }
            }
        }
        return result
    }

    private fun aligned(obligation: ProofObligation): ProofObligation =
        obligation.copy(identity = alignment.names[obligation.identity] ?: obligation.identity)

    private fun classDeclaration(snapshot: ProgramSnapshot, owner: String, includeIgnored: Boolean): String? {
        val structured = snapshot.structuralClassShapes[owner]?.takeIf { owner in structuralComparisonOwners }
        val shape = if (structured != null) {
            structured.core + structured.nestedTypes.filterNot { it.first in controlledOwners }.toString()
        } else snapshot.classShapes[owner]
        val fields = (if (snapshot === base) baseFields else headFields)[owner].orEmpty().filter {
            includeIgnored || it.identity !in snapshot.ignoredFields
        }.sortedBy { it.identity }.map { listOf(it.identity, it.declaration, it.constant) }
        return when {
            owner in controlledOwners -> null
            shape == null -> snapshot.declarations[owner]
            else -> "$shape\n$fields"
        }
    }

    private fun roots(obligation: ProofObligation): List<String> = when {
        // Inline bodies are copied across owners with no surviving invocation edge to the declaration.
        obligation.inlined -> (reachableMethods +
            if (obligation.kind == SymbolKind.CLASS) methodsByOwner[obligation.owner].orEmpty()
            else setOf(obligation.identity).intersect(allMethods))
            .filter { isReachable(it) || !removedBySelectedControls(it, base, head) }
        obligation.owner in controlledOwners -> emptyList()
        // Absence from public-entry reachability alone is not control coverage of a production change.
        obligation.kind == SymbolKind.METHOD && !isReachable(obligation.identity) ->
            if (removedBySelectedControls(obligation.identity, base, head)) emptyList()
            else listOf(obligation.identity).filter { it in allMethods }
        else -> when (obligation.kind) {
            SymbolKind.METHOD -> (listOf(obligation.identity) + reachableByOwner[obligation.owner].orEmpty()
                .filter(::syntheticDependency) + nestedMethods(obligation.owner)).filter(::isReachable).distinct()
            SymbolKind.FIELD -> reachableMethods.toList() // Constant inlining can cross owner boundaries.
            SymbolKind.CLASS -> (methodsByOwner[obligation.owner].orEmpty() + nestedMethods(obligation.owner)).distinct()
                .filterNot { !isReachable(it) && removedBySelectedControls(it, base, head) }
        }
    }

    /** Union both folded graphs: removal of an edge must not hide its old behavior. */
    private fun dependencies(roots: List<String>): Set<String> {
        val visited = linkedSetOf<String>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val calls = base.methods[current]?.calls.orEmpty() + head.methods[current]?.calls.orEmpty() +
                base.methods[current]?.lambdaTargets.orEmpty() + head.methods[current]?.lambdaTargets.orEmpty()
            calls.filter { it in allMethods }.forEach(pending::addLast)
            val owner = methodOwner(current)
            val initializedOwners = setOf(owner) + base.initializationDependencies[owner].orEmpty() +
                head.initializationDependencies[owner].orEmpty()
            initializedOwners.map { "$it.<clinit>()V" }.filter { it in allMethods && it !in visited }.forEach(pending::addLast)
            // Constructed objects can escape and have public methods invoked by code outside the current method.
            // Constructor calls alone do not represent these callbacks (e.g. a returned anonymous Runnable).
            val constructed = current.substringBefore('(').endsWith(".<init>")
            methodsByOwner.getValue(owner).filter { candidate ->
                candidate.substringBefore('(').endsWith(".<clinit>") || constructed && isReachable(candidate)
            }.forEach { if (it !in visited) pending.addLast(it) }
        }
        return visited
    }

    private fun isReachable(identity: String): Boolean = identity in reachableMethods

    private fun nestedMethods(owner: String): List<String> = nestedMethods.getOrPut(owner) {
        val pending = ArrayDeque(nestedOwners[owner].orEmpty())
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val child = pending.removeFirst()
            if (visited.add(child)) pending.addAll(nestedOwners[child].orEmpty())
        }
        visited.flatMap { reachableByOwner[it].orEmpty() }
    }

    private fun syntheticDependency(identity: String): Boolean =
        (base.methods[identity]?.modifiers.orEmpty() + head.methods[identity]?.modifiers.orEmpty()).contains(MethodModifier.SYNTHETIC)

    private fun reachable(snapshot: ProgramSnapshot, directOnly: Boolean = false): Set<String> {
        val byOwner = snapshot.methods.values.groupBy { methodOwner(it.identity) }
        val visited = mutableSetOf<String>()
        val roots = if (directOnly) entryPoints + snapshot.externalEntries
        else entryPoints + base.externalEntries + head.externalEntries
        val pending = ArrayDeque(roots.filter { it in snapshot.methods })
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (visited.add(current)) {
                val method = snapshot.methods.getValue(current)
                val gaps = snapshot.methodDiagnostics[current].orEmpty().filter { it.dispatchOnly }
                // A remaining gap can own derived targets with different identities, so it
                // invalidates every outgoing edge as an independent production change witness.
                val calls = if (directOnly && gaps.isNotEmpty()) emptySet() else method.calls
                // A surviving factory references production callback code even if no invocation occurs.
                val productionReferences = calls + if (directOnly) emptySet() else method.lambdaTargets
                productionReferences.filter { it in snapshot.methods }.forEach(pending::addLast)
                val owner = methodOwner(current)
                val initializedOwners = setOf(owner) + snapshot.initializationDependencies[owner].orEmpty()
                initializedOwners.map { "$it.<clinit>()V" }.filter { it in snapshot.methods }.forEach(pending::addLast)
                val referencedOwners = method.references
                    .filter { '(' !in it }.map { it.substringBefore(':').substringBeforeLast('.') }.toSet()
                val constructedOwners = calls
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

/** Used only after changed resource keys are proved unread; other proof gaps remain active. */
internal fun SnapshotComparison.resourceAnalysisGaps(): List<String> = analysisGaps(resourceProof = true)

private fun methodOwner(identity: String): String = identity.substringBefore('(').substringBeforeLast('.')

private fun fieldOwner(identity: String): String = identity.substringBefore(':').substringBeforeLast('.')

/** A field operation must survive on the same revision that declares the added field. */
private fun addedFieldWitnesses(before: ProgramSnapshot, after: ProgramSnapshot, reachedChanges: Set<String>): Set<String> {
    val added = after.fields.keys - before.fields.keys
    return reachedChanges.flatMap { identity ->
        val old = before.methods[identity]?.references.orEmpty().groupingBy { it }.eachCount()
        val current = after.methods.getValue(identity).references.groupingBy { it }.eachCount()
        added.filter { it in current && old[it] != current[it] }
    }.toSet()
}

private fun addedInterfaceInitializationWitness(
    before: ProgramSnapshot, after: ProgramSnapshot, reached: Set<String>, obligation: ProofObligation,
): Boolean {
    val owner = obligation.owner
    if (before.initializationTriggerMethods.any { methodOwner(it) == owner } ||
        after.initializationTriggerMethods.filter { methodOwner(it) == owner }.toSet() != setOf(obligation.identity)) return false
    return after.initializationDependencies.any { (initialized, dependencies) ->
        val previous = before.initializationDependencies[initialized] ?: return@any false
        owner in dependencies && owner !in previous &&
            reached.any { methodOwner(it) == initialized }
    }
}

/** A sole first concrete instance interface method changes initialization without being invoked. */
private fun interfaceInitializationWitness(
    base: ProgramSnapshot, head: ProgramSnapshot, witnessedBase: Set<String>, witnessedHead: Set<String>,
    obligation: ProofObligation,
): Boolean =
    base.classShapes == head.classShapes &&
        (addedInterfaceInitializationWitness(base, head, witnessedHead, obligation) ||
            addedInterfaceInitializationWitness(head, base, witnessedBase, obligation))

/** Factory ownership alone is insufficient: its implementation must be independently present in the compared production graph. */
private fun lambdaDifferenceWitness(
    snapshot: ProgramSnapshot, witnessed: Set<String>, changed: Set<String>, obligation: ProofObligation,
): Boolean = obligation.identity in witnessed && snapshot.methods.getValue(obligation.identity).lambdaTargets.any { target ->
    val modifiers = snapshot.methods[target]?.modifiers.orEmpty()
    target in changed && target in witnessed && methodOwner(target) == obligation.owner &&
        MethodModifier.PRIVATE in modifiers && MethodModifier.SYNTHETIC in modifiers && MethodModifier.BRIDGE !in modifiers
}

/** Every existing revision must independently certify same-input removal by selected controls. */
private fun removedBySelectedControls(identity: String, base: ProgramSnapshot, head: ProgramSnapshot): Boolean =
    (identity in base.methods || identity in head.methods) && (identity !in base.methods || identity in base.controlRemovedMethods) &&
        (identity !in head.methods || identity in head.controlRemovedMethods)
